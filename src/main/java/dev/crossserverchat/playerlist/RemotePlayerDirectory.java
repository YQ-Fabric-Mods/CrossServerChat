package dev.crossserverchat.playerlist;

import dev.crossserverchat.protocol.PlayerListSnapshot;
import dev.crossserverchat.protocol.SyncedPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class RemotePlayerDirectory {
	private final TreeMap<String, PlayerListSnapshot> snapshots = new TreeMap<>();
	private Map<UUID, RemotePlayerView> sentView = Map.of();

	public void put(PlayerListSnapshot snapshot) {
		snapshots.put(snapshot.serverId(), snapshot);
	}

	public void remove(String serverId) {
		snapshots.remove(serverId);
	}

	public void replaceAll(Map<String, PlayerListSnapshot> replacement) {
		snapshots.clear();
		snapshots.putAll(replacement);
	}

	public Diff reconcile(Set<UUID> localPlayerIds) {
		Map<UUID, RemotePlayerView> next = merge(localPlayerIds);
		List<RemotePlayerView> added = new ArrayList<>();
		List<PlayerUpdate> updated = new ArrayList<>();
		List<UUID> removed = new ArrayList<>();

		for (Map.Entry<UUID, RemotePlayerView> entry : next.entrySet()) {
			RemotePlayerView before = sentView.get(entry.getKey());
			RemotePlayerView after = entry.getValue();
			if (before == null) {
				added.add(after);
			} else if (!before.name().equals(after.name())) {
				removed.add(entry.getKey());
				added.add(after);
			} else {
				boolean latencyChanged = before.latency() != after.latency();
				boolean displayChanged = !before.server().equals(after.server());
				if (latencyChanged || displayChanged) {
					updated.add(new PlayerUpdate(after, latencyChanged, displayChanged));
				}
			}
		}

		for (UUID uuid : sentView.keySet()) {
			if (!next.containsKey(uuid)) {
				removed.add(uuid);
			}
		}

		sentView = Map.copyOf(next);
		return new Diff(List.copyOf(added), List.copyOf(updated), List.copyOf(removed));
	}

	public List<RemotePlayerView> currentView() {
		return List.copyOf(sentView.values());
	}

	public List<UUID> clear() {
		List<UUID> removed = List.copyOf(sentView.keySet());
		snapshots.clear();
		sentView = Map.of();
		return removed;
	}

	private Map<UUID, RemotePlayerView> merge(Set<UUID> localPlayerIds) {
		TreeMap<String, RemotePlayerView> byUuid = new TreeMap<>();
		for (PlayerListSnapshot snapshot : snapshots.values()) {
			for (SyncedPlayer player : snapshot.players()) {
				UUID uuid = UUID.fromString(player.uuid());
				if (!localPlayerIds.contains(uuid)) {
					byUuid.putIfAbsent(player.uuid(), new RemotePlayerView(
							uuid,
							player.name(),
							player.latency(),
							snapshot.serverId(),
							snapshot.server()
					));
				}
			}
		}

		Map<UUID, RemotePlayerView> merged = new LinkedHashMap<>();
		for (RemotePlayerView player : byUuid.values()) {
			merged.put(player.uuid(), player);
		}
		return merged;
	}

	public record PlayerUpdate(
			RemotePlayerView player,
			boolean latencyChanged,
			boolean displayChanged
	) {
	}

	public record Diff(
			List<RemotePlayerView> added,
			List<PlayerUpdate> updated,
			List<UUID> removed
	) {
		public boolean empty() {
			return added.isEmpty() && updated.isEmpty() && removed.isEmpty();
		}
	}
}
