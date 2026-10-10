package dev.crossserverchat.compat;

import me.drex.vanish.api.VanishAPI;
import me.drex.vanish.api.VanishEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

public final class VanishCompat {
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("melius-vanish");

	private VanishCompat() {
	}

	public static boolean isVanished(ServerPlayer player) {
		return LOADED && Hooks.isVanished(player);
	}

	public static void register(Runnable refresh) {
		if (LOADED) {
			Hooks.register(refresh);
		}
	}

	// Only load references to the optional API after Fabric confirms Vanish is installed.
	private static final class Hooks {
		private static boolean isVanished(ServerPlayer player) {
			return VanishAPI.isVanished(player);
		}

		private static void register(Runnable refresh) {
			VanishEvents.VANISH_EVENT.register((player, vanished) -> refresh.run());
		}
	}
}
