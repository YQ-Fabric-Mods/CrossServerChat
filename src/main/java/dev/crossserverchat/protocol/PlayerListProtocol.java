package dev.crossserverchat.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

public final class PlayerListProtocol {
	public static final int MAX_PLAYERS_PER_SNAPSHOT = 10_000;

	private PlayerListProtocol() {
	}

	public static String serverId(String serverName) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(serverName.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public static boolean validNotification(PlayerListNotification notification) {
		return notification.protocol() == PlayerListNotification.CURRENT_PROTOCOL
				&& validServerId(notification.serverId());
	}

	public static boolean validSnapshot(String redisField, PlayerListSnapshot snapshot) {
		if (snapshot.protocol() != PlayerListSnapshot.CURRENT_PROTOCOL
				|| !validServerId(redisField)
				|| !redisField.equals(snapshot.serverId())
				|| !validText(snapshot.server(), 64)
				|| !redisField.equals(serverId(snapshot.server()))
				|| snapshot.players() == null
				|| snapshot.players().size() > MAX_PLAYERS_PER_SNAPSHOT) {
			return false;
		}

		Set<UUID> seen = new HashSet<>();
		String previousUuid = null;
		for (SyncedPlayer player : snapshot.players()) {
			if (player == null || !validText(player.name(), 16)) {
				return false;
			}
			UUID uuid;
			try {
				uuid = UUID.fromString(player.uuid());
			} catch (IllegalArgumentException | NullPointerException exception) {
				return false;
			}
			if (!uuid.toString().equals(player.uuid()) || !seen.add(uuid)) {
				return false;
			}
			if (previousUuid != null && Comparator.<String>naturalOrder().compare(previousUuid, player.uuid()) >= 0) {
				return false;
			}
			previousUuid = player.uuid();
		}
		return true;
	}

	private static boolean validServerId(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}

	private static boolean validText(String value, int maximumLength) {
		return value != null
				&& !value.isBlank()
				&& value.length() <= maximumLength
				&& value.indexOf('\n') < 0
				&& value.indexOf('\r') < 0;
	}
}
