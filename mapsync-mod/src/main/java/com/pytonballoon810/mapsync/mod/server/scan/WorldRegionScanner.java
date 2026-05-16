package com.pytonballoon810.mapsync.mod.server.scan;

import com.pytonballoon810.mapsync.mod.server.MapSyncServerState;
import com.pytonballoon810.mapsync.mod.server.MsServerLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Walks each dimension's `region/*.mca` files on first server start and
/// force-loads every chunk that has saved data on disk. MC's regular
/// chunk-load pipeline runs as usual on the main thread, the
/// [WorldChunkCapture] CHUNK_LOAD hook catches every load, and the chunks
/// land in `db.sqlite` — populating it with everything the world has
/// already explored, including chunks no player has revisited since
/// MapSync was installed.
///
/// Disk presence is checked by reading just the 4 KiB header of each
/// `.mca` file directly: a 32-bit big-endian offset+length entry per
/// chunk position, top 24 bits = offset (zero ⇒ never saved). This
/// avoids generating fresh terrain for the gaps inside a partially-
/// populated region (a region file's mere existence does *not* imply all
/// 1024 chunks were ever saved), and avoids fighting Minecraft for
/// `RegionFile` instance ownership — we don't construct any.
///
/// Per-dimension marker files (`<world>/mapsync/.world-scanned-<dim>`)
/// keep the scan one-shot per (server, dimension). Interrupted scans
/// safely re-run from scratch on the next session — the captures are
/// idempotent (REPLACE INTO keyed by the synthetic server UUID).
public final class WorldRegionScanner {
	private static final @NotNull Logger logger = MsServerLog.get(WorldRegionScanner.class);
	private static final @NotNull Pattern MCA_NAME =
		Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	/// Throttle between chunk-load requests. Each call already blocks on
	/// the main thread's chunk-load pipeline, so this is mostly to keep
	/// the scanner from monopolising the main-thread dispatch queue when
	/// no players are connected (which would otherwise process scan
	/// requests as fast as physically possible and stall any incoming
	/// player connections).
	private static final long PER_CHUNK_SLEEP_MS = 2L;

	public sealed interface Status permits Status.Idle, Status.Scanning, Status.Done, Status.Failed {
		record Idle() implements Status {
		}

		record Scanning(int chunksRequested, @NotNull String dimension) implements Status {
		}

		record Done(int chunksRequested) implements Status {
		}

		record Failed(@NotNull String reason) implements Status {
		}
	}

	private final @NotNull MinecraftServer server;
	private final @NotNull MapSyncServerState state;
	private final @NotNull AtomicReference<Status> status = new AtomicReference<>(new Status.Idle());
	private final @NotNull Thread worker;

	public WorldRegionScanner(
		final @NotNull MinecraftServer server,
		final @NotNull MapSyncServerState state
	) {
		this.server = server;
		this.state = state;
		this.worker = new Thread(this::run, "MapSync-WorldScanner");
		this.worker.setDaemon(true);
	}

	public void start() {
		this.worker.start();
	}

	public @NotNull Status status() {
		return this.status.get();
	}

	private void run() {
		int totalRequested = 0;
		try {
			for (final ServerLevel level : this.server.getAllLevels()) {
				totalRequested += this.scanLevel(level, totalRequested);
			}
			this.status.set(new Status.Done(totalRequested));
			logger.info("World scan complete: {} chunks force-loaded", totalRequested);
		}
		catch (final InterruptedException ignored) {
			Thread.currentThread().interrupt();
			this.status.set(new Status.Failed("interrupted"));
		}
		catch (final Throwable t) {
			logger.warn("World scan aborted", t);
			this.status.set(new Status.Failed(t.getMessage() != null ? t.getMessage() : t.toString()));
		}
	}

	private int scanLevel(
		final @NotNull ServerLevel level,
		final int startingCount
	) throws InterruptedException, IOException {
		final Path regionDir = regionDirFor(this.server, level);
		if (regionDir == null || !Files.isDirectory(regionDir)) {
			return 0;
		}
		final Path marker = this.state.dataDir().resolve(".world-scanned-" + safeName(level));
		if (Files.exists(marker)) {
			return 0;
		}
		this.status.set(new Status.Scanning(startingCount, level.dimension().identifier().toString()));

		int requested = 0;
		final List<Path> mcaFiles = new ArrayList<>();
		try (final Stream<Path> stream = Files.list(regionDir)) {
			stream.filter((p) -> MCA_NAME.matcher(p.getFileName().toString()).matches())
				.forEach(mcaFiles::add);
		}
		catch (final IOException e) {
			logger.warn("Failed to list region dir {}", regionDir, e);
			return 0;
		}
		logger.info("Scanning {} region file(s) in {}", mcaFiles.size(), regionDir);

		for (final Path mca : mcaFiles) {
			final Matcher m = MCA_NAME.matcher(mca.getFileName().toString());
			if (!m.matches()) continue;
			final int rx = Integer.parseInt(m.group(1));
			final int rz = Integer.parseInt(m.group(2));

			final boolean[] present = readPresenceTable(mca);
			if (present == null) {
				continue;
			}
			for (int i = 0; i < present.length; i++) {
				if (!present[i]) continue;
				final int localX = i & 31;
				final int localZ = (i >> 5) & 31;
				final int cx = (rx << 5) | localX;
				final int cz = (rz << 5) | localZ;
				try {
					level.getChunk(cx, cz, ChunkStatus.FULL, true);
					requested++;
					this.status.set(new Status.Scanning(
						startingCount + requested,
						level.dimension().identifier().toString()
					));
				}
				catch (final Throwable t) {
					logger.warn("Force-load of ({},{}) in {} failed",
						cx, cz, level.dimension().identifier(), t);
				}
				if (PER_CHUNK_SLEEP_MS > 0) {
					Thread.sleep(PER_CHUNK_SLEEP_MS);
				}
			}
		}

		Files.createDirectories(marker.getParent());
		Files.writeString(marker, Instant.now().toString());
		logger.info("Marked {} as scanned (requested {} chunks)",
			level.dimension().identifier(), requested);
		return requested;
	}

	/// Reads the 4 KiB chunk-offset table at the head of a region file
	/// and returns a 1024-bit presence map (one entry per chunk position,
	/// in standard `(z << 5) | x` order). Returns null on I/O error so
	/// the caller can skip the file gracefully.
	private static boolean @Nullable [] readPresenceTable(
		final @NotNull Path mcaFile
	) {
		try (final FileChannel ch = FileChannel.open(mcaFile, StandardOpenOption.READ)) {
			final ByteBuffer buf = ByteBuffer.allocate(4096);
			int read = 0;
			while (read < 4096) {
				final int n = ch.read(buf);
				if (n <= 0) {
					if (read == 0) return null;
					break;
				}
				read += n;
			}
			buf.flip();
			final boolean[] present = new boolean[1024];
			for (int i = 0; i < 1024 && buf.remaining() >= 4; i++) {
				final int entry = buf.getInt();
				// Top 24 bits = sector offset; bottom 8 bits = sector
				// count. Either being zero means "never saved".
				present[i] = (entry & 0xFFFF_FF00) != 0;
			}
			return present;
		}
		catch (final IOException e) {
			logger.warn("Failed to read .mca header of {}", mcaFile, e);
			return null;
		}
	}

	/// `<world>/[dim-folder]/region/`. Vanilla dimensions use hardcoded
	/// folder names (overworld is the world root, the_nether is DIM-1,
	/// the_end is DIM1); everything else lands at
	/// `<world>/dimensions/<namespace>/<path>/`.
	private static @Nullable Path regionDirFor(
		final @NotNull MinecraftServer server,
		final @NotNull ServerLevel level
	) {
		final Path root = server.getWorldPath(LevelResource.ROOT);
		final Identifier dim = level.dimension().identifier();
		final String ns = dim.getNamespace();
		final String path = dim.getPath();
		if ("minecraft".equals(ns)) {
			return switch (path) {
				case "overworld" -> root.resolve("region");
				case "the_nether" -> root.resolve("DIM-1").resolve("region");
				case "the_end" -> root.resolve("DIM1").resolve("region");
				default -> root.resolve("dimensions").resolve(ns).resolve(path).resolve("region");
			};
		}
		return root.resolve("dimensions").resolve(ns).resolve(path).resolve("region");
	}

	private static @NotNull String safeName(final @NotNull ServerLevel level) {
		return level.dimension().identifier().toString().replace(':', '~').replace('/', '~');
	}
}
