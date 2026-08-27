package dev.crossserverchat.net;

import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.protocol.MessageCodec;
import dev.crossserverchat.protocol.MessageType;
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
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client relay mode. A single background thread maintains the TCP connection
 * and reconnects after failures. Messages sent while disconnected are dropped.
 */
public final class RelayClient implements RelayTransport {
	private final RelayConfig config;
	private final Logger logger;
	private final MessageCodec codec;
	private final Consumer<RelayMessage> remoteMessageConsumer;
	private final RecentMessageCache recentMessages = new RecentMessageCache();
	private final AtomicBoolean running = new AtomicBoolean();
	private final ExecutorService writer = Executors.newSingleThreadExecutor(
			Thread.ofPlatform().daemon().name("cross-server-chat-writer").factory()
	);
	private volatile Connection connection;
	private volatile Thread worker;

	public RelayClient(RelayConfig config, Logger logger, Consumer<RelayMessage> remoteMessageConsumer) {
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
		worker = Thread.ofPlatform().daemon().name("cross-server-chat-client").start(this::connectionLoop);
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
		Connection current = connection;
		if (current == null) {
			logger.debug("Dropping CrossServerChat message while disconnected");
			return;
		}
		try {
			current.send(codec, message);
		} catch (IOException exception) {
			logger.debug("CrossServerChat send failed", exception);
			current.close();
		}
	}

	private void connectionLoop() {
		while (running.get()) {
			Connection current = null;
			try {
				current = connect();
				connection = current;
				logger.info("Connected to CrossServerChat host {}:{}", config.host(), config.port());
				readMessages(current);
			} catch (IOException exception) {
				if (running.get()) {
					logger.warn("CrossServerChat connection failed: {}. Retrying in {} seconds.",
							exception.getMessage(), config.reconnectDelaySeconds());
				}
			} finally {
				if (connection == current) {
					connection = null;
				}
				if (current != null) {
					current.close();
				}
			}

			if (running.get()) {
				try {
					Thread.sleep(config.reconnectDelaySeconds() * 1000L);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private Connection connect() throws IOException {
		Socket socket = new Socket();
		try {
			socket.setKeepAlive(true);
			socket.setTcpNoDelay(true);
			socket.connect(
					new InetSocketAddress(config.host(), config.port()),
					config.connectTimeoutSeconds() * 1000
			);
			return new Connection(socket);
		} catch (IOException exception) {
			closeQuietly(socket);
			throw exception;
		}
	}

	private void readMessages(Connection current) throws IOException {
		while (running.get()) {
			RelayMessage message = codec.read(current.input);
			if (!message.isStructurallyValid(System.currentTimeMillis())) {
				throw new IOException("Host sent an invalid encrypted payload");
			}
			if (config.serverName().equals(message.server()) || !recentMessages.markIfNew(message.id())) {
				continue;
			}
			remoteMessageConsumer.accept(message);
		}
	}

	@Override
	public void close() {
		boolean wasRunning = running.getAndSet(false);
		Connection current = connection;
		connection = null;
		if (current != null) {
			current.close();
		}
		Thread currentWorker = worker;
		if (currentWorker != null) {
			currentWorker.interrupt();
		}
		writer.shutdownNow();
		if (wasRunning) {
			logger.info("CrossServerChat client stopped");
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

	private static final class Connection implements Closeable {
		private final Socket socket;
		private final DataInputStream input;
		private final DataOutputStream output;

		private Connection(Socket socket) throws IOException {
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
