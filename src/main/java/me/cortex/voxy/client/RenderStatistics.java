package me.cortex.voxy.client;

import me.cortex.voxy.common.world.WorldEngine;

public class RenderStatistics {
    public static boolean enabled = false;

    public static final int[] hierarchicalTraversalCounts = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] hierarchicalRenderSections = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] visibleSections = new int[WorldEngine.MAX_LOD_LAYER+1];
    public static final int[] quadCount = new int[WorldEngine.MAX_LOD_LAYER+1];
}
