package dev.crossserverchat.config;

import dev.crossserverchat.protocol.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayConfigTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void loadsRedisConfigAndRelayRules() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");
		Files.writeString(yamlPath, """
				version: 5
				enabled: true
				serverName: "survival"
				redisHost: "10.0.0.10"
				redisPort: 6380
				redisUsername: "relay"
				redisPassword: "redis-password"
				sharedSecret: "a-random-test-secret-that-is-long-enough"
				message-relay:
				  - player-chat: enabled
				    messageFormat: "[%server%] <%player%> %message%"
				  - player-join: disabled
				    messageFormat: "[%server%] %player% joined"
				  - player-leave: enabled
				    messageFormat: "[%server%] %player% left"
				  - player-death: enabled
				    messageFormat: "[%server%] %message%"
				connectTimeoutSeconds: 3
				reconnectDelaySeconds: 7
				""");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				LoggerFactory.getLogger("crossserverchat-config-test")
		);

		assertTrue(config.enabled());
		assertEquals("survival", config.serverName());
		assertEquals("10.0.0.10", config.redisHost());
		assertEquals(6380, config.redisPort());
		assertEquals("relay", config.redisUsername());
		assertEquals("redis-password", config.redisPassword());
		assertEquals(3, config.connectTimeoutSeconds());
		assertEquals(7, config.reconnectDelaySeconds());
		assertTrue(config.messageRelay(MessageType.PLAYER_CHAT).enabled());
		assertFalse(config.messageRelay(MessageType.PLAYER_JOIN).enabled());
		assertTrue(config.messageRelay(MessageType.PLAYER_LEAVE).enabled());
		assertTrue(config.messageRelay(MessageType.PLAYER_DEATH).enabled());
	}

	@Test
	void removesRedisChannelWhenMigratingV4Config() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");
		Files.writeString(yamlPath, """
				version: 4
				enabled: false
				redisChannel: "custom-channel"
				message-relay:
				  - player-chat: enabled
				    messageFormat: "[%server%] <%player%> %message%"
				  - player-join: enabled
				    messageFormat: "[%server%] %player% joined"
				  - player-leave: enabled
				    messageFormat: "[%server%] %player% left"
				  - player-death: enabled
				    messageFormat: "[%server%] %message%"
				""");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				LoggerFactory.getLogger("crossserverchat-config-v4-migration-test")
		);

		assertEquals(5, config.version());
		assertFalse(Files.readString(yamlPath).contains("redisChannel"));
	}

	@Test
	void migratesV3ConfigToDisabledRedisConfig() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");
		Files.writeString(yamlPath, """
				version: 3
				mode: "client"
				serverName: "creative"
				bindAddress: "0.0.0.0"
				host: "old-relay.example.com"
				port: 8192
				sharedSecret: "a-random-test-secret-that-is-long-enough"
				message-relay:
				  - player-chat: enabled
				    messageFormat: "[%server%] <%player%> %message%"
				  - player-join: enabled
				    messageFormat: "[%server%] %player% joined"
				  - player-leave: enabled
				    messageFormat: "[%server%] %player% left"
				  - player-death: enabled
				    messageFormat: "[%server%] %message%"
				""");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				LoggerFactory.getLogger("crossserverchat-config-migration-test")
		);

		assertEquals(5, config.version());
		assertFalse(config.enabled());
		assertEquals("127.0.0.1", config.redisHost());
		assertEquals(6379, config.redisPort());
		assertEquals("", config.redisPassword());
		assertFalse(Files.readString(yamlPath).contains("mode:"));
	}
}
