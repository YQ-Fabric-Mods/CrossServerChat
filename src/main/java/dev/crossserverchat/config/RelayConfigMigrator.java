package dev.crossserverchat.config;

import dev.crossserverchat.protocol.MessageType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RelayConfigMigrator {
	static final int CURRENT_VERSION = 3;

	private RelayConfigMigrator() {
	}

	static boolean migrate(Map<String, Object> values) throws IOException {
		int originalVersion = version(values);
		int version = originalVersion;
		if (version > CURRENT_VERSION) {
			throw new IOException("Unsupported configuration version " + version
					+ "; expected at most " + CURRENT_VERSION);
		}

		while (version < CURRENT_VERSION) {
			switch (version) {
				case 2 -> migrateV2ToV3(values);
				default -> throw new IOException("Unsupported configuration version " + version);
			}
			version = version(values);
		}
		return originalVersion != version;
	}

	private static void migrateV2ToV3(Map<String, Object> values) throws IOException {
		Object oldFormat = values.remove("messageFormat");
		String chatFormat;
		if (oldFormat == null) {
			chatFormat = RelayConfig.defaultFormat(MessageType.PLAYER_CHAT);
		} else if (oldFormat instanceof String format) {
			chatFormat = format;
		} else {
			throw new IOException("messageFormat must be a string");
		}

		List<Map<String, Object>> messageRelay = new ArrayList<>();
		for (MessageType type : MessageType.values()) {
			Map<String, Object> rule = new LinkedHashMap<>();
			rule.put(type.configKey(), "enabled");
			rule.put("messageFormat", type == MessageType.PLAYER_CHAT
					? chatFormat
					: RelayConfig.defaultFormat(type));
			messageRelay.add(rule);
		}
		values.put("message-relay", messageRelay);
		values.put("version", 3);
	}

	private static int version(Map<String, Object> values) throws IOException {
		Object value = values.get("version");
		if (value instanceof Number number && number.doubleValue() == number.intValue()) {
			return number.intValue();
		}
		throw new IOException("version is required and must be an integer");
	}
}
