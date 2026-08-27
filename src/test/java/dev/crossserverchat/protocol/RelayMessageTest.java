package dev.crossserverchat.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayMessageTest {
	@Test
	void acceptsThePayloadShapeForEveryMessageType() {
		for (MessageType type : MessageType.values()) {
			String text = switch (type) {
				case PLAYER_CHAT -> "Hello";
				case PLAYER_JOIN, PLAYER_LEAVE -> "";
				case PLAYER_DEATH -> "Steve fell from a high place";
			};
			RelayMessage message = RelayMessage.create(type, "survival", UUID.randomUUID(), "Steve", text);

			assertTrue(message.isStructurallyValid(System.currentTimeMillis()), type.configKey());
		}
	}

	@Test
	void rejectsMissingChatTextAndUnexpectedJoinText() {
		RelayMessage emptyChat = RelayMessage.create(
				MessageType.PLAYER_CHAT, "survival", UUID.randomUUID(), "Steve", ""
		);
		RelayMessage joinWithText = RelayMessage.create(
				MessageType.PLAYER_JOIN, "survival", UUID.randomUUID(), "Steve", "unexpected"
		);

		assertFalse(emptyChat.isStructurallyValid(System.currentTimeMillis()));
		assertFalse(joinWithText.isStructurallyValid(System.currentTimeMillis()));
	}
}
