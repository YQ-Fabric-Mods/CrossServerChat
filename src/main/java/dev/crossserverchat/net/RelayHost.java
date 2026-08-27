package dev.crossserverchat.net;

import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.protocol.MessageCodec;
import dev.crossserverchat.protocol.RecentMessageCache;
import dev.crossserverchat.protocol.RelayMessage;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Central relay mode. The Minecraft server also listens for the other Fabric
 * servers and forwards each valid message to every connected peer.
 */
public final class RelayHost implements RelayTransport {
	private final RelayConfig config;
	private final Logger logger;
	private final MessageCodec codec;
	private final Consumer<RelayMessage> remoteMessageConsumer;
	private final RecentMessageCache recentMessages = new RecentMessageCache();
	private final CopyOnWriteArrayList<ClientConnection> clients = new CopyOnWriteArrayList<>();
	private final ExecutorService ioPool = Executors.newCachedThreadPool(
			Thread.ofPlatform().daemon().name("cross-server-chat-host-", 0).factory()
	);
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile ServerSocket listener;

	public RelayHost(RelayConfig config, Logger logger, Consumer<RelayMessage> remoteMessageConsumer) {
		this.config = config;
		this.logger = logger;
		this.codec = new MessageCodec(config.sharedSecret());
		this.remoteMessageConsumer = remoteMessageConsumer;
	}

	@Override
	public void start() throws IOException {
		ServerSocket newListener = new ServerSocket();
		try {
			newListener.setReuseAddress(true);
			newListener.bind(new InetSocketAddress(config.bindAddress(), config.port()));
		} catch (IOException exception) {
			closeQuietly(newListener);
			throw exception;
		}
		listener = newListener;
		running.set(true);
		ioPool.execute(this::acceptLoop);
		logger.info("CrossServerChat host listening on {}:{}", config.bindAddress(), config.port());
	}

	@Override
	public void publish(UUID playerId, String playerName, String text) {
		if (!running.get()) {
			return;
		}
		RelayMessage message = RelayMessage.create(config.serverName(), playerId, playerName, text);
		recentMessages.markIfNew(message.id());
		try {
			ioPool.execute(() -> broadcast(message, null));
		} catch (RejectedExecutionException ignored) {
			// The server is shutting down between the running check and submit.
		}
	}

	private void acceptLoop() {
		while (running.get()) {
			try {
				Socket socket = listener.accept();
				socket.setKeepAlive(true);
				socket.setTcpNoDelay(true);
				ioPool.execute(() -> handle(socket));
			} catch (SocketException exception) {
				if (running.get()) {
					logger.error("CrossServerChat accept loop stopped unexpectedly", exception);
				}
				return;
			} catch (IOException exception) {
				if (running.get()) {
					logger.warn("Could not accept a CrossServerChat connection", exception);
				}
			}
		}
	}

	private void handle(Socket socket) {
		ClientConnection client;
		try {
			client = new ClientConnection(socket);
		} catch (IOException exception) {
			closeQuietly(socket);
			return;
		}

		clients.add(client);
		logger.info("CrossServerChat peer connected from {}", socket.getRemoteSocketAddress());
		try {
			while (running.get() && !socket.isClosed()) {
				RelayMessage message = codec.read(client.input);
				if (!message.isStructurallyValid(System.currentTimeMillis())) {
					logger.warn("Closing relay peer {} after an invalid encrypted payload",
							socket.getRemoteSocketAddress());
					return;
				}
				if (config.serverName().equals(message.server()) || !recentMessages.markIfNew(message.id())) {
					continue;
				}

				remoteMessageConsumer.accept(message);
				broadcast(message, client);
			}
		} catch (IOException exception) {
			if (running.get()) {
				logger.info("CrossServerChat peer {} disconnected: {}",
						socket.getRemoteSocketAddress(), exception.getMessage());
			}
		} finally {
			clients.remove(client);
			client.close();
		}
	}

	private void broadcast(RelayMessage message, ClientConnection source) {
		for (ClientConnection client : clients) {
			if (client == source) {
				continue;
			}
			try {
				client.send(codec, message);
			} catch (IOException exception) {
				clients.remove(client);
				client.close();
			}
		}
	}

	@Override
	public void close() {
		boolean wasRunning = running.getAndSet(false);
		closeQuietly(listener);
		for (ClientConnection client : clients) {
			client.close();
		}
		clients.clear();
		ioPool.shutdownNow();
		if (wasRunning) {
			logger.info("CrossServerChat host stopped");
		}
	}

	private static void closeQuietly(Closeable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (IOException ignored) {
		}
	}

	private static final class ClientConnection implements Closeable {
		private final Socket socket;
		private final DataInputStream input;
		private final DataOutputStream output;

		private ClientConnection(Socket socket) throws IOException {
			this.socket = socket;
			this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
			this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
		}

		private synchronized void send(MessageCodec codec, RelayMessage message) throws IOException {
			codec.write(output, message);
		}

		@Override
		public void close() {
			closeQuietly(socket);
		}
	}
}
