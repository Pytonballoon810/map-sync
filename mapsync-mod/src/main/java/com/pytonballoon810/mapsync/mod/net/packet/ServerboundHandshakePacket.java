package com.pytonballoon810.mapsync.mod.net.packet;

import com.pytonballoon810.mapsync.mod.data.GameAddress;
import com.pytonballoon810.mapsync.mod.net.Packet;
import com.pytonballoon810.mapsync.mod.net.buffers.BufferReader;
import com.pytonballoon810.mapsync.mod.net.buffers.BufferWriter;
import com.pytonballoon810.mapsync.mod.utils.Assertions;
import org.jetbrains.annotations.NotNull;

/// The client should send this to the server *IMMEDIATELY* upon a successful connection. The server should respond
/// with a [ClientboundIdentityRequestPacket].
///
/// - Next: [ClientboundIdentityRequestPacket]
public record ServerboundHandshakePacket(
	@NotNull String modVersion,
	@NotNull GameAddress gameAddress
) implements Packet {
	public static final int PACKET_ID = 1;

	public ServerboundHandshakePacket {
		Assertions.assertNotNull(modVersion);
		Assertions.assertNotNull(gameAddress);
	}

	public static @NotNull Packet read(
		final @NotNull BufferReader reader
	) throws Exception {
		return new ServerboundHandshakePacket(
			reader.readString(),
			new GameAddress(reader.readString())
		);
	}

	@Override
	public void write(
		final @NotNull BufferWriter writer
	) throws Exception {
		writer.writeString(this.modVersion());
		writer.writeString(this.gameAddress().address());
	}
}
