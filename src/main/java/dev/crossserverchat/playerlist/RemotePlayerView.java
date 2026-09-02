package dev.crossserverchat.playerlist;

import java.util.UUID;

public record RemotePlayerView(
		UUID uuid,
		String name,
		int latency,
		String serverId,
		String server
) {
}
