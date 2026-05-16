package com.pytonballoon810.mapsync.mod.net.packet;

import com.pytonballoon810.mapsync.mod.data.RegionPos;
import com.pytonballoon810.mapsync.mod.net.Packet;
import com.pytonballoon810.mapsync.mod.net.buffers.BufferReader;
import com.pytonballoon810.mapsync.mod.net.buffers.BufferWriter;
import com.pytonballoon810.mapsync.mod.utils.Assertions;
import org.jetbrains.annotations.NotNull;

/// You send this in response to a [ClientboundRegionTimestampsPacket], listing all the regions you'd like the server to
/// elaborate on. You should expect a [ClientboundChunkTimestampsResponsePacket].
///
/// - Prev: [ClientboundRegionTimestampsPacket]
/// - Next: [ClientboundChunkTimestampsResponsePacket]
public record ServerboundChunkTimestampsRequestPacket(
	@NotNull String dimension,
	@NotNull RegionPos region
) implements Packet {
	public static final int PACKET_ID = 8;

	public ServerboundChunkTimestampsRequestPacket {
		Assertions.assertNotNull(dimension);
		Assertions.assertNotNull(region);
	}

	public static @NotNull Packet read(
		final @NotNull BufferReader reader
	) throws Exception {
		return new ServerboundChunkTimestampsRequestPacket(
			reader.readString(),
			new RegionPos(reader.readInt16(), reader.readInt16())
		);
	}

	@Override
	public void write(
		final @NotNull BufferWriter writer
	) throws Exception {
		writer.writeString(this.dimension());
		writer.writeInt16((short) this.region().x());
		writer.writeInt16((short) this.region().z());
	}
}
