package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem2 {
    public static final boolean VERIFY_HASH_ON_LOAD  = VoxyCommon.isVerificationFlagOn("verifySectionHash");
    public static final boolean VERIFY_MEMORY_ACCESS = VoxyCommon.isVerificationFlagOn("verifyMemoryAccess");
    public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 32 * 32 * 32 * 8 * 2 + 8;

    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return Integer.expand(x,0b1001001001001)|Integer.expand(y,0b10010010010010)|Integer.expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = Integer.compress(i, 0b1001001001001);
        int y = Integer.compress(i, 0b10010010010010);
        int z = Integer.compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }


    private record SerialCache() {}
    private record DeserialCache() {}
    private static final ThreadLocal<SerialCache> SERIALIZE_CACHE = ThreadLocal.withInitial(()->new SerialCache());
    private static final ThreadLocal<DeserialCache> DESERIALIZE_CACHE = ThreadLocal.withInitial(()->new DeserialCache());


    //TODO: make it so that MemoryBuffer is cached and reused
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = SERIALIZE_CACHE.get();
        //Split into separate block, biome, blocklight, skylight
        // where block and biome are pelleted (0 block id (air) is implicitly in the pallet )
        // if all entries in a specific array are the same, just emit that single value
        // do bitpacking on the resulting arrays for pallets/when packing the palleted arrays
        // if doing bitpacking + pallet is larger than just emitting raw entries, do that

        //Header includes position (long), (maybe time?), version storage type/version, child existence, air block count?
        //
        return null;

    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        var cache = DESERIALIZE_CACHE.get();
        return false;
    }
}
