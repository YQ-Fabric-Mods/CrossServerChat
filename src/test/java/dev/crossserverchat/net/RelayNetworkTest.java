package dev.crossserverchat.net;

import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.protocol.RelayMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayNetworkTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	@Timeout(15)
	void forwardsAClientMessageThroughTheHostToAnotherClient() throws Exception {
		int port = findFreePort();
		String secret = "shared-network-test-secret-123456";
		RelayConfig hostConfig = config("host", "hub", port, secret);
		RelayConfig firstConfig = config("client", "survival", port, secret);
		RelayConfig secondConfig = config("client", "creative", port, secret);

		CountDownLatch hostReceived = new CountDownLatch(1);
		CountDownLatch secondReceived = new CountDownLatch(1);
		CountDownLatch hostPublishedReceived = new CountDownLatch(1);
		AtomicReference<RelayMessage> received = new AtomicReference<>();

		RelayHost host = new RelayHost(hostConfig, LoggerFactory.getLogger("crossserverchat-test-host"),
				message -> hostReceived.countDown());
		RelayClient first = new RelayClient(firstConfig, LoggerFactory.getLogger("crossserverchat-test-first"),
				message -> { });
		RelayClient second = new RelayClient(secondConfig, LoggerFactory.getLogger("crossserverchat-test-second"),
				message -> {
					if ("network test".equals(message.text())) {
						received.set(message);
						secondReceived.countDown();
					} else if ("host test".equals(message.text())) {
						hostPublishedReceived.countDown();
					}
				});

		try {
			host.start();
			first.start();
			second.start();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			while ((hostReceived.getCount() != 0 || secondReceived.getCount() != 0)
					&& System.nanoTime() < deadline) {
				first.publish(UUID.randomUUID(), "Alex", "network test");
				Thread.sleep(100);
			}

			assertTrue(hostReceived.await(0, TimeUnit.MILLISECONDS), "host did not receive the message");
			assertTrue(secondReceived.await(0, TimeUnit.MILLISECONDS), "second client did not receive the message");
			assertEquals("survival", received.get().server());
			assertEquals("Alex", received.get().playerName());
			assertEquals("network test", received.get().text());

			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
			while (hostPublishedReceived.getCount() != 0 && System.nanoTime() < deadline) {
				host.publish(UUID.randomUUID(), "Steve", "host test");
				Thread.sleep(100);
			}
			assertTrue(hostPublishedReceived.await(0, TimeUnit.MILLISECONDS),
					"client did not receive a locally published host message");
		} finally {
			first.close();
			second.close();
			host.close();
		}
	}

	private RelayConfig config(String mode, String serverName, int port, String secret) throws IOException {
		Path path = temporaryDirectory.resolve(serverName + ".yaml");
		Files.writeString(path, """
				version: 2
				mode: "%s"
				serverName: "%s"
				bindAddress: "127.0.0.1"
				host: "127.0.0.1"
				port: %d
				sharedSecret: "%s"
				messageFormat: "[%%server%%] <%%player%%> %%message%%"
				connectTimeoutSeconds: 2
				reconnectDelaySeconds: 1
				""".formatted(mode, serverName, port, secret));
		return RelayConfig.load(path, LoggerFactory.getLogger("crossserverchat-test-config"));
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
