package dev.crossserverchat.net;

import com.github.fppt.jedismock.RedisServer;
import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.protocol.MessageType;
import dev.crossserverchat.protocol.RelayMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRelayNetworkTest {
	private static final String SECRET = "shared-network-test-secret-123456";

	@TempDir
	Path temporaryDirectory;

	@Test
	@Timeout(15)
	void relaysEncryptedMessagesBetweenSymmetricServers() throws Exception {
		RedisServer redis = RedisServer.newRedisServer().start();
		CountDownLatch receivedMessage = new CountDownLatch(1);
		AtomicReference<RelayMessage> received = new AtomicReference<>();

		RedisRelayTransport first = new RedisRelayTransport(
				config("survival", redis.getHost(), redis.getBindPort()),
				LoggerFactory.getLogger("crossserverchat-test-first"),
				message -> { }
		);
		RedisRelayTransport second = new RedisRelayTransport(
				config("creative", redis.getHost(), redis.getBindPort()),
				LoggerFactory.getLogger("crossserverchat-test-second"),
				message -> {
					received.set(message);
					receivedMessage.countDown();
				}
		);

		try {
			first.start();
			second.start();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			while (receivedMessage.getCount() != 0 && System.nanoTime() < deadline) {
				first.publish(MessageType.PLAYER_CHAT, UUID.randomUUID(), "Alex", "network test");
				Thread.sleep(100);
			}

			assertTrue(receivedMessage.await(0, TimeUnit.MILLISECONDS), "second server did not receive the message");
			assertEquals("survival", received.get().server());
			assertEquals(MessageType.PLAYER_CHAT, received.get().type());
			assertEquals("Alex", received.get().playerName());
			assertEquals("network test", received.get().text());
		} finally {
			first.close();
			second.close();
			redis.stop();
		}
	}

	private RelayConfig config(String serverName, String redisHost, int redisPort) throws IOException {
		Path path = temporaryDirectory.resolve(serverName + ".yaml");
		Files.writeString(path, """
				version: 5
				enabled: true
				serverName: "%s"
				redisHost: "%s"
				redisPort: %d
				redisUsername: "default"
				redisPassword: "test-password"
				sharedSecret: "%s"
				message-relay:
				  - player-chat: enabled
				    messageFormat: "[%%server%%] <%%player%%> %%message%%"
				  - player-join: enabled
				    messageFormat: "[%%server%%] %%player%% joined"
				  - player-leave: enabled
				    messageFormat: "[%%server%%] %%player%% left"
				  - player-death: enabled
				    messageFormat: "[%%server%%] %%message%%"
				connectTimeoutSeconds: 2
				reconnectDelaySeconds: 1
				""".formatted(serverName, redisHost, redisPort, SECRET));
		return RelayConfig.load(path, LoggerFactory.getLogger("crossserverchat-test-config"));
	}
}
