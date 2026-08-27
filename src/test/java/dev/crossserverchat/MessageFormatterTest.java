package dev.crossserverchat;

import dev.crossserverchat.protocol.RelayMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageFormatterTest {
	@Test
	void rendersMiniMessageStylesAndPlaceholders() {
		Component rendered = MessageFormatter.render(
				"<gray>[<green>%server%</green>] <aqua>%player%</aqua>: <white>%message%",
				message("survival", "Steve", "Hello")
		);

		assertEquals("[survival] Steve: Hello", plainText(rendered));
		assertEquals(NamedTextColor.GREEN, effectiveStyle(rendered, "survival").color());
		assertEquals(NamedTextColor.AQUA, effectiveStyle(rendered, "Steve").color());
		assertEquals(NamedTextColor.WHITE, effectiveStyle(rendered, "Hello").color());
	}

	@Test
	void doesNotParseTagsFromPlaceholderValues() {
		Component rendered = MessageFormatter.render(
				"<green>%player%: %message%",
				message("survival", "<bold>Steve</bold>", "<red>Danger</red>")
		);

		assertEquals(
				"<bold>Steve</bold>: <red>Danger</red>",
				plainText(rendered)
		);
	}

	@Test
	void preservesTheExistingPlainFormat() {
		Component rendered = MessageFormatter.render(
				"[%server%] <%player%> %message%",
				message("survival", "Steve", "Hello")
		);

		assertEquals("[survival] <Steve> Hello", plainText(rendered));
	}

	@Test
	void onlyColorsTheServerSectionInTheDefaultFormat() {
		Component rendered = MessageFormatter.render(
				"<gray>[%server%]</gray> <%player%> %message%",
				message("survival", "Steve", "Hello")
		);

		assertEquals("[survival] <Steve> Hello", plainText(rendered));
		assertEquals(NamedTextColor.GRAY, effectiveStyle(rendered, "survival").color());
		assertNull(effectiveStyle(rendered, "Steve").color());
		assertNull(effectiveStyle(rendered, "Hello").color());
	}

	private static RelayMessage message(String server, String player, String text) {
		return RelayMessage.create(server, UUID.randomUUID(), player, text);
	}

	private static String plainText(Component component) {
		StringBuilder result = new StringBuilder();
		appendPlainText(component, result);
		return result.toString();
	}

	private static void appendPlainText(Component component, StringBuilder result) {
		if (component instanceof TextComponent textComponent) {
			result.append(textComponent.content());
		}
		for (Component child : component.children()) {
			appendPlainText(child, result);
		}
	}

	private static Style effectiveStyle(Component component, String content) {
		Style style = findStyle(component, Style.empty(), content);
		if (style == null) {
			throw new AssertionError("Text component not found: " + content);
		}
		return style;
	}

	private static Style findStyle(Component component, Style parentStyle, String content) {
		Style style = component.style().merge(
				parentStyle,
				Style.Merge.Strategy.IF_ABSENT_ON_TARGET
		);
		if (component instanceof TextComponent textComponent
				&& textComponent.content().contains(content)) {
			return style;
		}
		for (Component child : component.children()) {
			Style found = findStyle(child, style, content);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
