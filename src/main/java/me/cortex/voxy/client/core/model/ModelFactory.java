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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGenerateTextureMipmap;
import static org.lwjgl.opengl.ARBDirectStateAccess.glTextureSubImage2D;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
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
    // BIOME_STRIDE=512 matches the 9-bit biome field; MAX_BIOME_MODELS * 512 = 65536 = full colourBuffer.
    private static final int BIOME_STRIDE = 512;
    private static final int MAX_BIOME_MODELS = 128;
    // modelId -> index into the biome table (-1 = not biome-dependent)
    private final int[] modelBiomeIndex = new int[1 << 16];
    // modelId -> color type: 1=grass, 2=foliage, 3=water
    private final byte[] modelColorType = new byte[1 << 16];
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
        int modelId = this.nextModelId++;
        this.blockIdToModelId[blockId] = modelId;
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
            int argb = 0xFF000000 | getColorForBiome(mcBiome, colorType);
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
            try { glGenerateTextureMipmap(this.storage.textures.id); } catch (Throwable ignored) {}
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
                    // Only fall back to the first null quad if this block actually has
                    // directional quads (i.e. is not a cross-plant model). Cross-plant
                    // null quads are diagonal geometry that maps to no real cube face.
                    if (picked == null && !nullQuads.isEmpty() && (hasAnyDirectionalQuad || isPureFluid))
                        picked = nullQuads.get(0);
                }
            }

            if (picked != null) sprite = picked.getSprite();
            // Don't fall back to particle icon for cross-plant/sprite-only models;
            // they should be invisible in LOD rather than rendered as solid cubes.
            if (sprite == null && (hasAnyDirectionalQuad || isPureFluid)) {
                try { sprite = model.getParticleIcon(); } catch (Throwable ignored) {}
            }

            int faceByte;
            if (sprite == null) {
                // no geometry on this face; mark face missing
                writeBlankFace(modelId, faceIdx);
                faceByte = 0xFF;
                facePresent[faceIdx] = false;
            } else {
                boolean opaque = uploadFaceTexture(modelId, faceIdx, sprite);
                // bit0 = occludes neighbor face; bit2 = can be occluded by neighbor.
                faceByte = (opaque && canOcclude) ? 0b00000101 : 0b00000100;
                facePresent[faceIdx] = true;
                faceAllOpaque[faceIdx] = opaque;
            }
            meta |= ((long)(faceByte & 0xFF)) << (faceIdx * 8);

            if (picked != null && picked.isTinted()) {
                faceTinted[faceIdx] = true;
                if (!anyTinted) {
                    anyTinted = true;
                    try {
                        // Use a sentinel BlockAndTintGetter: if getBlockTint() is called,
                        // the block's color is biome-dependent (grass, foliage, etc.).
                        boolean[] biomeDep = {false};
                        final BlockState capturedState = state;
                        int rgb = mc.getBlockColors().getColor(state, new BlockAndTintGetter() {
                            @Override public float getShade(Direction dir, boolean shade) { return 1.0f; }
                            @Override public LevelLightEngine getLightEngine() { return null; }
                            @Override public int getBlockTint(BlockPos p, ColorResolver resolver) {
                                biomeDep[0] = true;
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
                            if (this.nextBiomeModelIndex < 10) {
                                DIAG.info("bakeBlock: BIOME-DEP detected block={} modelId={} registeredBiomes={}",
                                        state.getBlock().getDescriptionId(), modelId, this.registeredBiomes.size());
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

        int modelFlags = 0;
        if (canOcclude) modelFlags |= 0b01100000; // fullyOpaque + cullsSame
        if (anyTinted)  modelFlags |= 0b00000001; // biomeColoured (we don't have a LUT; just const tint)

        // Fluid detection: pure fluid block (water, lava) vs waterlogged block
        boolean isWaterlogged = (!isPureFluid)
                && state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
        if (isPureFluid)    modelFlags |= 0b00010000; // isFluid
        if (isWaterlogged)  modelFlags |= 0b00001000; // containsFluid

        // Translucency: water, ice, stained glass, etc. — any present face that is not fully opaque
        boolean anyFaceTransparent = false;
        for (int i = 0; i < 6; i++) {
            if (facePresent[i] && !faceAllOpaque[i]) { anyFaceTransparent = true; break; }
        }
        if (isPureFluid || anyFaceTransparent) modelFlags |= 0b00000010; // isTranslucent

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
            // Overwrite slots for already-registered biomes.
            for (Map.Entry<Integer, Biome> e : this.registeredBiomes.entrySet()) {
                glNamedBufferSubData(this.storage.modelColourBuffer.id,
                        (long)(biomeIdx * BIOME_STRIDE + e.getKey()) * 4,
                        new int[]{0xFF000000 | getColorForBiome(e.getValue(), colorType)});
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
        uploadBlockModelStruct(modelId, state, facePresent, faceAllOpaque, faceTinted, resolvedColourTint, biomeLUTFlag);
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
     *   bit23        = hasAlphaCutoutOverride
     *   bits[24..25] = tintState (0=none, 1=partial, 2=always)
     */
    private void uploadBlockModelStruct(int modelId, BlockState state,
            boolean[] facePresent, boolean[] faceAllOpaque, boolean[] faceTinted, int colourTint, int extraFlagsA) {
        this.modelBuf.clear();

        // faceData[6] — 24 bytes
        for (int i = 0; i < 6; i++) {
            if (facePresent[i]) {
                // Full-face bounds: start=0, end=15 for both axes.
                int faceData = 0x0000F0F0;
                if (!faceAllOpaque[i]) {
                    // Has semi-transparent pixels: enable alpha cutout.
                    faceData |= (1 << 22) | (1 << 23);
                }
                int tintState = faceTinted[i] ? 2 : 0; // 2 = always tint
                faceData |= (tintState << 24);
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

    private boolean uploadFaceTexture(int modelId, int faceIdx, TextureAtlasSprite sprite) {
        int sw, sh;
        try {
            sw = sprite.contents().width();
            sh = sprite.contents().height();
        } catch (Throwable t) {
            return false;
        }
        if (sw <= 0 || sh <= 0) return false;

        boolean allOpaque = true;
        this.faceBuf.clear();
        for (int y = 0; y < MODEL_TEXTURE_SIZE; y++) {
            // Flip Y: GL textures have row 0 at the bottom, MC sprites have row 0 at the top.
            // Without the flip, side-face textures (e.g. grass_block_side) appear upside-down.
            int srcY = ((MODEL_TEXTURE_SIZE - 1 - y) * sh) / MODEL_TEXTURE_SIZE;
            for (int x = 0; x < MODEL_TEXTURE_SIZE; x++) {
                int srcX = (x * sw) / MODEL_TEXTURE_SIZE;
                int abgr;
                try {
                    abgr = sprite.getPixelRGBA(0, srcX, srcY);
                } catch (Throwable t) {
                    abgr = 0xFFFF00FF; // magenta marker
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

    /** Returns the grass/foliage/water RGB colour for a given biome and colour type. */
    private static int getColorForBiome(Biome biome, byte colorType) {
        try {
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
        if (block instanceof LeavesBlock
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
