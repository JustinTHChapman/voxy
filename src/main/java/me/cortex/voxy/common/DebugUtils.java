package me.cortex.voxy.common;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.world.WorldEngine;

public class DebugUtils {
    public static void verifyAllTopLevelNodes(WorldEngine engine) {
        //TODO: run this async probably

        var worker = new Thread(()->{
            Logger.info("Verifying top level node masks, start");
            LongArrayFIFOQueue positions = new LongArrayFIFOQueue();
            engine.storage.iteratePositions(WorldEngine.MAX_LOD_LAYER, positions::enqueue);
            int count = positions.size();
            Logger.info("Verifying " + count + " top level nodes");
            while (!positions.isEmpty()) {
                long pos = positions.dequeueLong();
                verifyTopNodeChildren(engine, WorldEngine.getX(pos), WorldEngine.getY(pos), WorldEngine.getZ(pos));
                //if ((count - positions.size())/count)
            }
            Logger.info("Verification complete");
        });
        worker.setDaemon(true);
        worker.setName("Verification thread");
        worker.start();
    }


    public static void verifyTopNodeChildren(WorldEngine world, int X, int Y, int Z) {
        //TODO: can speed this up if needed by not getting the children and instead caching the previous getNonEmptyChildren result
        boolean loggedTLNPos = false;
        for (int lvl = 0; lvl < 5; lvl++) {
            for (int y = (Y<<4)>>lvl; y < ((Y+1)<<4)>>lvl; y++) {
                for (int x = (X<<4)>>lvl; x < ((X+1)<<4)>>lvl; x++) {
                    for (int z = (Z<<4)>>lvl; z < ((Z+1)<<4)>>lvl; z++) {
                        if (lvl == 0) {
                            var own = world.acquireIfExists(lvl, x, y, z);
                            if (own != null) {
                                if ((own.getNonEmptyChildren() != 0) ^ (own.getNonEmptyBlockCount() != 0)) {
                                    if (!loggedTLNPos) {
                                        Logger.error("Error verifying top level node: " + X + "," + Y + "," + Z);
                                        loggedTLNPos = true;
                                    }
                                    Logger.error("Lvl 0 node not marked correctly " + WorldEngine.pprintPos(own.key) + " expected: " + (own.getNonEmptyBlockCount() != 0) + " got " + (own.getNonEmptyChildren() != 0));
                                }
                                own.release();
                            }
                        } else {
                            byte msk = 0;
                            for (int child = 0; child < 8; child++) {
                                var section = world.acquireIfExists(lvl-1, (child&1)+(x<<1), ((child>>2)&1)+(y<<1), ((child>>1)&1)+(z<<1));
                                if (section != null) {
                                    msk |= (byte) (section.getNonEmptyChildren() != 0 ? (1 << child) : 0);
                                    section.release();
                                }
                            }
                            var own = world.acquireIfExists(lvl, x, y, z);
                            if (own != null) {
                                if (own.getNonEmptyChildren() != msk) {
                                    if (!loggedTLNPos) {
                                        Logger.error("Error verifying top level node: " + X + "," + Y + "," + Z);
                                        loggedTLNPos = true;
                                    }
                                    Logger.error("Section empty child mask not correct " + WorldEngine.pprintPos(own.key) + " got: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(own.getNonEmptyChildren()))).replace(' ', '0') + " expected: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(msk))).replace(' ', '0'));
                                }
                                own.release();
                            } else if (msk != 0) {
                                if (!loggedTLNPos) {
                                    Logger.error("Error verifying top level node: " + X + "," + Y + "," + Z);
                                    loggedTLNPos = true;
                                }
                                Logger.error("Section doesnt exist in db but has non empty children " + WorldEngine.pprintPos(WorldEngine.getWorldSectionId(lvl, x, y, z)) + " has children: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(msk))).replace(' ', '0'));
                            }
                        }
                    }
                }
            }
        }
    }
}
