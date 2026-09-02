package dev.crossserverchat.protocol;

public record PlayerListNotification(
		int protocol,
		String serverId
) {
	public static final int CURRENT_PROTOCOL = 1;

	public static PlayerListNotification create(String serverId) {
		return new PlayerListNotification(CURRENT_PROTOCOL, serverId);
	}
}
