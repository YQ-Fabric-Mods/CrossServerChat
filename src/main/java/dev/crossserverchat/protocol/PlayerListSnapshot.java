package dev.crossserverchat.protocol;

import java.util.List;

public record PlayerListSnapshot(
		int protocol,
		String serverId,
		String server,
		List<SyncedPlayer> players
) {
	public static final int CURRENT_PROTOCOL = 1;

	public static PlayerListSnapshot create(
			String serverId,
			String server,
			List<SyncedPlayer> players
	) {
		return new PlayerListSnapshot(CURRENT_PROTOCOL, serverId, server, List.copyOf(players));
	}
}
