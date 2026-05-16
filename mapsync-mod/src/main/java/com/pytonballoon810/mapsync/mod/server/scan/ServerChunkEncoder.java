package com.pytonballoon810.mapsync.mod.server.scan;

import com.pytonballoon810.mapsync.mod.net.buffers.BufferWriter;
import com.pytonballoon810.mapsync.mod.utils.MagicValues;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.NotNull;

/// Server-side equivalent of [com.pytonballoon810.mapsync.mod.sync.Cartography]'s
/// `chunkTileFromLevel` + [com.pytonballoon810.mapsync.mod.data.ChunkTile]'s
/// `writeColumns`, but without any of the client-only state those reach
/// for (notably `Utils.mc = Minecraft.getInstance()` and the static
/// `Utils.getBiomeRegistry()` call). Produces the same wire bytes a
/// client would have produced for the chunk, so server-stored data
/// relays seamlessly through the existing catchup flow.
///
/// All inputs are passed explicitly (server level for light + biome
/// registry; the chunk itself) so the class loads safely on a dedicated
/// server JVM.
public final class ServerChunkEncoder {
	private ServerChunkEncoder() {
	}

	/// Mirrors the client's column-encoding format byte for byte:
	/// for each of the 256 columns, u16 biome id + u8 light + u8 layer
	/// count + (i16 y, u16 blockStateId) * layerCount. Combined with the
	/// SHA-1 digest of the produced bytes, this is what gets stored as
	/// `chunk_data.data` / `chunk_data.hash`.
	public static @NotNull Encoded encode(
		final @NotNull LevelChunk chunk,
		final @NotNull ServerLevel level
	) throws Exception {
		final Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final BufferWriter writer = new BufferWriter(out);
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);
		final int minBuildHeight = chunk.getMinY();

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				final int worldX = (chunk.getPos().x() << 4) | x;
				final int worldZ = (chunk.getPos().z() << 4) | z;
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				pos.set(worldX, y, worldZ);
				BlockState bs = chunk.getBlockState(pos);

				// First pass: collect the layer stack the same way
				// blockColumnFromChunk does — walk down from the surface,
				// dropping into each newly-different non-air block until
				// we hit an opaque one or the chunk floor.
				final int[] layerYs = new int[127];
				final BlockState[] layerStates = new BlockState[127];
				int layerCount = 0;
				while (y >= minBuildHeight) {
					if (layerCount < 127) {
						layerYs[layerCount] = pos.getY();
						layerStates[layerCount] = bs;
						layerCount++;
					}
					if (bs.canOcclude()) break;
					final BlockState prev = bs;
					do {
						pos.setY(--y);
						bs = chunk.getBlockState(pos);
					}
					while ((bs == prev || bs.isAir()) && y >= -4096);
				}

				final Holder<Biome> biomeHolder = chunk.getNoiseBiome(x >> 2, pos.getY() >> 2, z >> 2);
				final int biomeId = biomeRegistry.getId(biomeHolder.value());
				final int light = level.getBrightness(LightLayer.BLOCK, pos);

				writer.writeUnt16(Math.max(0, biomeId));
				writer.writeUnt8(Math.min(255, Math.max(0, light)));
				writer.writeUnt8(Math.min(127, layerCount));
				for (int i = 0; i < Math.min(127, layerCount); i++) {
					writer.writeInt16((short) layerYs[i]);
					writer.writeUnt16(Block.BLOCK_STATE_REGISTRY.getId(layerStates[i]));
				}
			}
		}

		final byte[] columnBytes = out.toByteArray();
		final MessageDigest md = MessageDigest.getInstance("SHA-1");
		final byte[] hash = md.digest(columnBytes);
		if (hash.length != MagicValues.SHA1_HASH_LENGTH) {
			throw new IllegalStateException("unexpected hash length " + hash.length);
		}
		return new Encoded(columnBytes, hash);
	}

	public record Encoded(byte @NotNull [] columnBytes, byte @NotNull [] hash) {
	}
}
