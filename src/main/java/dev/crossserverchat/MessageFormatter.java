package dev.crossserverchat;

import dev.crossserverchat.protocol.RelayMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

final class MessageFormatter {
	private static final MiniMessage MINI_MESSAGE =
			MiniMessage.miniMessage(MiniMessage.Preset.FORMATTED_TEXT);

	private MessageFormatter() {
	}

	static Component render(String format, RelayMessage message) {
		String template = format
				.replace("%server%", "<server/>")
				.replace("%player%", "<player/>")
				.replace("%message%", "<message/>");

		return MINI_MESSAGE.deserialize(
				template,
				Placeholder.unparsed("server", message.server()),
				Placeholder.unparsed("player", message.playerName()),
				Placeholder.unparsed("message", message.text())
		);
	}
}
