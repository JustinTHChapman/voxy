package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.common.lod.WorldManifest;
import me.cortex.voxy.network.payload.LodSectionDataPayload;
import me.cortex.voxy.network.payload.RequestSectionsPayload;
import me.cortex.voxy.network.payload.SectionRemovePayload;
import me.cortex.voxy.network.payload.WorldManifestPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link net.minecraft.network.codec.StreamCodec} for each Voxy network payload
 * round-trips losslessly through a {@link FriendlyByteBuf}.
 *
 * <p>These tests run in the moddev "unit test" environment, which loads the Minecraft
 * classes needed for {@link ResourceLocation} and {@link FriendlyByteBuf}.
 */
class NetworkPayloadCodecTest {

    private static final ResourceLocation DIM = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void requestSectionsRoundTrip() {
        List<Long> keys = new ArrayList<>();
        for (int i = -10; i <= 10; i++) keys.add(SectionKey.encode(0, i, i));
        RequestSectionsPayload original = new RequestSectionsPayload(DIM, keys);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RequestSectionsPayload.CODEC.encode(buf, original);
        RequestSectionsPayload decoded = RequestSectionsPayload.CODEC.decode(buf);

        assertEquals(DIM, decoded.dimension());
        assertEquals(keys, decoded.sectionKeys());
    }

    @Test
    void sectionRemoveRoundTrip() {
        long key = SectionKey.encode(3, -50, 50);
        SectionRemovePayload original = new SectionRemovePayload(DIM, key);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SectionRemovePayload.CODEC.encode(buf, original);
        SectionRemovePayload decoded = SectionRemovePayload.CODEC.decode(buf);

        assertEquals(DIM, decoded.dimension());
        assertEquals(key, decoded.sectionKey());
    }

    @Test
    void lodSectionDataRoundTrip() {
        int cells = 16 * 16;
        int[] bs = new int[cells];
        short[] ht = new short[cells];
        byte[] lt = new byte[cells];
        int[] bi = new int[cells];
        for (int i = 0; i < cells; i++) { bs[i] = i; ht[i] = (short) i; lt[i] = (byte) i; bi[i] = i; }
        LodSection section = new LodSection(SectionKey.encode(0, 7, 7), bs, ht, lt, bi);

        LodSectionDataPayload original = new LodSectionDataPayload(
                DIM, section.key, section.hash, section.toBytes());

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LodSectionDataPayload.CODEC.encode(buf, original);
        LodSectionDataPayload decoded = LodSectionDataPayload.CODEC.decode(buf);

        assertEquals(DIM, decoded.dimension());
        assertEquals(section.key, decoded.sectionKey());
        assertEquals(section.hash, decoded.hash());
        assertArrayEquals(section.toBytes(), decoded.data());

        // and the bytes round-trip back into a LodSection
        LodSection restored = LodSection.fromBytes(decoded.sectionKey(), decoded.data());
        assertEquals(section, restored);
    }

    @Test
    void worldManifestRoundTripWithGzip() {
        Map<Long, Long> map = new HashMap<>();
        for (int x = -25; x < 25; x++) {
            for (int z = -25; z < 25; z++) {
                map.put(SectionKey.encode(0, x, z), (long) (x * 31 + z));
            }
        }
        WorldManifestPayload original = new WorldManifestPayload(DIM, new WorldManifest(map));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WorldManifestPayload.CODEC.encode(buf, original);
        WorldManifestPayload decoded = WorldManifestPayload.CODEC.decode(buf);

        assertEquals(DIM, decoded.dimension());
        assertEquals(map.size(), decoded.manifest().size());
        assertEquals(map, decoded.manifest().asMap());
    }

    @Test
    void emptyWorldManifestRoundTrip() {
        WorldManifestPayload original = new WorldManifestPayload(DIM, new WorldManifest(Map.of()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WorldManifestPayload.CODEC.encode(buf, original);
        WorldManifestPayload decoded = WorldManifestPayload.CODEC.decode(buf);
        assertEquals(0, decoded.manifest().size());
    }
}
