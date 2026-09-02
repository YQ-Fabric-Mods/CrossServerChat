package dev.crossserverchat.playerlist;

import dev.crossserverchat.protocol.PlayerListProtocol;
import dev.crossserverchat.protocol.PlayerListSnapshot;
import dev.crossserverchat.protocol.SyncedPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotePlayerDirectoryTest {
	private static final UUID ALEX = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

	@Test
	void producesAddsUpdatesAndRemovals() {
		RemotePlayerDirectory directory = new RemotePlayerDirectory();
		directory.put(snapshot("Survival", "Alex", 20));

		RemotePlayerDirectory.Diff added = directory.reconcile(Set.of());
		assertEquals(List.of(ALEX), added.added().stream().map(RemotePlayerView::uuid).toList());

		directory.put(snapshot("Survival", "Alex", 50));
		RemotePlayerDirectory.Diff updated = directory.reconcile(Set.of());
		assertTrue(updated.updated().getFirst().latencyChanged());

		directory.remove(PlayerListProtocol.serverId("Survival"));
		RemotePlayerDirectory.Diff removed = directory.reconcile(Set.of());
		assertEquals(List.of(ALEX), removed.removed());
	}

	@Test
	void usesLowestServerIdForDuplicateUuidAndExcludesLocalPlayers() {
		RemotePlayerDirectory directory = new RemotePlayerDirectory();
		PlayerListSnapshot survival = snapshot("Survival", "Alex", 20);
		PlayerListSnapshot creative = snapshot("Creative", "Alex", 40);
		directory.put(survival);
		directory.put(creative);

		RemotePlayerDirectory.Diff diff = directory.reconcile(Set.of());
		String expectedServer = survival.serverId().compareTo(creative.serverId()) < 0
				? "Survival"
				: "Creative";
		assertEquals(expectedServer, diff.added().getFirst().server());

		RemotePlayerDirectory.Diff local = directory.reconcile(Set.of(ALEX));
		assertEquals(List.of(ALEX), local.removed());
		assertTrue(directory.currentView().isEmpty());
	}

	private PlayerListSnapshot snapshot(String server, String name, int latency) {
		String serverId = PlayerListProtocol.serverId(server);
		return PlayerListSnapshot.create(
				serverId,
				server,
				List.of(new SyncedPlayer(ALEX.toString(), name, latency))
		);
	}
}
