package dev.crossserverchat.compat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VanishCompatTest {
	@Test
	void worksWithoutVanishOnTheRuntimeClasspath() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("me.drex.vanish.api.VanishAPI"));

		// The absent-mod branch must not load the API or access the player.
		assertFalse(VanishCompat.isVanished(null));
		AtomicBoolean refreshed = new AtomicBoolean();
		VanishCompat.register(() -> refreshed.set(true));
		assertFalse(refreshed.get());
	}
}
