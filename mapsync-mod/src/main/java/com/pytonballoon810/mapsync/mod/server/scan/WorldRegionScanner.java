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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

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
	private static final @NotNull MsServerLog logger = MsServerLog.get(WorldRegionScanner.class);
	private static final @NotNull Pattern MCA_NAME =
		Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	// Per-chunk throttling is now driven by the capture worker's queue
	// depth (see `awaitQueueSpace` below) rather than a fixed sleep —
	// without back-pressure the scanner outruns the encode-and-store
	// worker and drops tens of thousands of chunks on big worlds.

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
		final List<Path> candidates = candidateRegionDirsFor(this.server, level);
		final Path regionDir = candidates.stream()
			.filter(Files::isDirectory)
			.findFirst()
			.orElse(null);
		if (regionDir == null) {
			logger.warn(
				"No region directory found for {} — scan will skip this dimension. "
					+ "Tried: {}. Server-side capture will still record chunks as "
					+ "players load them; but historical pre-MapSync chunks won't "
					+ "be backfilled into the DB.",
				level.dimension().identifier(),
				candidates.stream().map(Path::toString).collect(Collectors.joining(", "))
			);
			return 0;
		}
		logger.info("Scanning region files for {} at {}",
			level.dimension().identifier(), regionDir);
		final Path marker = this.state.dataDir().resolve(".world-scanned-" + safeName(level));
		if (Files.exists(marker)) {
			logger.info("Skipping {} scan — marker file already exists at {}",
				level.dimension().identifier(), marker);
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
			// Half-queue back-pressure target so the capture worker has
			// breathing room to drain while the scanner sleeps. Lower
			// than the full capacity to avoid the brief race where
			// `awaitQueueSpace` returns just as the queue fills again.
			final int backPressureTarget = this.state.chunkCapture().queueCapacity() / 2;
			for (int i = 0; i < present.length; i++) {
				if (!present[i]) continue;
				final int localX = i & 31;
				final int localZ = (i >> 5) & 31;
				final int cx = (rx << 5) | localX;
				final int cz = (rz << 5) | localZ;
				// Wait if the capture worker is falling behind. Pinning
				// the producer rate to the consumer rate is the only way
				// to avoid the bounded queue dropping chunks under
				// sustained load; the explicit per-chunk sleep on its
				// own can't anticipate the worker's actual encode-and-
				// store latency on this particular machine.
				this.state.chunkCapture().awaitQueueSpace(backPressureTarget);
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

	/// Candidate region directories to try for a given dimension, in
	/// preference order. The first one that resolves to an actual directory
	/// wins. Returning a list (rather than a single path) lets us support
	/// both vanilla layouts and the "namespaced dimensions" layout that
	/// AMP-managed servers, some modded server packs, and any worldgen
	/// scheme using datapack-style dimensions tend to use uniformly:
	///
	/// - Vanilla overworld: `<world>/region/`
	/// - Vanilla nether: `<world>/DIM-1/region/`
	/// - Vanilla end: `<world>/DIM1/region/`
	/// - Namespaced (any dim): `<world>/dimensions/<namespace>/<path>/region/`
	///
	/// Paths are normalized to absolute before return so the redundant
	/// `./` segments `MinecraftServer.getWorldPath(LevelResource.ROOT)`
	/// introduces don't trip up `Files.isDirectory` checks on some JDK /
	/// FS combinations.
	private static @NotNull List<Path> candidateRegionDirsFor(
		final @NotNull MinecraftServer server,
		final @NotNull ServerLevel level
	) {
		final Path root = server.getWorldPath(LevelResource.ROOT);
		final Identifier dim = level.dimension().identifier();
		final String ns = dim.getNamespace();
		final String path = dim.getPath();
		final List<Path> raw = new ArrayList<>(2);
		if ("minecraft".equals(ns)) {
			switch (path) {
				case "overworld" -> raw.add(root.resolve("region"));
				case "the_nether" -> raw.add(root.resolve("DIM-1").resolve("region"));
				case "the_end" -> raw.add(root.resolve("DIM1").resolve("region"));
				default -> {
				}
			}
		}
		// Always also try the namespaced layout — datapack-style dim
		// folders apply equally to vanilla and modded dimensions on
		// servers that opt into them globally.
		raw.add(root.resolve("dimensions").resolve(ns).resolve(path).resolve("region"));
		final List<Path> out = new ArrayList<>(raw.size());
		for (final Path p : raw) {
			out.add(p.toAbsolutePath().normalize());
		}
		return out;
	}

	private static @NotNull String safeName(final @NotNull ServerLevel level) {
		return level.dimension().identifier().toString().replace(':', '~').replace('/', '~');
	}
}
