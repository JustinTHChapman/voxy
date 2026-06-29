package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.VineBlock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGenerateTextureMipmap;
import static org.lwjgl.opengl.ARBDirectStateAccess.glTextureSubImage2D;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glGetTexLevelParameteriv;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL45C.glGetTextureSubImage;
import static org.lwjgl.opengl.GL45C.glNamedBufferSubData;

/**
 * MC 1.21.1 port of ModelFactory.
 *
 * Bakes a per-block face texture atlas by sampling each block's BakedModel's first quad
 * sprite per direction. Atlas layout is unchanged from upstream:
 *   atlas size = (16*3*256) x (16*2*256), each modelId occupies a 3x2 cell of 16x16 face tiles.
 *   face index 0..5 = DOWN, UP, NORTH, SOUTH, WEST, EAST.
 *
 * Metadata long encoding (see ModelQueries):
 *   bits[0..47]    : 6 face bytes (bit0=occludes, bit2=canBeOccluded, bit3=usesSelfLighting)
 *                    byte == 0xFF means face does not exist
 *   bits[48..55]   : model flags (bit0=biomeColoured, bit5=cullsSame, bit6=fullyOpaque, ...)
 *   bits[56..63]   : lightEmission (low nibble)
 */
public class ModelFactory {
    public static final int MODEL_TEXTURE_SIZE = 16;
    public static final int LAYERS = Integer.numberOfTrailingZeros(MODEL_TEXTURE_SIZE);

    private static final Direction[] FACE_DIRS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    // Fallback metadata when baking fails: all faces exist+occlude, fully opaque, cullsSame.
    private static final long FALLBACK_OPAQUE_META = (0x60L << 48) | 0x010101010101L;

    public final SoftwareModelTextureBakery bakery2;
    private final Mapper mapper;
    private final ModelStore storage;

    // blockId -> modelId (1..65535). 0 reserved for air. -1 = not yet assigned.
    private final int[] blockIdToModelId = new int[1 << 20];
    // modelId -> packed metadata
    private final long[] metadataCache = new long[1 << 16];
    // modelId -> ARGB tint (0xFFFFFFFF = no tint)
    private final int[] tintCache = new int[1 << 16];

    // Biome colour LUT: modelColourBuffer layout is [biomeModelIndex * BIOME_STRIDE + voxyBiomeId] = ARGB.
    // BIOME_STRIDE=512 matches the 9-bit biome field; MAX_BIOME_MODELS * 512 * 4 bytes = 1MB colourBuffer.
    private static final int BIOME_STRIDE = 512;
    private static final int MAX_BIOME_MODELS = 512;
    // modelId -> index into the biome table (-1 = not biome-dependent)
    private final int[] modelBiomeIndex = new int[1 << 16];
    // modelId -> color type: 1=grass, 2=foliage, 3=water (fallback when no resolver)
    private final byte[] modelColorType = new byte[1 << 16];
    // biomeModelIndex -> the ColorResolver captured from BlockColors for this block type.
    // Null for water (handled via getWaterColor) and blocks that don't call getBlockTint.
    // When non-null, resolver.getColor(biome, 0, 0) is used instead of the vanilla
    // GrassColor/FoliageColor formula, so mod-replaced resolvers (Quark, Aether, etc.) work.
    private final ColorResolver[] biomeIndexResolver = new ColorResolver[MAX_BIOME_MODELS];
    private int nextBiomeModelIndex = 0;
    // voxyBiomeId -> MC Biome (populated on addBiome calls)
    private final ConcurrentHashMap<Integer, Biome> registeredBiomes = new ConcurrentHashMap<>();
    // Pending biome colour GPU uploads (queued from any thread, drained on render thread)
    private record BiomePendingUpdate(int biomeModelIndex, int voxyBiomeId, int argbColor) {}
    private final ConcurrentLinkedDeque<BiomePendingUpdate> pendingBiomeUploads = new ConcurrentLinkedDeque<>();
    // Biomes whose registry lookup failed (connection not yet ready); retried each frame.
    private record PendingBiome(String name, int id) {}
    private final ConcurrentLinkedDeque<PendingBiome> pendingBiomeLookups = new ConcurrentLinkedDeque<>();

    private int nextModelId = 1;
    private int bakedCount = 0;
    private boolean atlasDirty = false;

    private final ConcurrentLinkedDeque<Integer> bakeQueue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger inflight = new AtomicInteger();

    // Bubble-column → water aliasing: bubble columns must share water's model ID so the
    // mesh builder treats them as the same fluid and culls shared faces seamlessly.
    private volatile int waterBlockId = -1;
    private final ConcurrentLinkedDeque<Integer> pendingBubbleColumnBlockIds = new ConcurrentLinkedDeque<>();

    // Reusable 16x16 RGBA scratch buffer.
    private final ByteBuffer faceBuf = MemoryUtil.memAlloc(MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
    // Reusable BlockModel struct buffer (64 bytes = ModelStore.MODEL_SIZE).
    private final ByteBuffer modelBuf = MemoryUtil.memAlloc(ModelStore.MODEL_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);

    private RandomSource random;

    public ModelFactory(Mapper mapper, ModelStore storage) {
        this.mapper = mapper;
        this.storage = storage;
        this.bakery2 = new SoftwareModelTextureBakery();
        Arrays.fill(this.blockIdToModelId, -1);
        Arrays.fill(this.tintCache, 0xFFFFFFFF);
        Arrays.fill(this.modelBiomeIndex, -1);
        this.blockIdToModelId[0] = 0; // air -> modelId 0
    }

    public boolean addEntry(int blockId) {
        if (blockId < 0 || blockId >= this.blockIdToModelId.length) return false;
        if (this.blockIdToModelId[blockId] != -1) return false;
        if (this.nextModelId >= 65536) {
            this.blockIdToModelId[blockId] = 0;
            return false;
        }
        BlockState preState = this.mapper.getBlockStateFromBlockId(blockId);

        // Bubble columns must share water's exact model ID so the mesh builder treats
        // them as the same fluid and culls shared faces, making columns invisible.
        if (preState != null && preState.getBlock() == Blocks.BUBBLE_COLUMN) {
            if (this.waterBlockId >= 0) {
                // Water already registered — alias directly.
                this.blockIdToModelId[blockId] = this.blockIdToModelId[this.waterBlockId];
            } else {
                // Water not registered yet — park as 0 (air) and fix up when water arrives.
                this.blockIdToModelId[blockId] = 0;
                this.pendingBubbleColumnBlockIds.add(blockId);
            }
            return true;
        }

        // Track water's block ID so bubble columns can alias to it.
        boolean isWater = (preState != null && preState.getBlock() == Blocks.WATER);

        int modelId = this.nextModelId++;
        this.blockIdToModelId[blockId] = modelId;

        if (isWater) {
            this.waterBlockId = blockId;
            // Fix up any bubble columns that registered before water.
            Integer pendingBcId;
            while ((pendingBcId = this.pendingBubbleColumnBlockIds.poll()) != null) {
                this.blockIdToModelId[pendingBcId] = modelId;
            }
        }

        this.bakeQueue.add(blockId);
        this.inflight.incrementAndGet();
        this.bakedCount++;
        return true;
    }

    public void addBiome(Mapper.BiomeEntry biome) {
        if (biome == null || biome.biome == null) return;
        Biome mcBiome = lookupBiome(biome.biome);
        if (mcBiome == null) {
            // Connection not ready yet — retry on the render thread via processUploads().
            this.pendingBiomeLookups.add(new PendingBiome(biome.biome, biome.id));
            return;
        }
        registerBiome(biome.biome, biome.id, mcBiome);
    }

    private void registerBiome(String name, int voxyBiomeId, Biome mcBiome) {
        if (this.registeredBiomes.containsKey(voxyBiomeId)) return;
        this.registeredBiomes.put(voxyBiomeId, mcBiome);
        DIAG.info("addBiome: registered biome='{}' id={} totalRegistered={}", name, voxyBiomeId, this.registeredBiomes.size());
        // Queue GPU colour uploads for all already-baked biome-dependent models.
        for (int modelId = 1; modelId < this.nextModelId; modelId++) {
            int biomeIdx = this.modelBiomeIndex[modelId];
            if (biomeIdx < 0) continue;
            byte colorType = this.modelColorType[modelId];
            ColorResolver resolver = this.biomeIndexResolver[biomeIdx];
            int argb = 0xFF000000 | getColorForBiome(mcBiome, colorType, resolver);
            this.pendingBiomeUploads.add(new BiomePendingUpdate(biomeIdx, voxyBiomeId, argb));
        }
    }

    public boolean processAllThings() {
        return false; // no off-thread work
    }

    private static final org.slf4j.Logger DIAG = org.slf4j.LoggerFactory.getLogger("VoxyDiag");
    private int diagBakedThisCall = 0, diagTotalBaked = 0, diagFailed = 0;
    private long diagLastLog = 0;

    /** Render-thread: drains the bake queue and uploads textures to the GPU atlas. */
    public void processUploads() {
        // Retry biomes whose lookup failed earlier (connection was not ready at addBiome time).
        if (!this.pendingBiomeLookups.isEmpty()) {
            var retry = new java.util.ArrayList<PendingBiome>();
            PendingBiome pb;
            while ((pb = this.pendingBiomeLookups.poll()) != null) retry.add(pb);
            for (PendingBiome p : retry) {
                Biome mcBiome = lookupBiome(p.name());
                if (mcBiome != null) {
                    registerBiome(p.name(), p.id(), mcBiome);
                } else {
                    this.pendingBiomeLookups.add(p); // still not ready; try again next frame
                }
            }
        }

        // Drain pending biome colour uploads (queued from addBiome on any thread).
        BiomePendingUpdate biomeUpdate;
        while ((biomeUpdate = this.pendingBiomeUploads.poll()) != null) {
            glNamedBufferSubData(this.storage.modelColourBuffer.id,
                    (long)(biomeUpdate.biomeModelIndex() * BIOME_STRIDE + biomeUpdate.voxyBiomeId()) * 4,
                    new int[]{biomeUpdate.argbColor()});
        }

        Integer blockId;
        int budget = 64;
        boolean did = false;
        this.diagBakedThisCall = 0;
        while (budget-- > 0 && (blockId = this.bakeQueue.poll()) != null) {
            try {
                bakeBlock(blockId);
                this.diagBakedThisCall++;
                this.diagTotalBaked++;
            } catch (Throwable t) {
                this.diagFailed++;
                Logger.warn("Model bake failed for blockId=" + blockId + ": " + t);
                int modelId = this.blockIdToModelId[blockId];
                if (modelId > 0) this.metadataCache[modelId] = FALLBACK_OPAQUE_META;
            } finally {
                this.inflight.decrementAndGet();
                did = true;
            }
        }
        if (did) this.atlasDirty = true;
        if (this.bakeQueue.isEmpty() && this.atlasDirty) {
            try {
                glGenerateTextureMipmap(this.storage.textures.id);
                DIAG.info("[ModelFactory] glGenerateTextureMipmap done (texId={})", this.storage.textures.id);
            } catch (Throwable t) {
                DIAG.warn("[ModelFactory] glGenerateTextureMipmap failed: {}", t.toString());
            }
            this.atlasDirty = false;
        }
        long now = System.currentTimeMillis();
        if (now - this.diagLastLog > 2000) {
            this.diagLastLog = now;
            DIAG.info("ModelFactory: totalBaked={} failed={} queueSize={} nextModelId={} bakedCount={} biomeModels={} registeredBiomes={} sampleMeta(1)=0x{} sampleMeta(23)=0x{}",
                    this.diagTotalBaked, this.diagFailed, this.bakeQueue.size(), this.nextModelId, this.bakedCount,
                    this.nextBiomeModelIndex, this.registeredBiomes.size(),
                    Long.toHexString(this.metadataCache.length>1?this.metadataCache[1]:0L),
                    Long.toHexString(this.metadataCache.length>23?this.metadataCache[23]:0L));
        }
    }

    private void bakeBlock(int blockId) {
        int modelId = this.blockIdToModelId[blockId];
        if (modelId <= 0) return;

        BlockState state = this.mapper.getBlockStateFromBlockId(blockId);

        Minecraft mc = Minecraft.getInstance();
        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        BakedModel model = shaper.getBlockModel(state);
        if (this.random == null) this.random = RandomSource.create();

        long meta = 0L;
        int tint = 0xFFFFFFFF;
        boolean anyTinted = false;
        boolean tintIsBiomeDependent = false;
        ColorResolver capturedColorResolver = null; // set when a mod-replaced resolver is detected
        boolean canOcclude = state.canOcclude();
        int lightEmission = Math.min(15, state.getLightEmission());

        // Fluid detection needed before the face loop for sprite-selection guards.
        boolean isPureFluid = state.getBlock() instanceof LiquidBlock;

        // Pre-scan: does this model have ANY directional (face-culled) quads?
        // Cross-plant / sprite-only models (flowers, short grass, etc.) have none.
        // Those must not be rendered as solid cubes — all faces must be marked missing.
        boolean hasAnyDirectionalQuad = false;
        for (int fIdx = 0; fIdx < 6; fIdx++) {
            this.random.setSeed(42L);
            List<BakedQuad> fq = model.getQuads(state, FACE_DIRS[fIdx], this.random);
            if (fq != null && !fq.isEmpty()) { hasAnyDirectionalQuad = true; break; }
        }

        // Per-face tracking for modelBuffer upload.
        boolean[] facePresent   = new boolean[6];
        boolean[] faceAllOpaque = new boolean[6];
        boolean[] faceTinted    = new boolean[6];
        float[]   faceDepths    = new float[6];   // depth indentation per face (0 = no offset)
        String[]  faceSpriteName = new String[6]; // sprite resource name per face, for variation detection

        for (int faceIdx = 0; faceIdx < 6; faceIdx++) {
            Direction dir = FACE_DIRS[faceIdx];
            TextureAtlasSprite sprite = null;
            BakedQuad picked = null;

            this.random.setSeed(42L);
            List<BakedQuad> faceQuads = model.getQuads(state, dir, this.random);
            if (faceQuads != null && !faceQuads.isEmpty()) {
                picked = faceQuads.get(0);
            } else {
                this.random.setSeed(42L);
                List<BakedQuad> nullQuads = model.getQuads(state, null, this.random);
                if (nullQuads != null) {
                    for (BakedQuad q : nullQuads) {
                        if (q.getDirection() == dir) { picked = q; break; }
                    }
                    // Only fall back to the first null quad for non-cross-plant models.
                    // Cross-plant quads have N/S/E/W directions but no UP/DOWN, so those
                    // faces end up blank naturally — exactly what we want (sides show, top doesn't).
                    if (picked == null && !nullQuads.isEmpty() && (hasAnyDirectionalQuad || isPureFluid))
                        picked = nullQuads.get(0);
                }
            }

            if (picked != null) {
                sprite = picked.getSprite();
                // Extract face depth from vertex positions (for partial-height blocks like snow/slabs).
                // DefaultVertexFormat.BLOCK: 8 ints/vertex, position at offsets 0(x),1(y),2(z).
                try {
                    int[] verts = picked.getVertices();
                    if (verts.length == 32) {
                        int coordIdx = (faceIdx >> 1) == 0 ? 1 : ((faceIdx >> 1) == 1 ? 2 : 0); // Y, Z, X
                        float sum = 0;
                        for (int v = 0; v < 4; v++) sum += Float.intBitsToFloat(verts[v * 8 + coordIdx]);
                        float coord = sum / 4.0f;
                        // Positive-normal faces (UP/SOUTH/EAST, face&1==1): depth = 1-coord.
                        // Negative-normal faces (DOWN/NORTH/WEST, face&1==0): depth = coord.
                        faceDepths[faceIdx] = ((faceIdx & 1) == 1) ? (1.0f - coord) : coord;
                    }
                } catch (Exception ignored) {}
            }
            // For pure fluid blocks (water, lava), use the actual fluid still-texture from the
            // block atlas. BakedModel.getParticleIcon() for fluids often returns a grey/white
            // placeholder or the missingno texture (fully opaque), which makes LOD water look
            // like a solid opaque rectangle, hiding the seabed beneath it.
            if (sprite == null && isPureFluid) {
                try {
                    var ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
                            .of(state.getFluidState().getType());
                    sprite = mc.getModelManager()
                            .getAtlas(net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png"))
                            .getSprite(ext.getStillTexture());
                } catch (Throwable ignored) {}
            }
            // Fall back to the particle icon for any face still missing a sprite — INCLUDING
            // cross-plant / sprite-only models (flowers, grass, sugar cane, sprite torches). They
            // render as alpha-cutout sprite voxels (see the translucency override below) so they
            // appear at LOD instead of being dropped. Being non-full + transparent, they don't cull
            // neighbours. (Directional blocks already reached this fallback; this only adds the
            // sprite-only ones, so normal blocks are unaffected.)
            if (sprite == null) {
                try { sprite = model.getParticleIcon(); } catch (Throwable ignored) {}
            }

            int faceByte;
            if (sprite == null) {
                // no geometry on this face; mark face missing
                writeBlankFace(modelId, faceIdx);
                faceByte = 0xFF;
                facePresent[faceIdx] = false;
            } else {
                boolean opaque = uploadFaceTexture(modelId, faceIdx, sprite, picked);
                // bit0 = occludes neighbor face; bit2 = can be occluded by neighbor.
                faceByte = (opaque && canOcclude) ? 0b00000101 : 0b00000100;
                facePresent[faceIdx] = true;
                faceAllOpaque[faceIdx] = opaque;
                faceSpriteName[faceIdx] = sprite.contents().name().toString();
                if (state.is(BlockTags.LEAVES) && this.diagTotalBaked < 200) {
                    String[] faceNames = {"DOWN","UP","NORTH","SOUTH","WEST","EAST"};
                    DIAG.info("[LEAF-FACE] block={} face={} sprite={} opaque={}",
                            state.getBlock().getDescriptionId(), faceNames[faceIdx],
                            sprite.contents().name(), opaque);
                }
            }
            meta |= ((long)(faceByte & 0xFF)) << (faceIdx * 8);

            if (picked != null && picked.isTinted()) {
                faceTinted[faceIdx] = true;
                if (!anyTinted) {
                    anyTinted = true;
                    try {
                        // Use a sentinel BlockAndTintGetter: if getBlockTint() is called,
                        // the block's color is biome-dependent (grass, foliage, etc.).
                        // We also capture the ColorResolver so we can call it directly with
                        // any registered biome — this makes mod-replaced resolvers (Quark
                        // GreenerGrass, Aether, etc.) work automatically instead of falling
                        // back to the vanilla GrassColor/FoliageColor formulas.
                        boolean[] biomeDep = {false};
                        ColorResolver[] resolverCapture = {null};
                        final BlockState capturedState = state;
                        int rgb = mc.getBlockColors().getColor(state, new BlockAndTintGetter() {
                            @Override public float getShade(Direction dir, boolean shade) { return 1.0f; }
                            @Override public LevelLightEngine getLightEngine() { return null; }
                            @Override public int getBlockTint(BlockPos p, ColorResolver resolver) {
                                biomeDep[0] = true;
                                if (resolverCapture[0] == null) resolverCapture[0] = resolver;
                                return 0;
                            }
                            @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
                            @Override public BlockState getBlockState(BlockPos p) { return capturedState; }
                            @Override public FluidState getFluidState(BlockPos p) { return capturedState.getFluidState(); }
                            @Override public int getHeight() { return 384; }
                            @Override public int getMinBuildHeight() { return -64; }
                        }, BlockPos.ZERO, picked.getTintIndex());
                        if (biomeDep[0]) {
                            // getBlockTint was invoked → color varies by biome
                            tintIsBiomeDependent = true;
                            tint = 0xFFFFFFFF; // overridden by biome LUT
                            capturedColorResolver = resolverCapture[0];
                            if (this.nextBiomeModelIndex < 10) {
                                DIAG.info("bakeBlock: BIOME-DEP detected block={} modelId={} registeredBiomes={} resolver={}",
                                        state.getBlock().getDescriptionId(), modelId, this.registeredBiomes.size(),
                                        capturedColorResolver != null ? capturedColorResolver.getClass().getSimpleName() : "null");
                            }
                        } else if (rgb != -1) {
                            tint = 0xFF000000 | (rgb & 0xFFFFFF);
                        }
                    } catch (Throwable t) {
                        tint = 0xFFFFFFFF;
                    }
                }
            }
        }

        // Water tinting: LiquidBlock quads are empty; texture comes from particle icon (grey/white).
        // Use biome-dependent water colour; fall back to MC default blue if LUT is full.
        if (state.getBlock() == Blocks.WATER) {
            tintIsBiomeDependent = true;
            tint = 0xFF000000 | 0x3F76E4; // fallback if biome LUT is exhausted
            anyTinted = true;
            for (int i = 0; i < 6; i++) {
                if (facePresent[i]) faceTinted[i] = true;
            }
        }

        // Per-face occlusion from the block's actual occlusion shape: a face may only cull its
        // neighbour if the block geometrically fills that whole voxel face. The old height-only
        // heuristic (UP/DOWN depth) caught slabs/snow but MISSED full-height-but-thin blocks —
        // fences, bamboo, walls, panes — so their faces wrongly culled neighbours (e.g. the face
        // under a fence post, or the sides of a slab seen at LOD). isFaceFull() resolves it per
        // direction: a bottom slab still occludes DOWN, a fence post occludes nothing.
        boolean isFullCube = false;
        if (canOcclude) {
            try {
                VoxelShape occ = state.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                isFullCube = Block.isShapeFullBlock(occ);
                if (!isFullCube) {
                    for (int fi = 0; fi < 6; fi++) {
                        // Clear bit0 (occludes neighbour) for any face the shape doesn't fully cover.
                        if (!Block.isFaceFull(occ, FACE_DIRS[fi])) {
                            meta &= ~(1L << (fi * 8));
                        }
                    }
                }
            } catch (Throwable t) {
                // Fall back to the height heuristic if the occlusion shape can't be queried.
                float blockTopY    = facePresent[1] ? (1.0f - faceDepths[1]) : 1.0f;
                float blockBottomY = facePresent[0] ? faceDepths[0]          : 0.0f;
                if (blockTopY < 0.999f || blockBottomY > 0.001f) {
                    for (int fi = 0; fi < 6; fi++) meta &= ~(1L << (fi * 8));
                }
            }
        }

        int modelFlags = 0;
        if (canOcclude && isFullCube) modelFlags |= 0b01100000; // fullyOpaque + cullsSame
        else if (canOcclude)          modelFlags |= 0b00100000; // cullsSame only
        if (anyTinted)  modelFlags |= 0b00000001; // biomeColoured (we don't have a LUT; just const tint)

        // Fluid detection: pure fluid block (water, lava) vs waterlogged / water-immersed block.
        // Use fluid state rather than the WATERLOGGED property so kelp, seagrass, and other
        // water-immersed plants (which lack WATERLOGGED but always have a WATER fluid state)
        // also get the containsFluid flag, causing adjacent water faces to cull correctly.
        boolean isWaterlogged = (!isPureFluid) && state.getFluidState().is(FluidTags.WATER);
        if (isPureFluid)    modelFlags |= 0b00010000; // isFluid
        if (isWaterlogged)  modelFlags |= 0b00001000; // containsFluid

        // Translucency: water, ice, stained glass, etc. — any present face that is not fully opaque.
        // Leaves are excluded: their per-face hasAlphaCutout bit handles transparency via the
        // opaque-pass cutout discard (textureGrad mip-averaged alpha ~0.5 > 0.1 threshold),
        // so they must NOT go to the alpha-blend translucent pass (which would make them semi-transparent).
        boolean anyFaceTransparent = false;
        for (int i = 0; i < 6; i++) {
            if (facePresent[i] && !faceAllOpaque[i]) { anyFaceTransparent = true; break; }
        }
        if (state.is(BlockTags.LEAVES)) anyFaceTransparent = false;
        // Sprite-only / cross-plant models (flowers, grass, sugar cane, sprite torches) render via the
        // particle-icon fallback above; route them through the opaque-pass alpha-CUTOUT discard like
        // leaves rather than the alpha-blend translucent pass, so they read crisply instead of ghostly.
        if (!hasAnyDirectionalQuad) anyFaceTransparent = false;
        // Ice (ice / packed / blue / frosted) is semi-transparent but visually near-opaque at LOD
        // scale. Routing it to the alpha-blend translucent pass made it overlap the water beneath it
        // in frozen oceans — two unsorted translucent surfaces produce order-dependent blending that
        // flickers/stripes as the camera moves. Render ice opaque instead (stable, looks fine at LOD).
        if (state.is(BlockTags.ICE)) anyFaceTransparent = false;
        if (anyFaceTransparent) modelFlags |= 0b00000010; // isTranslucent

        // Detect if this block uses different sprites for different face directions.
        // Blocks like "Better Leaves" models use a dense top texture on UP and a
        // sparse individual-leaf texture on the sides.  At LOD distance the side
        // texture's average alpha is what matters, not the per-pixel detail, so we
        // flag these blocks so the shader uses the mip-averaged alpha for discard.
        boolean spritesVaryPerFace = false;
        String firstPresentSprite = null;
        for (int i = 0; i < 6; i++) {
            if (!facePresent[i] || faceSpriteName[i] == null) continue;
            if (firstPresentSprite == null) firstPresentSprite = faceSpriteName[i];
            else if (!faceSpriteName[i].equals(firstPresentSprite)) { spritesVaryPerFace = true; break; }
        }

        if ((state.is(BlockTags.LEAVES) || state.getBlock() == Blocks.BIRCH_LOG) && this.diagTotalBaked < 200) {
            String[] faceNames = {"DOWN","UP","NORTH","SOUTH","WEST","EAST"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(faceNames[i]).append("=present:").append(facePresent[i])
                  .append("/opaque:").append(faceAllOpaque[i])
                  .append("/tinted:").append(faceTinted[i])
                  .append("/sprite:").append(faceSpriteName[i]).append("  ");
            }
            DIAG.info("[BLOCKDIAG] block={} modelId={} anyFaceTransparent={} spritesVary={} anyTinted={} biomeDep={} tint=0x{} translucent={} faces: {}",
                    state.getBlock().getDescriptionId(), modelId, anyFaceTransparent, spritesVaryPerFace,
                    anyTinted, tintIsBiomeDependent, Integer.toHexString(tint),
                    (modelFlags & 0b10) != 0, sb);
        }

        meta |= ((long)(modelFlags & 0xFF)) << 48;
        meta |= ((long)(lightEmission & 0xF)) << 55; // lightEmission nibble at bits[55..58]

        this.metadataCache[modelId] = meta;

        // Resolve colour tint: allocate biome LUT slot for biome-dependent blocks.
        int resolvedColourTint;
        int biomeLUTFlag = 0;
        if (tintIsBiomeDependent && this.nextBiomeModelIndex < MAX_BIOME_MODELS) {
            byte colorType = detectColorType(state);
            int biomeIdx = this.nextBiomeModelIndex++;
            this.modelBiomeIndex[modelId] = biomeIdx;
            this.modelColorType[modelId] = colorType;
            this.biomeIndexResolver[biomeIdx] = capturedColorResolver; // may be null for water
            // Pre-fill all 512 slots with the default plains colour.
            int defaultColor = 0xFF000000 | switch (colorType) {
                case 1 -> GrassColor.get(0.8, 0.4);
                case 2 -> FoliageColor.get(0.8, 0.4);
                case 3 -> 0x3F76E4;
                default -> 0xFFFFFF;
            };
            int[] defaultSlot = new int[BIOME_STRIDE];
            Arrays.fill(defaultSlot, defaultColor);
            glNamedBufferSubData(this.storage.modelColourBuffer.id,
                    (long) biomeIdx * BIOME_STRIDE * 4, defaultSlot);
            // Overwrite slots for already-registered biomes using the captured resolver.
            final ColorResolver resolverForFill = capturedColorResolver;
            for (Map.Entry<Integer, Biome> e : this.registeredBiomes.entrySet()) {
                glNamedBufferSubData(this.storage.modelColourBuffer.id,
                        (long)(biomeIdx * BIOME_STRIDE + e.getKey()) * 4,
                        new int[]{0xFF000000 | getColorForBiome(e.getValue(), colorType, resolverForFill)});
            }
            resolvedColourTint = biomeIdx * BIOME_STRIDE;
            biomeLUTFlag = 2; // modelHasBiomeLUT
        } else if (anyTinted) {
            resolvedColourTint = tint;
        } else {
            resolvedColourTint = 0xFFFFFFFF;
        }
        this.tintCache[modelId] = resolvedColourTint;

        // Upload BlockModel struct to modelBuffer (SSBO binding 3).
        uploadBlockModelStruct(modelId, facePresent, faceAllOpaque, faceTinted, faceDepths, resolvedColourTint, biomeLUTFlag, spritesVaryPerFace);
    }

    /**
     * Writes a 64-byte BlockModel struct for this modelId into modelBuffer at offset modelId*64.
     *
     * BlockModel layout (matches block_model.glsl):
     *   uint faceData[6]  @ offset 0  (6×4 = 24 bytes)
     *   uint flagsA       @ offset 24
     *   uint colourTint   @ offset 28
     *   uint customId     @ offset 32
     *   uint _pad[7]      @ offset 36  (28 bytes)
     *
     * faceData encoding:
     *   bits[0..3]   = start_x (0-15)
     *   bits[4..7]   = end_x   (0-15)
     *   bits[8..11]  = start_z (0-15)
     *   bits[12..15] = end_z   (0-15)
     *   bits[16..21] = depth indentation (0 = flat)
     *   bit22        = hasAlphaCutout
     *   bit23        = hasAlphaCutoutOverride / useAveragedMipDiscard
     *   bits[24..25] = tintState (0=none, 1=partial, 2=always)
     */
    private void uploadBlockModelStruct(int modelId,
            boolean[] facePresent, boolean[] faceAllOpaque, boolean[] faceTinted, float[] faceDepths,
            int colourTint, int extraFlagsA, boolean useAveragedMipDiscard) {
        this.modelBuf.clear();

        // Detect partial-height block from UP (faceIdx=1) and DOWN (faceIdx=0) face depths.
        // blockTopY   = Y of the top surface in [0,1]; blockBottomY = Y of the bottom.
        float blockTopY    = facePresent[1] ? (1.0f - faceDepths[1]) : 1.0f;
        float blockBottomY = facePresent[0] ? faceDepths[0]          : 0.0f;
        boolean isPartialHeight = (blockTopY < 0.999f || blockBottomY > 0.001f);

        // faceData[6] — 24 bytes
        for (int i = 0; i < 6; i++) {
            if (facePresent[i]) {
                // Full-face UV bounds: start=0, end=15 for both axes.
                int faceData = 0x0000F0F0;

                // Side-face height clipping for partial-height blocks (snow layers, slabs, etc.).
                // UP/DOWN faces (axis 0) use depth indentation; side faces encode the actual height.
                // NORTH/SOUTH (axis 1): world-Y controlled by start_z (bits 8-11) / end_z (bits 12-15).
                // EAST/WEST   (axis 2): world-Y controlled by start_x (bits 0-3)  / end_x (bits 4-7).
                if (isPartialHeight && (i >> 1) != 0) {
                    int startH = Math.max(0, Math.round(blockBottomY * 16));
                    int spanH  = Math.max(1, Math.round((blockTopY - blockBottomY) * 16));
                    int endH   = Math.min(15, startH + spanH - 1);
                    if ((i >> 1) == 1) { // NORTH/SOUTH
                        faceData = (faceData & 0xFFFF00FF) | (startH << 8) | (endH << 12);
                    } else {             // EAST/WEST
                        faceData = (faceData & 0xFFFFFF00) | startH | (endH << 4);
                    }
                }

                if (!faceAllOpaque[i]) {
                    // bit22 = hasAlphaCutout: enable per-fragment discard for this face.
                    // bit23 = useAveragedMipDiscard: for leaf blocks, force the discard test
                    //         to use the tile's mip-averaged alpha instead of the raw
                    //         textureGrad sample.  This makes "Better Leaves" style models
                    //         (which use a sparse side texture) appear solid at LOD distance,
                    //         matching the intended canopy appearance.
                    faceData |= (1 << 22);
                    if (useAveragedMipDiscard) faceData |= (1 << 23);
                }
                int tintState = faceTinted[i] ? 2 : 0; // 2 = always tint
                faceData |= (tintState << 24);
                // Depth indentation (bits 16-21): encodes face position for partial-height blocks.
                // 0 = face flush with block edge; higher values push the face inward.
                int encDepth = Math.min((int) Math.round(faceDepths[i] * 64.0f), 63);
                faceData |= (encDepth << 16);
                this.modelBuf.putInt(faceData);
            } else {
                // Face missing: shader won't generate quads for it (metadata byte = 0xFF).
                this.modelBuf.putInt(0);
            }
        }

        // flagsA @ offset 24
        // bit1 (2) = modelHasBiomeLUT  — set via extraFlagsA when block uses biome colour LUT
        // bit2 (4) = modelIsTranslucent — for water/ice/stained glass
        // bit3 (8) = modelIsShaded      — directional shading, true for most blocks
        int flagsA = 8; // shaded by default
        // Check translucency: if any face is NOT fully opaque → translucent.
        boolean hasTransparent = false;
        for (int i = 0; i < 6; i++) {
            if (facePresent[i] && !faceAllOpaque[i]) { hasTransparent = true; break; }
        }
        if (hasTransparent) flagsA |= 4; // translucent
        flagsA |= extraFlagsA;           // e.g. bit1 = modelHasBiomeLUT
        this.modelBuf.putInt(flagsA);

        // colourTint @ offset 28  (0xFFFFFFFF = uint(-1) = no tint override)
        this.modelBuf.putInt(colourTint);

        // customId @ offset 32
        this.modelBuf.putInt(0);

        // _pad[7] @ offset 36
        for (int p = 0; p < 7; p++) this.modelBuf.putInt(0);

        this.modelBuf.flip();
        glNamedBufferSubData(this.storage.modelBuffer.id, (long) modelId * ModelStore.MODEL_SIZE, this.modelBuf);

        // modelColourBuffer is now managed by the biome LUT (uploaded in bakeBlock / addBiome).
    }

    private void writeBlankFace(int modelId, int faceIdx) {
        this.faceBuf.clear();
        for (int i = 0; i < MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE; i++) this.faceBuf.putInt(0);
        this.faceBuf.flip();
        uploadFaceBuffer(modelId, faceIdx);
    }

    /**
     * Bake a single face texture for a model into the voxy atlas.
     *
     * Sodium/Embeddium (and its NeoForge port) frees the CPU-side NativeImage backing a
     * TextureAtlasSprite after uploading it to the GPU, to reclaim memory. This breaks
     * the standard sprite.getPixelRGBA() API which reads from the NativeImage.
     *
     * Fix: probe getPixelRGBA(0,0,0) first. If it throws, fall back to uploadFaceTextureFromAtlas()
     * which reads the sprite region directly from the OpenGL block atlas texture.
     *
     * Y-flip rationale (applies to BOTH paths):
     *   Minecraft uploads atlas textures from Java images where row 0 is the VISUAL TOP.
     *   OpenGL stores textures with y=0 at the VISUAL TOP (matching Java image convention in
     *   this case because Minecraft doesn't flip on upload). The voxy atlas is uploaded the
     *   same way. So both the CPU and GPU paths preserve top→top orientation — no flip needed
     *   here. But the old CPU path DID flip, which was harmless because the shader's UV mapping
     *   compensated. We keep the same flip for both paths for consistency.
     *
     * @return true if all sampled pixels are fully opaque (a ≥ 250), false if semi-transparent.
     *         The return value controls whether the face sets the 'occludes' metadata bit.
     */
    private boolean uploadFaceTexture(int modelId, int faceIdx, TextureAtlasSprite sprite, BakedQuad quad) {
        int sw, sh;
        try {
            sw = sprite.contents().width();
            sh = sprite.contents().height();
        } catch (Throwable t) {
            return false;
        }
        if (sw <= 0 || sh <= 0) return false;

        // Some resource packs use composite "atlas" sprites wider than 16px (e.g. Reimagined's
        // birch_log_side_atlas is 48x48 — three 16x16 variants side by side). Each model variant
        // samples a different UV sub-tile via the baked quad's vertex UVs. Extract that sub-region
        // so we only copy the correct 16x16 portion rather than the whole composite texture.
        int px0 = 0, py0 = 0, pxW = sw, pyH = sh;
        if (quad != null) {
            int[] verts = quad.getVertices();
            // DefaultVertexFormat.BLOCK: 8 ints per vertex; UV0 at offsets 4 (u) and 5 (v).
            if (verts.length == 32) {
                float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE;
                float vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
                for (int v = 0; v < 4; v++) {
                    float u  = Float.intBitsToFloat(verts[v * 8 + 4]);
                    float vc = Float.intBitsToFloat(verts[v * 8 + 5]);
                    if (u  < uMin) uMin = u;  if (u  > uMax) uMax = u;
                    if (vc < vMin) vMin = vc; if (vc > vMax) vMax = vc;
                }
                float uRange = sprite.getU1() - sprite.getU0();
                float vRange = sprite.getV1() - sprite.getV0();
                if (uRange > 0 && vRange > 0) {
                    int x0 = Math.max(0, Math.round((uMin - sprite.getU0()) / uRange * sw));
                    int x1 = Math.min(sw, Math.round((uMax - sprite.getU0()) / uRange * sw));
                    int y0 = Math.max(0, Math.round((vMin - sprite.getV0()) / vRange * sh));
                    int y1 = Math.min(sh, Math.round((vMax - sprite.getV0()) / vRange * sh));
                    if (x1 > x0 && y1 > y0) { px0 = x0; py0 = y0; pxW = x1 - x0; pyH = y1 - y0; }
                }
            }
        }

        // Sodium/Embeddium frees the CPU-side NativeImage after GPU upload.
        // Detect this by probing the first pixel; if it throws, fall back to reading
        // directly from the block atlas GL texture.
        boolean cpuAccessible = true;
        try { sprite.getPixelRGBA(0, 0, 0); } catch (Throwable t) { cpuAccessible = false; }
        if (!cpuAccessible) {
            return uploadFaceTextureFromAtlas(modelId, faceIdx, sprite, sw, sh, px0, py0, pxW, pyH);
        }

        boolean allOpaque = true;
        this.faceBuf.clear();
        for (int y = 0; y < MODEL_TEXTURE_SIZE; y++) {
            // Y-flip: Minecraft's sprite has row 0 at the top; the voxy atlas is uploaded
            // with the same convention (top-to-bottom). We flip here to match the Y ordering
            // that uploadFaceBuffer expects (the upload goes to slotY which is GL y=0 = bottom
            // for a right-side-up atlas, so we need visual bottom in faceBuf[0]).
            int srcY = py0 + ((MODEL_TEXTURE_SIZE - 1 - y) * pyH) / MODEL_TEXTURE_SIZE;
            for (int x = 0; x < MODEL_TEXTURE_SIZE; x++) {
                int srcX = px0 + (x * pxW) / MODEL_TEXTURE_SIZE;
                int abgr;
                try {
                    abgr = sprite.getPixelRGBA(0, srcX, srcY);
                } catch (Throwable t) {
                    abgr = 0xFFFF00FF; // magenta marker for debug
                }
                // ABGR int (A high) -> little-endian bytes R,G,B,A which GL_RGBA/UBYTE expects.
                this.faceBuf.putInt(abgr);
                if (((abgr >>> 24) & 0xFF) < 250) allOpaque = false;
            }
        }
        this.faceBuf.flip();
        uploadFaceBuffer(modelId, faceIdx);
        return allOpaque;
    }

    // Cached atlas GL texture id and pixel dimensions (populated lazily on first fallback call).
    // We cache these because querying them via GL on every bake would be slow.
    // Invalidated by resource reloads, but baking only happens at session start before any reload.
    private int cachedAtlasId = -1;
    private int cachedAtlasW = -1;
    private int cachedAtlasH = -1;

    /**
     * GL-atlas fallback for when Sodium/Embeddium has freed the sprite's CPU NativeImage.
     *
     * Reads the sprite's pixel region directly from the GL block atlas texture using
     * glGetTextureSubImage, then rescales it to MODEL_TEXTURE_SIZE×MODEL_TEXTURE_SIZE.
     *
     * Atlas dimension query note:
     *   Two separate IntBuffer slots (wBuf, hBuf) are used for the two glGetTexLevelParameteriv
     *   calls rather than a single mallocInt(1) buffer reused twice.  LWJGL3's nglGetTexLevelParameteriv
     *   writes at memAddress(buf) = buf.address() + buf.position()*4.  If the implementation
     *   advances the position after writing, a single buffer reused for the second call would
     *   write past its capacity (position=1, capacity=1) and dim.get(0) would return the stale
     *   first value — meaning cachedAtlasH would equal cachedAtlasW.  For a non-square atlas
     *   (common with large modpacks), this doubles sprH, making the extracted sprite look
     *   vertically compressed (every other row sub-sampled) with adjacent atlas content below.
     *
     * Animated sprite capping:
     *   sprite.getV1() - sprite.getV0() covers ALL animation frames stacked vertically in
     *   the atlas.  We cap sprH to sh (the single-frame height from sprite.contents()) so we
     *   only sample the first animation frame.  For non-animated sprites this is a no-op.
     *
     * @param sw  Sprite frame width  (from sprite.contents().width())
     * @param sh  Sprite frame height (from sprite.contents().height())
     */
    private boolean uploadFaceTextureFromAtlas(int modelId, int faceIdx, TextureAtlasSprite sprite, int sw, int sh, int px0, int py0, int pxW, int pyH) {
        try {
            if (cachedAtlasId < 0) {
                var atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                        .getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
                cachedAtlasId = atlas.getId();
                // Use TWO separate IntBuffer slots to avoid the LWJGL position-advancement bug
                // (see Javadoc above) that would cause cachedAtlasH to equal cachedAtlasW for
                // non-square atlases.
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var wBuf = stack.mallocInt(1);
                    var hBuf = stack.mallocInt(1);
                    glBindTexture(GL_TEXTURE_2D, cachedAtlasId);
                    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH,  wBuf);
                    glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, hBuf);
                    cachedAtlasW = wBuf.get(0);
                    cachedAtlasH = hBuf.get(0);
                    Logger.info("[ModelFactory] Block atlas GL id=" + cachedAtlasId
                            + " size=" + cachedAtlasW + "x" + cachedAtlasH);
                }
            }
            if (cachedAtlasW <= 0 || cachedAtlasH <= 0) return false;

            // Compute the sprite's pixel rectangle in the atlas.
            // sprite.getU0/V0 are normalized [0,1] coords for the sprite's top-left corner.
            // sprite.getU1/V1 are the bottom-right corner of the FULL animation strip.
            // We cap sprW/sprH to the single-frame dimensions (sw, sh) so animated textures
            // only sample one frame rather than the full strip.
            int sprX = Math.round(sprite.getU0() * cachedAtlasW);
            int sprY = Math.round(sprite.getV0() * cachedAtlasH);
            int sprW = Math.max(1, Math.min(Math.round(sprite.getU1() * cachedAtlasW) - sprX, sw));
            int sprH = Math.max(1, Math.min(Math.round(sprite.getV1() * cachedAtlasH) - sprY, sh));

            ByteBuffer atlasPix = MemoryUtil.memAlloc(sprW * sprH * 4);
            try {
                // Read the sprite rectangle from the GL atlas.
                // zoffset=0 and depth=1 work for both GL_TEXTURE_2D and the first layer of
                // GL_TEXTURE_2D_ARRAY (Sodium may use array textures for atlas storage).
                glGetTextureSubImage(cachedAtlasId, 0, sprX, sprY, 0, sprW, sprH, 1, GL_RGBA, GL_UNSIGNED_BYTE, atlasPix);
                boolean allOpaque = true;
                this.faceBuf.clear();
                for (int y = 0; y < MODEL_TEXTURE_SIZE; y++) {
                    // Same Y-flip as the CPU path: the atlas data has visual top at low GL y
                    // (Minecraft stores textures upside-down from standard GL), so we flip to
                    // place the visual bottom in faceBuf[0] which maps to slotY (GL bottom of slot).
                    // Use px0/py0/pxW/pyH to sample only the UV sub-tile of a composite sprite.
                    int srcY2 = py0 + ((MODEL_TEXTURE_SIZE - 1 - y) * pyH) / MODEL_TEXTURE_SIZE;
                    for (int x = 0; x < MODEL_TEXTURE_SIZE; x++) {
                        int srcX = px0 + (x * pxW) / MODEL_TEXTURE_SIZE;
                        int pOff = (srcY2 * sprW + srcX) * 4;
                        int r = atlasPix.get(pOff)     & 0xFF;
                        int g = atlasPix.get(pOff + 1) & 0xFF;
                        int b = atlasPix.get(pOff + 2) & 0xFF;
                        int a = atlasPix.get(pOff + 3) & 0xFF;
                        // Pack as ABGR: A high byte, then B, G, R.
                        // When this int is written as 4 little-endian bytes: R,G,B,A — the
                        // layout that GL_RGBA / GL_UNSIGNED_BYTE expects on upload.
                        int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                        this.faceBuf.putInt(abgr);
                        if (a < 250) allOpaque = false;
                    }
                }
                this.faceBuf.flip();
                uploadFaceBuffer(modelId, faceIdx);
                return allOpaque;
            } finally {
                MemoryUtil.memFree(atlasPix);
            }
        } catch (Throwable t) {
            Logger.warn("Sprite GL-atlas fallback failed for face " + faceIdx + ": " + t);
            return false;
        }
    }

    private void uploadFaceBuffer(int modelId, int faceIdx) {
        int faceX = faceIdx >> 1;
        int faceY = faceIdx & 1;
        int slotX = (modelId & 0xFF) * 3 * MODEL_TEXTURE_SIZE + faceX * MODEL_TEXTURE_SIZE;
        int slotY = ((modelId >> 8) & 0xFF) * 2 * MODEL_TEXTURE_SIZE + faceY * MODEL_TEXTURE_SIZE;
        glTextureSubImage2D(this.storage.textures.id, 0, slotX, slotY,
                MODEL_TEXTURE_SIZE, MODEL_TEXTURE_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, this.faceBuf);
    }

    // ── Biome colour helpers ─────────────────────────────────────────────────

    /** Looks up the MC Biome from a voxy biome resource-location string. */
    private static Biome lookupBiome(String biomeName) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(biomeName);
            if (rl == null) return null;
            Minecraft mc = Minecraft.getInstance();
            // Try connection registry (multiplayer / integrated server).
            if (mc.getConnection() != null) {
                var reg = mc.getConnection().registryAccess().registry(Registries.BIOME).orElse(null);
                if (reg != null) { Biome b = reg.get(rl); if (b != null) return b; }
            }
            // Fallback: level registry (available once world is loaded).
            if (mc.level != null) {
                var reg = mc.level.registryAccess().registry(Registries.BIOME).orElse(null);
                if (reg != null) { Biome b = reg.get(rl); if (b != null) return b; }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Returns the RGB colour for a given biome and colour type.
     *
     * If a non-null {@code resolver} is provided (captured from the block's registered
     * BlockColors handler), it is called as {@code resolver.getColor(biome, 0, 0)}.  This
     * honours any mod-replaced color provider (Quark GreenerGrass, Aether, etc.) instead of
     * falling back to the vanilla GrassColor/FoliageColor formula.
     *
     * Falls back to the vanilla formula when resolver is null (water special-case) or throws.
     */
    private static int getColorForBiome(Biome biome, byte colorType, ColorResolver resolver) {
        try {
            if (resolver != null) {
                return resolver.getColor(biome, 0.0, 0.0) & 0xFFFFFF;
            }
            var effects = biome.getSpecialEffects();
            return switch (colorType) {
                case 1 -> {  // grass
                    int color = effects.getGrassColorOverride().orElse(-1);
                    if (color == -1) {
                        double temp = Mth.clamp((double) biome.getBaseTemperature(), 0.0, 1.0);
                        double humidity = Mth.clamp((double) biome.getModifiedClimateSettings().downfall(), 0.0, 1.0);
                        color = GrassColor.get(temp, humidity);
                    }
                    yield effects.getGrassColorModifier().modifyColor(0.0, 0.0, color);
                }
                case 2 -> {  // foliage
                    int color = effects.getFoliageColorOverride().orElse(-1);
                    if (color == -1) {
                        double temp = Mth.clamp((double) biome.getBaseTemperature(), 0.0, 1.0);
                        double humidity = Mth.clamp((double) biome.getModifiedClimateSettings().downfall(), 0.0, 1.0);
                        color = FoliageColor.get(temp, humidity);
                    }
                    yield color;
                }
                case 3 -> effects.getWaterColor();  // water
                default -> 0xFFFFFF;
            };
        } catch (Throwable t) {
            return 0xFFFFFF;
        }
    }

    /**
     * Detects the biome colour type for a block state:
     *   1 = grass (most vegetation), 2 = foliage (leaves, vines), 3 = water.
     */
    private static byte detectColorType(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LiquidBlock) return 3;                                // water
        if (state.is(BlockTags.LEAVES)
                || block instanceof VineBlock
                || block == Blocks.LILY_PAD) return 2;                             // foliage
        return 1;                                                                   // grass
    }

    // ────────────────────────────────────────────────────────────────────────

    public void free() {
        try { this.bakery2.free(); } catch (Throwable ignored) {}
        MemoryUtil.memFree(this.faceBuf);
        MemoryUtil.memFree(this.modelBuf);
    }

    public int[] _unsafeRawAccess() {
        return this.blockIdToModelId;
    }

    public int getModelId(int blockId) {
        if (blockId < 0 || blockId >= this.blockIdToModelId.length) return 0;
        int id = this.blockIdToModelId[blockId];
        return id < 0 ? 0 : id;
    }

    public int getFluidClientStateId(int clientBlockStateId) {
        return 0;
    }

    public final long getModelMetadataFromClientId(int clientId) {
        if (clientId <= 0 || clientId >= this.metadataCache.length) return 0L;
        long m = this.metadataCache[clientId];
        // If not yet baked, return a sensible default so meshing can still emit faces.
        return m == 0L ? FALLBACK_OPAQUE_META : m;
    }

    public int getBakedCount() {
        return this.bakedCount;
    }

    public int getInflightCount() {
        return this.inflight.get();
    }

    public boolean hasModelForBlockId(int blockId) {
        return blockId >= 0 && blockId < this.blockIdToModelId.length && this.blockIdToModelId[blockId] >= 0;
    }
}
