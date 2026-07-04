package me.cortex.voxy.client.sync;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.C2SRequestSectionsPacket;
import me.cortex.voxy.common.network.S2CManifestPacket;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side handler for {@link S2CManifestPacket}.
 *
 * Accumulates (sectionPos, serverHash) pairs across potentially multiple packets.
 * On the final packet of the sequence, compares each position against the locally
 * stored version and sends a {@link C2SRequestSectionsPacket} for every section
 * that is missing or whose hash differs from the server's.
 *
 * Sections marked {@code userModified} are never requested — the client's copy wins.
 */
public final class ManifestSyncHandler {

    public static final ManifestSyncHandler INSTANCE = new ManifestSyncHandler();

    /** Maximum positions per C2S request packet (keeps packets under ~64 KB). */
    private static final int MAX_REQUEST_BATCH = 1_000;

    private final LongArrayList pendingPositions = new LongArrayList();
    private final List<Integer> pendingHashes     = new ArrayList<>();

    private ManifestSyncHandler() {}

    /** Called on the main client thread for each received manifest packet. */
    public void onManifestPacket(S2CManifestPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        // Drop manifests for a dimension other than the one we're currently in (e.g. an overworld
        // manifest that arrives just after a nether portal). Clear any partial accumulation so a
        // stale prefix can't merge into a later valid sequence.
        if (mc.level == null || !pkt.dimensionId().equals(mc.level.dimension().location().toString())) {
            pendingPositions.clear();
            pendingHashes.clear();
            return;
        }
        long[] positions = pkt.sectionPositions();
        int[]  hashes    = pkt.contentHashes();
        for (int i = 0; i < positions.length; i++) {
            pendingPositions.add(positions[i]);
            pendingHashes.add(hashes[i]);
        }
        if (pkt.isFinal()) {
            processAndRequest();
        }
    }

    /** Clear accumulated state (call on session end). */
    public void reset() {
        pendingPositions.clear();
        pendingHashes.clear();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void processAndRequest() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            pendingPositions.clear();
            pendingHashes.clear();
            return;
        }

        WorldEngine engine = WorldIdentifier.ofEngineNullable(mc.level);
        LongArrayList needed = new LongArrayList();

        for (int i = 0; i < pendingPositions.size(); i++) {
            long pos        = pendingPositions.getLong(i);
            int  serverHash = pendingHashes.get(i);

            if (engine != null) {
                WorldSection section = engine.acquireIfExists(pos);
                if (section != null) {
                    try {
                        if (section.userModified) continue;   // client's version wins
                        if (section.contentHash == serverHash) continue; // already up-to-date
                    } finally {
                        section.release();
                    }
                }
            }
            needed.add(pos);
        }

        pendingPositions.clear();
        pendingHashes.clear();

        if (needed.isEmpty()) {
            Logger.info("[VoxyManifest] All " + needed.size() + " sections already up-to-date.");
            return;
        }

        Logger.info("[VoxyManifest] Requesting " + needed.size() + " sections from server.");

        // Defensive: a manifest only ever comes from a voxy server, but re-check the channel before
        // sending in case the connection changed — an un-negotiated payload send throws.
        if (!me.cortex.voxy.client.network.ClientPacketHandlers.serverHasVoxy()) return;

        // Send in batches to avoid oversized packets
        for (int start = 0; start < needed.size(); start += MAX_REQUEST_BATCH) {
            int end   = Math.min(start + MAX_REQUEST_BATCH, needed.size());
            long[] batch = needed.toLongArray();
            long[] slice = new long[end - start];
            System.arraycopy(batch, start, slice, 0, slice.length);
            PacketDistributor.sendToServer(new C2SRequestSectionsPacket(slice));
        }
    }
}
