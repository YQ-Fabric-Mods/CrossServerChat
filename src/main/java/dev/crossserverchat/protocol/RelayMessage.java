package dev.crossserverchat.protocol;

import java.util.UUID;

/**
 * One relayed server message. The whole JSON representation is encrypted and
 * authenticated by the frame codec before it is sent.
 */
public record RelayMessage(
		int protocol,
		String id,
		String server,
		MessageType type,
		String playerId,
		String playerName,
		String text,
		long timestamp
) {
	public static final int CURRENT_PROTOCOL = 4;
	public static final int MAX_TEXT_LENGTH = 512;
	public static final long MAX_CLOCK_SKEW_MILLIS = 120_000L;

	public static RelayMessage create(
			MessageType type,
			String server,
			UUID playerId,
			String playerName,
			String text
	) {
		return new RelayMessage(
				CURRENT_PROTOCOL,
				UUID.randomUUID().toString(),
				server,
				type,
				playerId.toString(),
				playerName,
				text,
				System.currentTimeMillis()
		);
	}

	public boolean isStructurallyValid(long now) {
		if (protocol != CURRENT_PROTOCOL
				|| !validText(id, 36)
				|| !validText(server, 64)
				|| type == null
				|| !validText(playerId, 36)
				|| !validText(playerName, 64)
				|| !validMessageText()) {
			return false;
		}
		if (timestamp < now - MAX_CLOCK_SKEW_MILLIS || timestamp > now + MAX_CLOCK_SKEW_MILLIS) {
			return false;
		}
		try {
			UUID.fromString(id);
			UUID.fromString(playerId);
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private boolean validMessageText() {
		if (text == null || text.length() > MAX_TEXT_LENGTH
				|| text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
			return false;
		}
		return switch (type) {
			case PLAYER_CHAT, PLAYER_DEATH -> !text.isBlank();
			case PLAYER_JOIN, PLAYER_LEAVE -> text.isEmpty();
		};
	}

	private static boolean validText(String value, int maxLength) {
		return value != null
				&& !value.isBlank()
				&& value.length() <= maxLength
				&& value.indexOf('\n') < 0
				&& value.indexOf('\r') < 0;
	}
}
