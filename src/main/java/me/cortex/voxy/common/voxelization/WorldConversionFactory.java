package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.common.world.other.Mapper;
import net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette;
import net.minecraft.block.BlockState;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.*;

import java.util.WeakHashMap;

public class WorldConversionFactory {
    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    //TODO: create a mapping for world/mapper -> local mapping
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    private static void setupLocalPalette(Palette<BlockState> vp,Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        {
            if (vp instanceof ArrayPalette<BlockState>) {
                for (int i = 0; i < vp.getSize(); i++) {
                    var state = vp.get(i);
                    int blockId = -1;
                    if (state != null) {
                        blockId = blockCache.getOrDefault(state, -1);
                        if (blockId == -1) {
                            blockId = mapper.getIdForBlockState(state);
                            blockCache.put(state, blockId);
                        }
                    }
                    pc[i] = blockId;
                }
            } else if (vp instanceof LithiumHashPalette<BlockState>) {
                for (int i = 0; i < vp.getSize(); i++) {
                    BlockState state = null;
                    int blockId = -1;
                    try { state = vp.get(i); } catch (Exception e) {}
                    if (state != null) {
                        blockId = blockCache.getOrDefault(state, -1);
                        if (blockId == -1) {
                            blockId = mapper.getIdForBlockState(state);
                            blockCache.put(state, blockId);
                        }
                    }
                    pc[i] = blockId;
                }
            } else {
                if (vp instanceof BiMapPalette<BlockState> pal) {
                    //var map = pal.map;
                    //TODO: heavily optimize this by reading the map directly

                    for (int i = 0; i < vp.getSize(); i++) {
                        BlockState state = null;
                        int blockId = -1;
                        try { state = vp.get(i); } catch (Exception e) {}
                        if (state != null) {
                            blockId = blockCache.getOrDefault(state, -1);
                            if (blockId == -1) {
                                blockId = mapper.getIdForBlockState(state);
                                blockCache.put(state, blockId);
                            }
                        }
                        pc[i] = blockId;
                    }

                } else if (vp instanceof SingularPalette<BlockState>) {
                    int blockId = -1;
                    var state = vp.get(0);
                    if (state != null) {
                        blockId = blockCache.getOrDefault(state, -1);
                        if (blockId == -1) {
                            blockId = mapper.getIdForBlockState(state);
                            blockCache.put(state, blockId);
                        }
                    }
                    pc[0] = blockId;
                } else {
                    throw new IllegalStateException("Unknown palette type: " + vp);
                }
            }
        }
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           ReadableContainer<RegistryEntry<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier) {

        //Cheat by creating a local pallet then read the data directly


        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;

        var vp = blockContainer.data.palette;
        var pc = cache.getPaletteCache(vp.getSize());

        setupLocalPalette(vp, blockCache, stateMapper, pc);


        {
            int i = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        biomes[i++] = stateMapper.getIdForBiome(biomeContainer.get(x, y, z));
                    }
                }
            }
        }

        var bDat = blockContainer.data;
        var bStor = bDat.storage;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    //TODO: replace .get with a raw enumeration
                    int bId = pc[bStor.get(i++)];

                    byte light = lightSupplier.supply(x,y,z);
                    if (!(bId==0 && (light==0))) {
                        data[G(x, y, z)] = Mapper.composeMappingId(light, bId, biomes[((y&0b1100)<<2)|(z&0b1100)|(x>>2)]);
                    } else {
                        data[G(x, y, z)] = Mapper.AIR;
                    }
                }
            }
        }
        return section;
    }

    private static int G(int x, int y, int z) {
        return ((y<<8)|(z<<4)|x);
    }

    private static int H(int x, int y, int z) {
        return ((y<<6)|(z<<3)|x) + 16*16*16;
    }

    private static int I(int x, int y, int z) {
        return ((y<<4)|(z<<2)|x) + 8*8*8 + 16*16*16;
    }

    private static int J(int x, int y, int z) {
        return ((y<<2)|(z<<1)|x) + 4*4*4 + 8*8*8 + 16*16*16;
    }

    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        var data = section.section;

        //Mip L1
        int i = 0;
        for (int y = 0; y < 16; y+=2) {
            for (int z = 0; z < 16; z += 2) {
                for (int x = 0; x < 16; x += 2) {
                    data[16*16*16 + i++] =
                            Mipper.mip(
                                    data[G(x, y, z)],       data[G(x+1, y, z)],       data[G(x, y, z+1)],      data[G(x+1, y, z+1)],
                                    data[G(x, y+1, z)],   data[G(x+1, y+1, z)],  data[G(x, y+1, z+1)], data[G(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L2
        i = 0;
        for (int y = 0; y < 8; y+=2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[16*16*16 + 8*8*8 + i++] =
                            Mipper.mip(
                                    data[H(x, y, z)],       data[H(x+1, y, z)],       data[H(x, y, z+1)],      data[H(x+1, y, z+1)],
                                    data[H(x, y+1, z)],  data[H(x+1, y+1, z)],  data[H(x, y+1, z+1)], data[H(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L3
        i = 0;
        for (int y = 0; y < 4; y+=2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[16*16*16 + 8*8*8 + 4*4*4 + i++] =
                            Mipper.mip(
                                    data[I(x, y, z)],       data[I(x+1, y, z)],       data[I(x, y, z+1)],      data[I(x+1, y, z+1)],
                                    data[I(x, y+1, z)],   data[I(x+1, y+1, z)],  data[I(x, y+1, z+1)], data[I(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L4
        data[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2] =
                Mipper.mip(
                        data[J(0, 0, 0)], data[J(1, 0, 0)], data[J(0, 0, 1)], data[J(1, 0, 1)],
                        data[J(0, 1, 0)], data[J(1, 1, 0)], data[J(0, 1, 1)], data[J(1, 1, 1)],
                        mapper);




        //Do some very cheeky stuff for MiB
        if (false) {
            int sector = (section.x+512)>>10;
            section.setPosition(section.x-(sector<<10), section.y+16+(256-32-sector*30), section.z);//Note sector size mult is 30 because the top chunk is replicated (and so is bottom chunk)
        }
    }
}
