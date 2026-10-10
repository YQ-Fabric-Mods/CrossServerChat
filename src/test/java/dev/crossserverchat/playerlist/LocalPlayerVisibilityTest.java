package dev.crossserverchat.playerlist;

import dev.crossserverchat.protocol.PlayerListProtocol;
import dev.crossserverchat.protocol.PlayerListSnapshot;
import dev.crossserverchat.protocol.PlayerListSyncCodec;
import dev.crossserverchat.protocol.SyncedPlayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPlayerVisibilityTest {
	private static final UUID ADMIN = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
	private static final UUID PLAYER = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
	private static final String SERVER_ID = PlayerListProtocol.serverId("Survival");
	private final PlayerListSyncCodec codec = new PlayerListSyncCodec("vanish-test-secret-that-is-long-enough");

	@Test
	void hiddenPlayersAreExcludedFromTheSnapshotReceivedByOtherServers() throws IOException {
		PlayerListSnapshot received = transmit(List.of(
				new LocalPlayer(ADMIN, "Admin", 10, true),
				new LocalPlayer(PLAYER, "Player", 20, false)
		));
		assertEquals(List.of(new SyncedPlayer(PLAYER.toString(), "Player", 20)), received.players());
		assertTrue(PlayerListProtocol.validSnapshot(SERVER_ID, received));

		RemotePlayerDirectory directory = new RemotePlayerDirectory();
		directory.put(received);
		assertEquals(List.of(PLAYER), directory.reconcile(Set.of()).added().stream()
				.map(RemotePlayerView::uuid).toList());
	}

	@Test
	void vanishingRemovesTheRemoteEntryAndUnvanishingRestoresIt() throws IOException {
		RemotePlayerDirectory directory = new RemotePlayerDirectory();
		directory.put(transmit(List.of(new LocalPlayer(ADMIN, "Admin", 10, false))));
		assertEquals(List.of(ADMIN), directory.reconcile(Set.of()).added().stream()
				.map(RemotePlayerView::uuid).toList());

		PlayerListSnapshot hidden = transmit(List.of(new LocalPlayer(ADMIN, "Admin", 10, true)));
		assertTrue(hidden.players().isEmpty());
		assertTrue(PlayerListProtocol.validSnapshot(SERVER_ID, hidden));
		directory.put(hidden);
		RemotePlayerDirectory.Diff removed = directory.reconcile(Set.of());
		assertEquals(List.of(ADMIN), removed.removed());
		assertTrue(removed.added().isEmpty());
		assertTrue(directory.currentView().isEmpty());

		// Repeated full snapshots (heartbeat/reconnect) must not restore a hidden entry.
		directory.put(hidden);
		assertTrue(directory.reconcile(Set.of()).empty());

		directory.put(transmit(List.of(new LocalPlayer(ADMIN, "Admin", 15, false))));
		RemotePlayerDirectory.Diff restored = directory.reconcile(Set.of());
		assertEquals(List.of(ADMIN), restored.added().stream().map(RemotePlayerView::uuid).toList());
		assertEquals(15, restored.added().getFirst().latency());
		assertTrue(restored.removed().isEmpty());
	}

	private PlayerListSnapshot transmit(List<LocalPlayer> players) throws IOException {
		PlayerListSnapshot snapshot = PlayerListSnapshot.create(
				SERVER_ID,
				"Survival",
				LocalPlayer.toVisibleSyncedPlayers(players)
		);
		return codec.decodeSnapshot(codec.encodeSnapshot(snapshot));
	}
}
