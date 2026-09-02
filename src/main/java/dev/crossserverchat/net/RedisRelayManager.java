package dev.crossserverchat.net;

import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.config.RelayConfigManager;
import dev.crossserverchat.playerlist.LocalPlayer;
import dev.crossserverchat.playerlist.PlayerListPacketSender;
import dev.crossserverchat.playerlist.RemotePlayerDirectory;
import dev.crossserverchat.protocol.MessageCodec;
import dev.crossserverchat.protocol.MessageType;
import dev.crossserverchat.protocol.PlayerListNotification;
import dev.crossserverchat.protocol.PlayerListProtocol;
import dev.crossserverchat.protocol.PlayerListSnapshot;
import dev.crossserverchat.protocol.PlayerListSyncCodec;
import dev.crossserverchat.protocol.RecentMessageCache;
import dev.crossserverchat.protocol.RelayMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static dev.crossserverchat.CrossServerChatConstants.PLAYER_LIST_DATA;
import static dev.crossserverchat.CrossServerChatConstants.PLAYER_LIST_UPDATED_CHANNEL;
import static dev.crossserverchat.CrossServerChatConstants.REDIS_CHANNEL;

public final class RedisRelayManager {
	private static final int PLAYER_LIST_TTL_SECONDS = 30;
	private static final int HEARTBEAT_SECONDS = 10;

	private final MinecraftServer server;
	private final RelayConfigManager configManager;
	private final Logger logger;
	private final BiConsumer<RelayConfig.MessageRelay, RelayMessage> remoteMessageConsumer;
	private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
			1,
			1,
			0L,
			TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(),
			Thread.ofPlatform().name("cross-server-chat-manager").factory()
	);
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().daemon().name("cross-server-chat-timer").factory()
	);
	private final Object taskGate = new Object();
	private final Object connectionLock = new Object();
	private final Set<RedisIdentity> checkedIdentities = new HashSet<>();
	private final RecentMessageCache recentMessages = new RecentMessageCache();
	private final RemotePlayerDirectory remotePlayers = new RemotePlayerDirectory();

	private volatile boolean acceptingOrdinary;
	private volatile boolean acceptingInbound;
	private volatile boolean permanentlyStopping;
	private boolean reloadPending;
	private State state = State.STOPPED;
	private RelayConfig config;
	private MessageCodec messageCodec;
	private PlayerListSyncCodec playerListCodec;
	private PlayerListPacketSender packetSender;
	private String serverId;
	private Jedis command;
	private volatile Jedis subscriber;
	private volatile boolean closingSubscriber;
	private Thread subscriberThread;
	private ScheduledFuture<?> heartbeatFuture;
	private ScheduledFuture<?> reconnectFuture;
	private int heartbeatNumber;

	public RedisRelayManager(
			MinecraftServer server,
			RelayConfigManager configManager,
			Logger logger,
			BiConsumer<RelayConfig.MessageRelay, RelayMessage> remoteMessageConsumer
	) {
		this.server = server;
		this.configManager = configManager;
		this.logger = logger;
		this.remoteMessageConsumer = remoteMessageConsumer;
	}

	public void start(RelayConfig initialConfig) {
		submitControl(() -> applyConfigAndConnect(initialConfig));
	}

	public void publish(MessageType type, UUID playerId, String playerName, String text) {
		submitOrdinary(() -> publishOnWorker(type, playerId, playerName, text));
	}

	public void playerJoined(ServerPlayer player) {
		submitOrdinary(() -> playerMembershipChanged(player, MembershipChange.ADD));
	}

	public void playerDisconnected(ServerPlayer player) {
		submitOrdinary(() -> playerMembershipChanged(player, MembershipChange.REMOVE));
	}

	public boolean requestReload(Consumer<ReloadResult> completion) {
		synchronized (taskGate) {
			if (permanentlyStopping || reloadPending) {
				return false;
			}
			reloadPending = true;
			acceptingOrdinary = false;
			acceptingInbound = false;
			removeQueuedOrdinaryTasks();
			submitControlLocked(() -> reloadOnWorker(completion));
			return true;
		}
	}

	public void stop() {
		synchronized (taskGate) {
			if (permanentlyStopping) {
				return;
			}
			permanentlyStopping = true;
			acceptingOrdinary = false;
			acceptingInbound = false;
			removeQueuedOrdinaryTasks();
			submitControlLocked(this::stopPermanentlyOnWorker);
		}
	}

	private void applyConfigAndConnect(RelayConfig newConfig) {
		config = newConfig;
		if (!config.enabled()) {
			state = State.STOPPED;
			logger.info("CrossServerChat is disabled. Edit config/cross-server-chat.yaml and reload to enable it.");
			return;
		}
		prepareConfig();
		connectAndHydrate();
	}

	private void prepareConfig() {
		messageCodec = new MessageCodec(config.sharedSecret());
		playerListCodec = new PlayerListSyncCodec(config.sharedSecret());
		serverId = PlayerListProtocol.serverId(config.serverName());
		packetSender = new PlayerListPacketSender(server, config.playerListSync().displayFormat());
	}

	private void connectAndHydrate() {
		state = State.CONNECTING;
		try {
			command = connect(false);
			openInboundGate();
			startSubscriber();
			state = State.HYDRATING;

			List<LocalPlayer> localPlayers = List.of();
			if (config.playerListSync().enabled()) {
				warnAboutDuplicateIdentity();
				localPlayers = readLocalPlayers(null, MembershipChange.NONE);
				writeFullSnapshot(localPlayers);
				hydrateRemotePlayers(localPlayers);
			}

			state = State.ACTIVE;
			openOrdinaryGate();
			if (config.playerListSync().enabled()) {
				scheduleHeartbeat();
			}
			logger.info("CrossServerChat connected to Redis as '{}'", config.serverName());
		} catch (IOException | JedisException exception) {
			logger.warn("Could not connect CrossServerChat to Redis: {}", exception.getMessage());
			transitionDisconnected();
		}
	}

	private void startSubscriber() throws IOException {
		CountDownLatch ready = new CountDownLatch(1);
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		int expectedSubscriptions = config.playerListSync().enabled() ? 2 : 1;
		String[] channels = config.playerListSync().enabled()
				? new String[]{REDIS_CHANNEL, PLAYER_LIST_UPDATED_CHANNEL}
				: new String[]{REDIS_CHANNEL};

		closingSubscriber = false;
		subscriberThread = Thread.ofPlatform()
				.daemon()
				.name("cross-server-chat-redis-subscriber")
				.start(() -> subscriptionLoop(ready, failure, expectedSubscriptions, channels));

		try {
			if (!ready.await(config.connectTimeoutSeconds(), TimeUnit.SECONDS)) {
				throw new IOException("Timed out waiting for Redis subscriptions");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for Redis subscriptions", exception);
		}
		if (failure.get() != null) {
			throw new IOException("Redis subscription failed", failure.get());
		}
	}

	private void subscriptionLoop(
			CountDownLatch ready,
			AtomicReference<RuntimeException> failure,
			int expectedSubscriptions,
			String[] channels
	) {
		try (Jedis current = connect(true)) {
			synchronized (connectionLock) {
				subscriber = current;
			}
			current.subscribe(new RedisListener(ready, expectedSubscriptions), channels);
		} catch (RuntimeException exception) {
			failure.compareAndSet(null, exception);
		} finally {
			ready.countDown();
			synchronized (connectionLock) {
				subscriber = null;
			}
			if (!closingSubscriber) {
				requestDisconnected();
			}
		}
	}

	private Jedis connect(boolean blocking) {
		int timeoutMillis = config.connectTimeoutSeconds() * 1000;
		Jedis jedis = new Jedis(
				config.redisHost(),
				config.redisPort(),
				timeoutMillis,
				blocking ? 0 : timeoutMillis
		);
		try {
			jedis.auth(config.redisUsername(), config.redisPassword());
			return jedis;
		} catch (JedisException exception) {
			jedis.close();
			throw exception;
		}
	}

	private void warnAboutDuplicateIdentity() {
		RedisIdentity identity = new RedisIdentity(
				config.redisHost(),
				config.redisPort(),
				config.redisUsername(),
				serverId
		);
		if (checkedIdentities.contains(identity)) {
			return;
		}
		String existing = command.hget(PLAYER_LIST_DATA, serverId);
		checkedIdentities.add(identity);
		if (existing == null) {
			return;
		}

		String warning = "CrossServerChat serverName '" + config.serverName()
				+ "' may be duplicated; cross-server player-list data may be incorrect.";
		logger.error("============================================================");
		logger.error(warning);
		logger.error("============================================================");
		server.execute(() -> {
			Component message = Component.literal(warning)
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.sendSystemMessage(message);
			}
		});
	}

	private void publishOnWorker(MessageType type, UUID playerId, String playerName, String text) {
		if (state != State.ACTIVE) {
			return;
		}
		RelayMessage message = RelayMessage.create(type, config.serverName(), playerId, playerName, text);
		recentMessages.markIfNew(message.id());
		try {
			command.publish(REDIS_CHANNEL, messageCodec.encodeBase64(message));
		} catch (IOException exception) {
			logger.error("Could not encrypt a CrossServerChat message", exception);
		} catch (JedisException exception) {
			redisCommandFailed("publish a chat message", exception);
		}
	}

	private void receiveChat(String payload) {
		try {
			RelayMessage message = messageCodec.decodeBase64(payload);
			if (!message.isStructurallyValid(System.currentTimeMillis())) {
				logger.warn("Ignoring an invalid encrypted CrossServerChat message from Redis");
				return;
			}
			if (config.serverName().equals(message.server()) || !recentMessages.markIfNew(message.id())) {
				return;
			}
			RelayConfig.MessageRelay relay = config.messageRelay(message.type());
			if (relay.enabled()) {
				remoteMessageConsumer.accept(relay, message);
			}
		} catch (IOException exception) {
			logger.warn("Ignoring an unreadable CrossServerChat message from Redis: {}", exception.getMessage());
		}
	}

	private void receivePlayerListNotification(String payload) {
		try {
			PlayerListNotification notification = playerListCodec.decodeNotification(payload);
			if (!PlayerListProtocol.validNotification(notification)) {
				logger.warn("Ignoring an invalid player-list notification from Redis");
				return;
			}
			if (notification.serverId().equals(serverId)) {
				return;
			}

			String snapshotPayload = command.hget(PLAYER_LIST_DATA, notification.serverId());
			if (snapshotPayload == null) {
				remotePlayers.remove(notification.serverId());
			} else {
				readRemoteSnapshot(notification.serverId(), snapshotPayload)
						.ifPresentOrElse(remotePlayers::put,
								() -> remotePlayers.remove(notification.serverId()));
			}
			reconcileRemotePlayers(readLocalPlayers(null, MembershipChange.NONE));
		} catch (IOException exception) {
			logger.warn("Ignoring an unreadable player-list notification from Redis: {}", exception.getMessage());
		} catch (JedisException exception) {
			redisCommandFailed("read a remote player-list snapshot", exception);
		}
	}

	private java.util.Optional<PlayerListSnapshot> readRemoteSnapshot(String field, String payload) {
		try {
			PlayerListSnapshot snapshot = playerListCodec.decodeSnapshot(payload);
			if (!PlayerListProtocol.validSnapshot(field, snapshot)) {
				logger.warn("Ignoring an invalid player-list snapshot in Redis field '{}'", field);
				return java.util.Optional.empty();
			}
			return java.util.Optional.of(snapshot);
		} catch (IOException exception) {
			logger.warn("Ignoring an unreadable player-list snapshot in Redis field '{}': {}",
					field, exception.getMessage());
			return java.util.Optional.empty();
		}
	}

	private void playerMembershipChanged(ServerPlayer eventPlayer, MembershipChange change) {
		if (state != State.ACTIVE || !config.playerListSync().enabled()) {
			return;
		}
		try {
			List<LocalPlayer> localPlayers = readLocalPlayers(eventPlayer, change);
			writeFullSnapshot(localPlayers);
			reconcileRemotePlayers(localPlayers);
			if (change == MembershipChange.ADD) {
				packetSender.sendFull(eventPlayer, remotePlayers.currentView());
			}
		} catch (IOException exception) {
			logger.error("Could not encode the local player-list snapshot", exception);
		} catch (JedisException exception) {
			redisCommandFailed("publish the local player-list snapshot", exception);
		}
	}

	private List<LocalPlayer> readLocalPlayers(ServerPlayer eventPlayer, MembershipChange change) {
		CompletableFuture<List<LocalPlayer>> result = new CompletableFuture<>();
		server.execute(() -> {
			Map<UUID, LocalPlayer> players = new HashMap<>();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				players.put(player.getUUID(), localPlayer(player));
			}
			if (change == MembershipChange.ADD) {
				players.put(eventPlayer.getUUID(), localPlayer(eventPlayer));
			} else if (change == MembershipChange.REMOVE) {
				players.remove(eventPlayer.getUUID());
			}
			List<LocalPlayer> snapshot = new ArrayList<>(players.values());
			snapshot.sort((first, second) -> first.uuid().toString().compareTo(second.uuid().toString()));
			result.complete(List.copyOf(snapshot));
		});
		return result.join();
	}

	private LocalPlayer localPlayer(ServerPlayer player) {
		return new LocalPlayer(
				player.getUUID(),
				player.getGameProfile().name(),
				player.connection.latency()
		);
	}

	private void writeFullSnapshot(List<LocalPlayer> localPlayers) throws IOException {
		PlayerListSnapshot snapshot = PlayerListSnapshot.create(
				serverId,
				config.serverName(),
				localPlayers.stream().map(LocalPlayer::toSyncedPlayer).toList()
		);
		PlayerListNotification notification = PlayerListNotification.create(serverId);
		try (Transaction transaction = command.multi()) {
			transaction.hset(PLAYER_LIST_DATA, serverId, playerListCodec.encodeSnapshot(snapshot));
			Response<List<Long>> expiry = transaction.hexpire(
					PLAYER_LIST_DATA,
					PLAYER_LIST_TTL_SECONDS,
					serverId
			);
			transaction.publish(
					PLAYER_LIST_UPDATED_CHANNEL,
					playerListCodec.encodeNotification(notification)
			);
			transaction.exec();
			if (!expiry.get().equals(List.of(1L))) {
				throw new JedisException("Unexpected HEXPIRE result after HSET: " + expiry.get());
			}
		}
	}

	private void hydrateRemotePlayers(List<LocalPlayer> localPlayers) {
		Map<String, PlayerListSnapshot> snapshots = new HashMap<>();
		for (Map.Entry<String, String> entry : command.hgetAll(PLAYER_LIST_DATA).entrySet()) {
			if (!entry.getKey().equals(serverId)) {
				readRemoteSnapshot(entry.getKey(), entry.getValue())
						.ifPresent(snapshot -> snapshots.put(entry.getKey(), snapshot));
			}
		}
		remotePlayers.replaceAll(snapshots);
		reconcileRemotePlayers(localPlayers);
	}

	private void reconcileRemotePlayers(List<LocalPlayer> localPlayers) {
		Set<UUID> localIds = new HashSet<>();
		for (LocalPlayer player : localPlayers) {
			localIds.add(player.uuid());
		}
		packetSender.broadcast(remotePlayers.reconcile(localIds));
	}

	private void heartbeat() {
		if (state != State.ACTIVE || !config.playerListSync().enabled()) {
			return;
		}
		try {
			List<LocalPlayer> localPlayers = readLocalPlayers(null, MembershipChange.NONE);
			heartbeatNumber = (heartbeatNumber + 1) % 3;
			if (heartbeatNumber == 0) {
				writeFullSnapshot(localPlayers);
				hydrateRemotePlayers(localPlayers);
				return;
			}

			String currentPayload = command.hget(PLAYER_LIST_DATA, serverId);
			if (currentPayload == null || localMembershipChanged(currentPayload, localPlayers)) {
				writeFullSnapshot(localPlayers);
				return;
			}

			List<Long> expiry = command.hexpire(PLAYER_LIST_DATA, PLAYER_LIST_TTL_SECONDS, serverId);
			if (expiry.equals(List.of(-2L))) {
				writeFullSnapshot(localPlayers);
			} else if (!expiry.equals(List.of(1L))) {
				throw new JedisException("Unexpected heartbeat HEXPIRE result: " + expiry);
			}
		} catch (IOException exception) {
			logger.error("Could not process the local player-list snapshot", exception);
		} catch (JedisException exception) {
			redisCommandFailed("run the player-list heartbeat", exception);
		}
	}

	private boolean localMembershipChanged(String payload, List<LocalPlayer> localPlayers) {
		try {
			PlayerListSnapshot snapshot = playerListCodec.decodeSnapshot(payload);
			if (!PlayerListProtocol.validSnapshot(serverId, snapshot)) {
				return true;
			}
			List<String> stored = snapshot.players().stream().map(player -> player.uuid()).toList();
			List<String> current = localPlayers.stream().map(player -> player.uuid().toString()).toList();
			return !stored.equals(current);
		} catch (IOException exception) {
			return true;
		}
	}

	private void scheduleHeartbeat() {
		cancelHeartbeat();
		heartbeatNumber = 0;
		heartbeatFuture = timer.scheduleAtFixedRate(
				() -> submitOrdinary(this::heartbeat),
				HEARTBEAT_SECONDS,
				HEARTBEAT_SECONDS,
				TimeUnit.SECONDS
		);
	}

	private void redisCommandFailed(String operation, JedisException exception) {
		logger.warn("Could not {}: {}", operation, exception.getMessage());
		transitionDisconnected();
	}

	private void requestDisconnected() {
		synchronized (taskGate) {
			if (permanentlyStopping || reloadPending) {
				return;
			}
			acceptingOrdinary = false;
			acceptingInbound = false;
			removeQueuedOrdinaryTasks();
			submitControlLocked(this::transitionDisconnected);
		}
	}

	private void transitionDisconnected() {
		if (state == State.STOPPED || state == State.STOPPING || state == State.DISCONNECTED) {
			return;
		}
		state = State.DISCONNECTED;
		closeTaskGates();
		cancelHeartbeat();
		closeConnections();
		clearRemotePlayers(true);
		logger.warn("CrossServerChat disconnected from Redis; retrying in {} seconds",
				config.reconnectDelaySeconds());
		reconnectFuture = timer.schedule(
				() -> submitControl(this::reconnect),
				config.reconnectDelaySeconds(),
				TimeUnit.SECONDS
		);
	}

	private void reconnect() {
		if (state != State.DISCONNECTED || permanentlyStopping) {
			return;
		}
		connectAndHydrate();
	}

	private void reloadOnWorker(Consumer<ReloadResult> completion) {
		stopCurrent(true);
		if (permanentlyStopping) {
			finishReload(completion, new ReloadResult(false, "Server shutdown interrupted the reload."));
			return;
		}

		try {
			RelayConfig reloaded = configManager.load();
			applyConfigAndConnect(reloaded);
			String message = state == State.DISCONNECTED
					? "CrossServerChat configuration reloaded; Redis is unavailable and will be retried."
					: "CrossServerChat configuration reloaded.";
			finishReload(completion, new ReloadResult(true, message));
		} catch (IOException exception) {
			logger.error("Could not reload CrossServerChat configuration", exception);
			state = State.STOPPED;
			finishReload(completion, new ReloadResult(
					false,
					"CrossServerChat reload failed; the relay remains stopped: " + exception.getMessage()
			));
		}
	}

	private void finishReload(Consumer<ReloadResult> completion, ReloadResult result) {
		synchronized (taskGate) {
			reloadPending = false;
		}
		completion.accept(result);
	}

	private void stopPermanentlyOnWorker() {
		stopCurrent(false);
		worker.shutdown();
		timer.shutdownNow();
		logger.info("CrossServerChat stopped");
	}

	private void stopCurrent(boolean notifyPlayers) {
		state = State.STOPPING;
		closeTaskGates();
		cancelHeartbeat();
		cancelReconnect();
		clearRemotePlayers(notifyPlayers);

		if (command != null && config != null && config.playerListSync().enabled()) {
			try {
				PlayerListNotification notification = PlayerListNotification.create(serverId);
				try (Transaction transaction = command.multi()) {
					transaction.hdel(PLAYER_LIST_DATA, serverId);
					transaction.publish(
							PLAYER_LIST_UPDATED_CHANNEL,
							playerListCodec.encodeNotification(notification)
					);
					transaction.exec();
				}
			} catch (IOException | JedisException exception) {
				logger.warn("Could not remove the local player-list snapshot during shutdown: {}",
						exception.getMessage());
			}
		}
		closeConnections();
		state = State.STOPPED;
	}

	private void clearRemotePlayers(boolean notifyPlayers) {
		List<UUID> removed = remotePlayers.clear();
		if (notifyPlayers && packetSender != null) {
			packetSender.removeAll(removed);
		}
	}

	private void closeConnections() {
		closingSubscriber = true;
		Jedis currentSubscriber;
		synchronized (connectionLock) {
			currentSubscriber = subscriber;
		}
		if (currentSubscriber != null) {
			currentSubscriber.close();
		}
		Thread currentThread = subscriberThread;
		if (currentThread != null) {
			try {
				currentThread.join();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
		subscriberThread = null;
		subscriber = null;

		Jedis currentCommand = command;
		command = null;
		if (currentCommand != null) {
			currentCommand.close();
		}
	}

	private void openInboundGate() {
		synchronized (taskGate) {
			acceptingInbound = true;
		}
	}

	private void openOrdinaryGate() {
		synchronized (taskGate) {
			if (!permanentlyStopping) {
				acceptingOrdinary = true;
			}
		}
	}

	private void closeTaskGates() {
		synchronized (taskGate) {
			acceptingOrdinary = false;
			acceptingInbound = false;
			removeQueuedOrdinaryTasks();
		}
	}

	private void submitOrdinary(Runnable action) {
		synchronized (taskGate) {
			if (!acceptingOrdinary || permanentlyStopping) {
				return;
			}
			submitLocked(TaskKind.NORMAL, action);
		}
	}

	private void submitInbound(Runnable action) {
		synchronized (taskGate) {
			if (!acceptingInbound || permanentlyStopping) {
				return;
			}
			submitLocked(TaskKind.NORMAL, action);
		}
	}

	private void submitControl(Runnable action) {
		synchronized (taskGate) {
			submitControlLocked(action);
		}
	}

	private void submitControlLocked(Runnable action) {
		submitLocked(TaskKind.CONTROL, action);
	}

	private void submitLocked(TaskKind kind, Runnable action) {
		try {
			worker.execute(new ManagedTask(kind, action));
		} catch (RejectedExecutionException ignored) {
			// The permanent stop task has already shut down the worker.
		}
	}

	private void removeQueuedOrdinaryTasks() {
		worker.getQueue().removeIf(task -> task instanceof ManagedTask managed
				&& managed.kind() == TaskKind.NORMAL);
	}

	private void cancelHeartbeat() {
		if (heartbeatFuture != null) {
			heartbeatFuture.cancel(false);
			heartbeatFuture = null;
		}
	}

	private void cancelReconnect() {
		if (reconnectFuture != null) {
			reconnectFuture.cancel(false);
			reconnectFuture = null;
		}
	}

	public record ReloadResult(boolean success, String message) {
	}

	private record RedisIdentity(String host, int port, String username, String serverId) {
	}

	private record ManagedTask(TaskKind kind, Runnable action) implements Runnable {
		@Override
		public void run() {
			action.run();
		}
	}

	private enum TaskKind {
		NORMAL,
		CONTROL
	}

	private enum MembershipChange {
		NONE,
		ADD,
		REMOVE
	}

	private enum State {
		STOPPED,
		CONNECTING,
		HYDRATING,
		ACTIVE,
		DISCONNECTED,
		STOPPING
	}

	private final class RedisListener extends JedisPubSub {
		private final CountDownLatch ready;
		private final int expectedSubscriptions;

		private RedisListener(CountDownLatch ready, int expectedSubscriptions) {
			this.ready = ready;
			this.expectedSubscriptions = expectedSubscriptions;
		}

		@Override
		public void onMessage(String channel, String message) {
			if (REDIS_CHANNEL.equals(channel)) {
				submitInbound(() -> receiveChat(message));
			} else if (PLAYER_LIST_UPDATED_CHANNEL.equals(channel)) {
				submitInbound(() -> receivePlayerListNotification(message));
			}
		}

		@Override
		public void onSubscribe(String channel, int subscribedChannels) {
			if (subscribedChannels == expectedSubscriptions) {
				ready.countDown();
			}
		}
	}
}
