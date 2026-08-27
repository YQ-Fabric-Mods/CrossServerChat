package dev.crossserverchat.config;

import dev.crossserverchat.protocol.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayConfigTest {
	@TempDir
	Path temporaryDirectory;

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
