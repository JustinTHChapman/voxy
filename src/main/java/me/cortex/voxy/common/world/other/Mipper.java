package me.cortex.voxy.common.world.other;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        //TODO: do a stable sort on all the entires, w.r.t the opacity and maybe light as a secondary???
        // then select the highest value
        // UPDATE, dumbass, the highest value _is_ the max/min



        int max = -1;

        // Prefer upper Y layer (idx1=1, i.e. bit1 set in the index) over lower Y layer.
        // This ensures fluid/translucent surface blocks (e.g. water) are preserved in the
        // mipped LOD even though their opacity is lower than solid blocks beneath them.
        // Within the same Y layer, pick by highest opacity.
        // Bit encoding: index = (idx0) | (idx1<<1) | (idx2<<2), so Y = bit1.
        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111)<<4)|0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110)<<4)|0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011)<<4)|0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010)<<4)|0b010, max);
        }

        // Only fall back to lower Y layer if upper Y layer is entirely air
        if (max == -1) {
            if (!Mapper.isAir(I101)) {
                max = (mapper.getBlockStateOpacity(I101)<<4)|0b101;
            }
            if (!Mapper.isAir(I100)) {
                max = Math.max((mapper.getBlockStateOpacity(I100)<<4)|0b100, max);
            }
            if (!Mapper.isAir(I001)) {
                max = Math.max((mapper.getBlockStateOpacity(I001)<<4)|0b001, max);
            }
            if (!Mapper.isAir(I000)) {
                max = Math.max((mapper.getBlockStateOpacity(I000)<<4), max);
            }
        }

        if (max != -1) {
            return switch (max&0b111) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + (max&0b111));
            };
        } else {
            int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                    (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
            int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                    (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
            blockLight = (blockLight / 8) & 0xF0;
            skyLight = (int) Math.ceil((double) skyLight / 8);

            return withLight(I111, blockLight | skyLight);
        }
    }
}
