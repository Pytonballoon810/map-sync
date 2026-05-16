package com.pytonballoon810.mapsync.mod.net.packet;

import com.pytonballoon810.mapsync.mod.data.ChunkTile;
import com.pytonballoon810.mapsync.mod.net.Packet;
import com.pytonballoon810.mapsync.mod.net.buffers.BufferReader;
import com.pytonballoon810.mapsync.mod.net.buffers.BufferWriter;
import com.pytonballoon810.mapsync.mod.utils.Assertions;
import org.jetbrains.annotations.NotNull;

/// This packet is sent in two situations:
///
/// 1. Clients are relaying chunk data to each other in real time.
/// 2. You have requested synchronisation via [ServerboundCatchupRequestPacket].
public record ChunkTilePacket(
	@NotNull ChunkTile chunkTile
) implements Packet {
	public static final int PACKET_ID = 4;

	public ChunkTilePacket {
		Assertions.assertNotNull(chunkTile);
	}

	public static @NotNull Packet read(
		final @NotNull BufferReader reader
	) throws Exception {
		return new ChunkTilePacket(
			ChunkTile.read(reader)
		);
	}

	@Override
	public void write(
		final @NotNull BufferWriter writer
	) throws Exception {
		this.chunkTile().write(writer);
	}
}
