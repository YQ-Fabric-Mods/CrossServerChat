package dev.crossserverchat.config;

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

		assertEquals(2, config.version());
		assertEquals(RelayConfig.Mode.CLIENT, config.mode());
		assertEquals("survival", config.serverName());
		assertEquals("relay.example.com", config.host());
		assertEquals(9000, config.port());
		assertTrue(Files.exists(yamlPath));
		assertFalse(Files.exists(jsonPath));

		String yaml = Files.readString(yamlPath);
		assertTrue(yaml.contains("version: 2"));
		assertEquals(10, yaml.lines().filter(line -> line.startsWith("# ")).count());

		RelayConfig reloaded = RelayConfig.load(yamlPath, LoggerFactory.getLogger("crossserverchat-config-test"));
		assertEquals(config.serverName(), reloaded.serverName());
		assertEquals(config.sharedSecret(), reloaded.sharedSecret());
	}
}
