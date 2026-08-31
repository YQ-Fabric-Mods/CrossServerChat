package dev.crossserverchat.net;

import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.protocol.MessageCodec;
import dev.crossserverchat.protocol.MessageType;
import dev.crossserverchat.protocol.RecentMessageCache;
import dev.crossserverchat.protocol.RelayMessage;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dev.crossserverchat.CrossServerChatMod.REDIS_CHANNEL;

/**
 * Symmetric Redis Pub/Sub transport. Every Minecraft server publishes and
 * subscribes to the same channel. Messages are encrypted before Redis sees
 * them; Redis authentication only controls access to the channel.
 */
public final class RedisRelayTransport implements RelayTransport {
	private final RelayConfig config;
	private final Logger logger;
	private final MessageCodec codec;
	private final Consumer<RelayMessage> remoteMessageConsumer;
	private final RecentMessageCache recentMessages = new RecentMessageCache();
	private final AtomicBoolean running = new AtomicBoolean();
	private final ExecutorService writer = Executors.newSingleThreadExecutor(
			Thread.ofPlatform().daemon().name("cross-server-chat-redis-writer").factory()
	);
	private volatile Jedis publisher;
	private volatile Jedis subscriber;
	private volatile Thread subscriberWorker;

	public RedisRelayTransport(
			RelayConfig config,
			Logger logger,
			Consumer<RelayMessage> remoteMessageConsumer
	) {
		this.config = config;
		this.logger = logger;
		this.codec = new MessageCodec(config.sharedSecret());
		this.remoteMessageConsumer = remoteMessageConsumer;
	}

	@Override
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		subscriberWorker = Thread.ofPlatform()
				.daemon()
				.name("cross-server-chat-redis-subscriber")
				.start(this::subscriptionLoop);
	}

	@Override
	public void publish(MessageType type, UUID playerId, String playerName, String text) {
		if (!running.get()) {
			return;
		}

		RelayMessage message = RelayMessage.create(type, config.serverName(), playerId, playerName, text);
		recentMessages.markIfNew(message.id());
		try {
			writer.execute(() -> send(message));
		} catch (RejectedExecutionException ignored) {
			// The server is shutting down between the running check and submit.
		}
	}

	private void send(RelayMessage message) {
		try {
			Jedis current = publisher;
			if (current == null) {
				current = connect(false);
				publisher = current;
			}
			current.publish(REDIS_CHANNEL, codec.encodeBase64(message));
		} catch (IOException exception) {
			logger.error("Could not encrypt a CrossServerChat message", exception);
		} catch (JedisException exception) {
			closePublisher();
			logger.warn("Could not publish a CrossServerChat message to Redis: {}", exception.getMessage());
		}
	}

	private void subscriptionLoop() {
		while (running.get()) {
			try (Jedis current = connect(true)) {
				subscriber = current;
				current.subscribe(new RedisListener(), REDIS_CHANNEL);
			} catch (JedisException exception) {
				if (running.get()) {
					logger.warn("CrossServerChat Redis subscription failed: {}. Retrying in {} seconds.",
							exception.getMessage(), config.reconnectDelaySeconds());
				}
			} finally {
				subscriber = null;
			}

			if (running.get() && !waitBeforeReconnect()) {
				return;
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

	private boolean waitBeforeReconnect() {
		try {
			Thread.sleep(config.reconnectDelaySeconds() * 1000L);
			return true;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void receive(String payload) {
		try {
			RelayMessage message = codec.decodeBase64(payload);
			if (!message.isStructurallyValid(System.currentTimeMillis())) {
				logger.warn("Ignoring an invalid encrypted CrossServerChat message from Redis");
				return;
			}
			if (config.serverName().equals(message.server()) || !recentMessages.markIfNew(message.id())) {
				return;
			}
			remoteMessageConsumer.accept(message);
		} catch (IOException exception) {
			logger.warn("Ignoring an unreadable CrossServerChat message from Redis: {}", exception.getMessage());
		}
	}

	@Override
	public void close() {
		boolean wasRunning = running.getAndSet(false);

		Jedis currentSubscriber = subscriber;
		subscriber = null;
		if (currentSubscriber != null) {
			currentSubscriber.close();
		}
		closePublisher();

		Thread currentWorker = subscriberWorker;
		if (currentWorker != null) {
			currentWorker.interrupt();
		}
		writer.shutdownNow();
		if (wasRunning) {
			logger.info("CrossServerChat Redis transport stopped");
		}
	}

	private void closePublisher() {
		Jedis current = publisher;
		publisher = null;
		if (current != null) {
			current.close();
		}
	}

	private final class RedisListener extends JedisPubSub {
		@Override
		public void onMessage(String channel, String message) {
			receive(message);
		}

		@Override
		public void onSubscribe(String channel, int subscribedChannels) {
			logger.info("Connected to Redis {}:{} and subscribed to '{}'",
					config.redisHost(), config.redisPort(), channel);
		}
	}
}
