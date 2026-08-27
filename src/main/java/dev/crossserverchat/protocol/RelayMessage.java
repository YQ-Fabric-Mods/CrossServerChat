package dev.crossserverchat.protocol;

import java.util.UUID;

/**
 * One chat message. The whole JSON representation is encrypted and
 * authenticated by the frame codec before it is sent.
 */
public record RelayMessage(
		int protocol,
		String id,
		String server,
		String playerId,
		String playerName,
		String text,
		long timestamp
) {
	public static final int CURRENT_PROTOCOL = 3;
	public static final int MAX_TEXT_LENGTH = 512;
	public static final long MAX_CLOCK_SKEW_MILLIS = 120_000L;

	public static RelayMessage create(String server, UUID playerId, String playerName, String text) {
		return new RelayMessage(
				CURRENT_PROTOCOL,
				UUID.randomUUID().toString(),
				server,
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
				|| !validText(playerId, 36)
				|| !validText(playerName, 64)
				|| !validText(text, MAX_TEXT_LENGTH)) {
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

	private static boolean validText(String value, int maxLength) {
		return value != null
				&& !value.isBlank()
				&& value.length() <= maxLength
				&& value.indexOf('\n') < 0
				&& value.indexOf('\r') < 0;
	}
}
