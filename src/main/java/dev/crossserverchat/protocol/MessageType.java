package dev.crossserverchat.protocol;

import com.google.gson.annotations.SerializedName;

public enum MessageType {
	@SerializedName("player-chat")
	PLAYER_CHAT("player-chat"),
	@SerializedName("player-join")
	PLAYER_JOIN("player-join"),
	@SerializedName("player-leave")
	PLAYER_LEAVE("player-leave"),
	@SerializedName("player-death")
	PLAYER_DEATH("player-death");

	private final String configKey;

	MessageType(String configKey) {
		this.configKey = configKey;
	}

	public String configKey() {
		return configKey;
	}
}
