package com.pytonballoon810.mapsync.mod.server.scan;

import com.pytonballoon810.mapsync.mod.server.MapSyncServerState;
import com.pytonballoon810.mapsync.mod.server.MsServerLog;
import com.pytonballoon810.mapsync.mod.server.net.auth.OfflineUuid;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.NotNull;

/// Captures every chunk Minecraft loads on the server into MapSync's
/// chunk database. The `CHUNK_LOAD` event fires for any reason MC has to
/// page a chunk in: a player walking in, a `/forceload`, the
/// [WorldRegionScanner] proactively pulling chunks via `getChunk(x, z)`.
/// All three feed into the same capture queue.
///
/// CHUNK_LOAD runs on the server's main thread, so the handler does
/// almost nothing — just offers the (level, chunk) pair into a bounded
/// blocking queue. A daemon worker thread drains the queue, runs the
/// (CPU-heavy) [ServerChunkEncoder.encode] off the main thread, and
/// writes the result through the existing
/// [com.pytonballoon810.mapsync.mod.server.db.MapSyncDatabase#storeChunkData]
/// path. Stored rows use a stable synthetic UUID (`OfflineUuid.forName("@server")`)
/// for attribution, so the same chunk loaded repeatedly stays one row
/// (REPLACE INTO bumps the timestamp).
public final class WorldChunkCapture implements AutoCloseable {
	private static final @NotNull MsServerLog logger = MsServerLog.get(WorldChunkCapture.class);
	/// Synthetic player UUID under which the server itself "reports"
	/// chunks. Deterministic so repeated captures of the same chunk
	/// stay one row in `player_chunk`.
	public static final @NotNull UUID SERVER_UUID = OfflineUuid.forName("@server");
	/// Soft cap on the in-flight capture queue. The server can hand us
	/// chunks faster than we can encode + write them — at startup
	/// hundreds of spawn-area chunks dump in within a tick. The bound
	/// keeps memory from ballooning during a [WorldRegionScanner] run;
	/// overflows drop oldest, which is acceptable because the chunk
	/// will be re-captured on next load anyway.
	private static final int QUEUE_CAPACITY = 4_096;

	private final @NotNull MapSyncServerState state;
	private final @NotNull BlockingQueue<Task> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
	private final @NotNull Thread worker;
	private final @NotNull AtomicLong captured = new AtomicLong(0L);
	private final @NotNull AtomicLong dropped = new AtomicLong(0L);
	private final @NotNull AtomicLong failed = new AtomicLong(0L);

	public WorldChunkCapture(final @NotNull MapSyncServerState state) {
		this.state = state;
		this.worker = new Thread(this::runWorker, "MapSync-ChunkCapture");
		this.worker.setDaemon(true);
	}

	public void start() {
		this.worker.start();
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNewlyGenerated) -> {
			if (chunk == null) return;
			if (!this.queue.offer(new Task(level, chunk))) {
				this.dropped.incrementAndGet();
			}
		});
		logger.info("ChunkLoad capture armed (queue cap {})", QUEUE_CAPACITY);
	}

	public long capturedCount() {
		return this.captured.get();
	}

	public long droppedCount() {
		return this.dropped.get();
	}

	public long failedCount() {
		return this.failed.get();
	}

	public int queueSize() {
		return this.queue.size();
	}

	public int queueCapacity() {
		return QUEUE_CAPACITY;
	}

	/// Blocks the calling thread until the queue has drained below
	/// `targetSize`. Used by [WorldRegionScanner] to back-pressure its
	/// force-load loop — without this, the scanner outpaces the
	/// encode-and-store worker by 5–10× and ends up dropping tens of
	/// thousands of chunks on a real-world scan. Must NOT be invoked
	/// from the worker thread itself, which would deadlock.
	public void awaitQueueSpace(final int targetSize) throws InterruptedException {
		while (this.queue.size() > targetSize) {
			Thread.sleep(50L);
		}
	}

	@Override
	public void close() {
		this.worker.interrupt();
	}

	private void runWorker() {
		while (!Thread.currentThread().isInterrupted()) {
			final Task task;
			try {
				task = this.queue.take();
			}
			catch (final InterruptedException ignored) {
				Thread.currentThread().interrupt();
				return;
			}
			try {
				this.process(task);
			}
			catch (final Throwable t) {
				this.failed.incrementAndGet();
				logger.warn(
					"Failed to capture chunk ({},{}) in {}",
					task.chunk().getPos().x(),
					task.chunk().getPos().z(),
					task.level().dimension().identifier(),
					t
				);
			}
		}
	}

	private void process(final @NotNull Task task) throws Exception {
		final LevelChunk chunk = task.chunk();
		final ServerLevel level = task.level();
		final ServerChunkEncoder.Encoded encoded = ServerChunkEncoder.encode(chunk, level);
		final ChunkPos pos = chunk.getPos();
		this.state.database().storeChunkData(
			level.dimension().identifier().toString(),
			pos.x(),
			pos.z(),
			SERVER_UUID,
			System.currentTimeMillis(),
			1, // dataVersion — matches the constant Cartography uses
			encoded.hash(),
			encoded.columnBytes()
		);
		this.captured.incrementAndGet();
	}

	private record Task(@NotNull ServerLevel level, @NotNull LevelChunk chunk) {
	}
}
