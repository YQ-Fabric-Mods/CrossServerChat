package dev.crossserverchat.net;

import dev.crossserverchat.protocol.MessageType;

import java.io.IOException;
import java.util.UUID;

public interface RelayTransport extends AutoCloseable {
	void start(boolean blocking) throws IOException;

	boolean connected();

	void publish(MessageType type, UUID playerId, String playerName, String text);

	@Override
	void close();
}
