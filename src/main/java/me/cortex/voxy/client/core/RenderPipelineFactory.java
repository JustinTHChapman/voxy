package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        if (IrisUtil.irisShaderPackEnabled()) {
            // Iris is active: use NormalRenderPipeline but fog is suppressed at render-time
            // (NormalRenderPipeline.finish() checks IrisUtil.irisShaderPackEnabled() each frame).
            // A dedicated IrisVoxyRenderPipeline that writes gbuffer data is a future enhancement.
            Logger.info("Iris shader pack detected — using normal pipeline with Iris fog suppression");
        }
        return new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
