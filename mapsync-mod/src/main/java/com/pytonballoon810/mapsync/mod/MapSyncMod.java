package com.pytonballoon810.mapsync.mod;

import static com.pytonballoon810.mapsync.mod.sync.Cartography.chunkTileFromLevel;

import com.mojang.blaze3d.platform.InputConstants;
import com.pytonballoon810.mapsync.mod.config.ModConfig;
import com.pytonballoon810.mapsync.mod.config.gui.SyncConnectionsGui;
import com.pytonballoon810.mapsync.mod.data.CatchupChunk;
import com.pytonballoon810.mapsync.mod.data.ChunkTile;
import com.pytonballoon810.mapsync.mod.data.RegionPos;
import com.pytonballoon810.mapsync.mod.net.CloseContext;
import com.pytonballoon810.mapsync.mod.net.Packet;
import com.pytonballoon810.mapsync.mod.net.SyncClient;
import com.pytonballoon810.mapsync.mod.net.discovery.SyncAddressClientHandler;
import com.pytonballoon810.mapsync.mod.net.packet.ServerboundDimensionChangePacket;
import com.pytonballoon810.mapsync.mod.utils.MapSyncLogCapture;
import com.pytonballoon810.mapsync.mod.sync.DimensionState;
import com.pytonballoon810.mapsync.mod.sync.GameContext;
import com.pytonballoon810.mapsync.mod.net.UnexpectedPacketException;
import com.pytonballoon810.mapsync.mod.net.auth.AuthProcess;
import com.pytonballoon810.mapsync.mod.net.packet.ChunkTilePacket;
import com.pytonballoon810.mapsync.mod.net.packet.ClientboundChunkTimestampsResponsePacket;
import com.pytonballoon810.mapsync.mod.net.packet.ClientboundIdentityRequestPacket;
import com.pytonballoon810.mapsync.mod.net.packet.ClientboundRegionTimestampsPacket;
import com.pytonballoon810.mapsync.mod.net.packet.ClientboundWelcomePacket;
import com.pytonballoon810.mapsync.mod.net.packet.ServerboundCatchupRequestPacket;
import com.pytonballoon810.mapsync.mod.net.packet.ServerboundChunkTimestampsRequestPacket;
import com.pytonballoon810.mapsync.mod.sync.RenderQueue;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

public final class MapSyncMod {
	public static final Logger logger = LogManager.getLogger(MapSyncMod.class);

	private static final KeyMapping.Category KEY_MAPPING_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mapsync", "general"));
	private static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
		"key.map-sync.openGui",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_COMMA,
		KEY_MAPPING_CATEGORY
	);

	public static ModConfig modConfig;

	@ApiStatus.Internal
	public static void bootstrap() {
		MapSyncLogCapture.install();
		KeyMappingHelper.registerKeyMapping(OPEN_GUI_KEY);
		SyncAddressClientHandler.register();

		modConfig = ModConfig.load();
		modConfig.save(); // creates the default file if it doesn't exist yet

		ClientTickEvents.START_CLIENT_TICK.register((minecraft) -> {
			final GameContext gameContext = GameContext.get().orElse(null);
			if (gameContext == null) { // This *shouldn't* ever happen, but just case
				return;
			}
			while (OPEN_GUI_KEY.consumeClick()) {
				minecraft.setScreen(new SyncConnectionsGui(minecraft.screen, gameContext));
			}
			gameContext.getDimensionState().ifPresent(DimensionState::onTick);
		});
		GameContext.initEvents();
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			final GameContext gameContext = GameContext.get().orElse(null);
			if (gameContext == null) {
				return;
			}
			// TODO batch this up and send multiple chunks at once
			// TODO disable in nether (no meaningful "surface layer")
			final DimensionState dimensionState = gameContext.getDimensionState().orElse(null);
			if (dimensionState == null) {
				return;
			}
			final ChunkPos chunkPos = chunk.getPos();
			debugLog("received mc chunk: %d,%d".formatted(
				chunkPos.x(),
				chunkPos.z()
			));
			final ChunkTile chunkTile = chunkTileFromLevel(level, chunk);
			// TODO handle journeymap skipping chunks due to rate limiting - probably need mixin on render function
			if (RenderQueue.areAllMapModsMapping()) {
				dimensionState.setChunkTimestamp(chunkTile.chunkPos(), chunkTile.timestamp());
			}
			for (final SyncClient client : gameContext.getSyncConnections()) {
				client.sendChunkTile(chunkTile);
			}
		});
	}

	public static void handleSyncConnection(
		final @NotNull SyncClient client
	) throws Exception {
		client.authState.set(null);
		AuthProcess.sendHandshake(client);
	}

	public static void handleSyncDisconnection(
		final @NotNull SyncClient client,
		final @NotNull CloseContext context
	) {
		client.authState.set(null);
	}

	/// BEWARE: This is called from whatever thread the given SyncClient websocket is using for reads.
	public static void handleSyncPacket(
		final @NotNull SyncClient client,
		final @NotNull Packet received
	) throws Exception {
		switch (received) {
			case ChunkTilePacket(ChunkTile chunkTile) -> handleSharedChunk(client, chunkTile);
			case ClientboundIdentityRequestPacket packet -> AuthProcess.handleIdentityRequest(client, packet);
			case ClientboundWelcomePacket packet -> AuthProcess.handleWelcome(client, packet);
			case ClientboundRegionTimestampsPacket packet -> handleRegionTimestamps(client, packet);
			case ClientboundChunkTimestampsResponsePacket packet -> handleCatchupData(client, packet);
			default -> throw new UnexpectedPacketException(received);
		}
	}

	public static void handleWelcomed(
		final @NotNull SyncClient client
	) {
		if (client.gameContext.getDimensionState().orElse(null) instanceof final DimensionState dimensionState) {
			client.send(new ServerboundDimensionChangePacket(
				dimensionState.dimension.identifier()
			));
		}
	}

	public static void handleGameConnection(
		final @NotNull Minecraft minecraft,
		final @NotNull GameContext gameContext
	) {
		if (gameContext.getGameConfig().shouldAutoConnect()) {
			gameContext.getSyncConnections().setAll(Set.copyOf(
				gameContext.getGameConfig().getSyncServerAddresses()
			));
		}
	}

	/// @param level This is the *new* dimension.
	public static void handleDimensionChange(
		final @NotNull Minecraft minecraft,
		final @NotNull ClientLevel level,
		final @NotNull GameContext gameContext
	) {
		gameContext.getSyncConnections().broadcast(new ServerboundDimensionChangePacket(
			level.dimension().identifier()
		));
	}

	/**
	 * part of a chunk changed, and the chunk is likely to change again soon,
	 * so a ChunkTile update is queued, instead of updating instantly.
	 */
	public static void handleMcChunkPartialChange(int cx, int cz) {
		// TODO update ChunkTile in a second or so; remember dimension in case it changes til then
	}

	public static void handleRegionTimestamps(SyncClient client, ClientboundRegionTimestampsPacket packet) {
		client.authState.requireWelcomed();
		DimensionState dimension = client.gameContext.getDimensionState().orElse(null);
		if (dimension == null) return;
		if (!dimension.dimension.identifier().toString().equals(packet.dimension())) {
			return;
		}

		var regionTs = packet.timestamp();

		var regionPos = new RegionPos(regionTs.x(), regionTs.z());
		long oldestChunkTs = dimension.getOldestChunkTsInRegion(regionPos);
		boolean requiresUpdate = regionTs.timestamp() > oldestChunkTs;

		debugLog("region " + regionPos
				+ (requiresUpdate ? " requires update." : " is up to date.")
				+ " oldest client chunk ts: " + oldestChunkTs
				+ ", newest server chunk ts: " + regionTs.timestamp());

		if (requiresUpdate) {
			client.send(new ServerboundChunkTimestampsRequestPacket(packet.dimension(), regionPos));
		}
	}

	public static void handleSharedChunk(SyncClient client, ChunkTile chunkTile) {
		client.authState.requireWelcomed();
		debugLog("received shared chunk: " + chunkTile.chunkPos());
		for (SyncClient syncClient : client.gameContext.getSyncConnections()) {
			syncClient.setServerKnownChunkHash(chunkTile.chunkPos(), chunkTile.dataHash());
		}

		client.gameContext.getDimensionState().ifPresent((dimensionState) -> dimensionState.processSharedChunk(chunkTile));
	}

	public static void handleCatchupData(SyncClient client, ClientboundChunkTimestampsResponsePacket packet) {
		client.authState.requireWelcomed();
		for (CatchupChunk chunk : packet.chunks()) {
			chunk.syncClient = client;
		}
		client.gameContext.getDimensionState().ifPresent((dimensionState) -> {
			debugLog("received catchup: " + packet.chunks().size() + " " + client.syncAddress);
			dimensionState.addCatchupChunks(packet.chunks());
		});
	}

	public static void requestCatchupData(
		final @NotNull DimensionState dimensionState,
		final List<@NotNull CatchupChunk> chunks
	) {
		if (chunks == null || chunks.isEmpty()) {
			debugLog("not requesting more catchup: null/empty");
			return;
		}
		debugLog("requesting %d more catchup chunks".formatted(
			chunks.size()
		));
		final var catchupChunksBySyncServer = new IdentityHashMap<SyncClient, List<CatchupChunk>>();
		for (final CatchupChunk chunk : chunks) {
			final SyncClient source = chunk.syncClient;
			// Chunk's originating sync client may have closed since the
			// timestamps response arrived — happens whenever a player
			// reconnects mid-catchup or autoConnect retries from a stale
			// address. Skip; a fresh connection re-discovers these chunks
			// through the normal region-timestamps round.
			if (source == null || source.state() != SyncClient.ConnectionState.WELCOMED) {
				continue;
			}
			catchupChunksBySyncServer
				.computeIfAbsent(source, (key) -> new ArrayList<>())
				.add(chunk);
		}
		if (catchupChunksBySyncServer.isEmpty()) {
			return;
		}
		for (final var byServerEntry : catchupChunksBySyncServer.entrySet()) {
			final SyncClient syncConnection = byServerEntry.getKey();
			final Map<RegionPos, Object2LongMap<ChunkPos>> regionChunkRequests = new HashMap<>();
			for (final CatchupChunk catchupChunk : byServerEntry.getValue()) {
				regionChunkRequests
					.computeIfAbsent(RegionPos.forChunkPos(catchupChunk.chunkPos()), (regionPos) -> new Object2LongArrayMap<>())
					.mergeLong(catchupChunk.chunkPos(), catchupChunk.timestamp(), Math::max);
			}
			for (final var byRegionEntry : regionChunkRequests.entrySet()) {
				final RegionPos regionPos = byRegionEntry.getKey();
				syncConnection.send(new ServerboundCatchupRequestPacket(
					dimensionState.dimension.identifier(),
					(short) regionPos.x(),
					(short) regionPos.z(),
					byRegionEntry.getValue()
				));
			}
		}
	}

	public static void debugLog(String msg) {
		// we could also make use of slf4j's debug() but I don't know how to reconfigure that at runtime based on globalConfig
		if (modConfig.isShowDebugLog()) {
			logger.info(msg);
		}
	}
}
