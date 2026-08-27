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
	void createsACommentedV3ConfigWithAllRelaysEnabled() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				LoggerFactory.getLogger("crossserverchat-config-test")
		);

		assertEquals(3, config.version());
		for (MessageType type : MessageType.values()) {
			assertTrue(config.messageRelay(type).enabled());
		}
		String yaml = Files.readString(yamlPath);
		assertTrue(yaml.contains("message-relay:"));
		assertTrue(yaml.contains("  - player-chat: enabled"));
		assertTrue(yaml.contains("  - player-join: enabled"));
		assertTrue(yaml.contains("  - player-leave: enabled"));
		assertTrue(yaml.contains("  - player-death: enabled"));
		assertTrue(yaml.contains("# Remote message types to display on this server."));
	}

	@Test
	void upgradesLegacyJsonToCommentedYamlAndDeletesJson() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");
		Path jsonPath = temporaryDirectory.resolve("cross-server-chat.json");
		Files.writeString(jsonPath, """
				{
				  "mode": "client",
				  "serverName": "survival",
				  "bindAddress": "0.0.0.0",
				  "host": "relay.example.com",
				  "port": 9000,
				  "sharedSecret": "shared-config-test-secret-123456789",
				  "messageFormat": "[%server%] <%player%> %message%",
				  "connectTimeoutSeconds": 8,
				  "reconnectDelaySeconds": 10
				}
				""");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				jsonPath,
				LoggerFactory.getLogger("crossserverchat-config-test")
		);

		assertEquals(3, config.version());
		assertEquals(RelayConfig.Mode.CLIENT, config.mode());
		assertEquals("survival", config.serverName());
		assertEquals("relay.example.com", config.host());
		assertEquals(9000, config.port());
		assertTrue(Files.exists(yamlPath));
		assertFalse(Files.exists(jsonPath));
		for (MessageType type : MessageType.values()) {
			assertTrue(config.messageRelay(type).enabled());
		}
		assertEquals("[%server%] <%player%> %message%",
				config.messageRelay(MessageType.PLAYER_CHAT).messageFormat());

		String yaml = Files.readString(yamlPath);
		assertTrue(yaml.contains("version: 3"));
		assertTrue(yaml.contains("message-relay:"));
		assertTrue(yaml.contains("# Displays remote player death messages."));

		RelayConfig reloaded = RelayConfig.load(yamlPath, LoggerFactory.getLogger("crossserverchat-config-test"));
		assertEquals(config.serverName(), reloaded.serverName());
		assertEquals(config.sharedSecret(), reloaded.sharedSecret());
	}

	@Test
	void upgradesV2YamlInPlaceAndPreservesTheChatFormat() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");
		Files.writeString(yamlPath, """
				version: 2
				mode: "disabled"
				serverName: "survival"
				messageFormat: "<green>[%server%]</green> %player%: %message%"
				""");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				LoggerFactory.getLogger("crossserverchat-config-test")
		);

		assertEquals(3, config.version());
		assertEquals("<green>[%server%]</green> %player%: %message%",
				config.messageRelay(MessageType.PLAYER_CHAT).messageFormat());
		for (MessageType type : MessageType.values()) {
			assertTrue(config.messageRelay(type).enabled());
		}

		String upgraded = Files.readString(yamlPath);
		assertTrue(upgraded.contains("version: 3"));
		assertTrue(upgraded.contains("  - player-join: enabled"));
		assertTrue(upgraded.contains("  - player-leave: enabled"));
		assertTrue(upgraded.contains("  - player-death: enabled"));
		assertFalse(upgraded.contains("\nmessageFormat:"));
	}

	@Test
	void loadsEnabledAndDisabledV3RelayRules() throws IOException {
		Path yamlPath = temporaryDirectory.resolve("cross-server-chat.yaml");
		Files.writeString(yamlPath, """
				version: 3
				mode: "disabled"
				message-relay:
				  - player-chat: enabled
				    messageFormat: "[%server%] <%player%> %message%"
				  - player-join: disabled
				    messageFormat: "[%server%] %player% joined"
				  - player-leave: enabled
				    messageFormat: "[%server%] %player% left"
				  - player-death: enabled
				    messageFormat: "[%server%] %message%"
				""");

		RelayConfig config = RelayConfig.load(
				yamlPath,
				LoggerFactory.getLogger("crossserverchat-config-test")
		);

		assertTrue(config.messageRelay(MessageType.PLAYER_CHAT).enabled());
		assertFalse(config.messageRelay(MessageType.PLAYER_JOIN).enabled());
		assertTrue(config.messageRelay(MessageType.PLAYER_LEAVE).enabled());
		assertTrue(config.messageRelay(MessageType.PLAYER_DEATH).enabled());
	}
}
