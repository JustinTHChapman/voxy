#version 460 core

// ── Extension selection ───────────────────────────────────────────────────────
// USE_NV_BARRY / USE_SINGLE_TRI: when the NV barycentric extension is available
// the vertex shader emits only one or two triangles per quad instead of a full
// quad strip, which reduces GPU vertex-processing overhead.  The barycentric
// coordinates are then used here to reconstruct the per-fragment UV that would
// normally come from an interpolated attribute.
//#extension GL_KHR_shader_subgroup_quad: enable   (future: subgroup-based mip)
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

// ── Texture bindings ──────────────────────────────────────────────────────────
// blockModelAtlas  (binding 0): the voxy model atlas.  Each modelId occupies a
//   3×2 cell of 16×16 face tiles, laid out as (face/2, face&1) within the cell.
//   The atlas is 256×256 cells → 768×512 texels total (3*256*16 × 2*256*16).
// depthTex         (binding 2): a downscaled depth buffer captured from the
//   opaque geometry pass.  Used to early-out LOD fragments that sit behind
//   already-drawn solid geometry, saving fillrate on occluded terrain.
layout(binding = 0) uniform sampler2D blockModelAtlas;
#ifndef SHADOW_PASS
layout(binding = 2) uniform sampler2D depthTex;
#endif

//#define DEBUG_RENDER   // enable to colour each quad by a unique hash (debugging)

//TODO: need to fix when merged quads have discardAlpha set to false but they
// span multiple tiles yet are not a full block — the tile-boundary clip below
// may clip too aggressively in that case.

// ── Vertex → fragment interpolants ───────────────────────────────────────────
// interData is a flat (non-interpolated) uvec4 packed by the vertex shader:
//   .x  bits[0]     useDiscard flag  — 1 = cutout alpha test (leaves, glass panes)
//       bits[2:1]   tintingState     — 0=none, 1=partial, 2=always
//       bits[6:4]   face index       — 0..5 = DOWN/UP/NORTH/SOUTH/WEST/EAST
//       bits[11:8]  quad width  – 1  (tiles, 0-based → add 1 before use)
//       bits[15:12] quad height – 1
//       bits[31:16] modelId
//   .y  packed light + biome colour info (decoded by lighting.glsl)
//   .z  RGBA biome tint (ARGB u32, only valid when tintingState != 0)
//   .w  low byte = ambient occlusion / additive alpha term
layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
// Standard path: UV is interpolated from the four quad vertices by the rasteriser.
layout(location = 1) in vec2 uv;
#endif

#ifdef DEBUG_RENDER
// Debug path only: each quad is given a unique integer so we can colourise it.
layout(location = 7) in flat uint quadDebug;
#endif


// ── Output / patched-shader integration ──────────────────────────────────────
// In the standard (non-Iris) path we write directly to location 0.
// When PATCHED_SHADER is defined Iris has replaced the output with its own
// framebuffer hooks, so we call voxy_emitFragment() instead and let Iris
// route the data.
#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

// The block_model.glsl import exposes the per-model metadata SSBO (binding 3).
#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>

#endif

// global UBOs (camera matrices, fog params)
#import <voxy:lod/gl46/bindings.glsl>
// getLightmapUv() — converts packed light value to UV for the MC lightmap texture
#import <voxy:lod/lighting.glsl>
// DEPTH_SCALAR_COMPARE macro — adjusts depth comparison for the downscaled depthTex
#import <voxy:util/depthutils.glsl>


// ── Utility helpers ───────────────────────────────────────────────────────────

// Unpack a packed u32 RGBA (A in high byte) into a normalised vec4.
// Used for the tint colour and the light/biome colour stored in interData.
vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

// Tinting mode packed into bits [3:2] of interData.x:
//   0 = no tint, 1 = partial (only greyscale pixels get tinted), 2 = full tint.
uint tintingState() {
    return (interData.x>>2)&3u;
}

// Whether this quad uses cutout alpha discard (true for leaves, grass, etc.).
// Packed into bit 0 of interData.x by ModelFactory.
bool useDiscard() {
    return (interData.x&1u)==1u;
}

// Face index 0-5 (DOWN/UP/N/S/W/E), encoded in bits [6:4].
uint getFace() {
    return (interData.x>>4)&7u;
}

// ModelId stored in the upper 16 bits of interData.x.
uint getModelId() {
    return interData.x>>16;
}

// Compute the base UV of this face's 16×16 tile in the block model atlas.
// Atlas layout: each modelId occupies a (1/256)×(1/256) cell; within the cell
// the 6 faces are arranged in a 3-column × 2-row sub-grid.
// face>>1 selects the column (0..2), face&1 the row (0..1).
vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    // Normalised position of this model's cell top-left corner.
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    // Offset to the specific face tile within the 3×2 sub-grid.
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


// ── PATCHED_SHADER interface ──────────────────────────────────────────────────
#ifdef PATCHED_SHADER
// Struct passed to Iris's voxy_emitFragment hook.  Iris uses this to apply its
// own post-processing, PBR shaders, etc.
struct VoxyFragmentParameters {
    //TODO: pass in derivative data for Iris mip-bias support
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId; // mirrors Iris's internal modelId for compatibility
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else

// ── Non-patched colour computation ────────────────────────────────────────────
// Applies biome tint and light colour to the sampled texture colour.
// tintingState controls whether to multiply by the biome colour stored in
// interData.z:
//   1 (partial): only tint greyscale-ish pixels so grass side textures don't
//                tint the brown dirt strip.
//   2 (full):    always tint (grass top, leaves, etc.).
// The final term adds any additive alpha from ambient occlusion (interData.w).
vec4 computeColour(vec2 texturePos, vec4 colour) {
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2; // full tint: always apply

    if (tintingFunction == 1) {
        // Partial tint: sample at mip 0 to check whether the pixel is near-grey.
        // Near-grey pixels are assumed to be the vegetation overlay that should
        // receive the biome colour; coloured pixels (bark, stone) should not.
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }

    if (doTint) {
        // interData.z is packed ARGB; .yzwx swizzles it to RGBA order.
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }

    // Multiply by the per-vertex light/biome colour (interData.y) and add the
    // ambient-occlusion additive alpha packed in the low byte of interData.w.
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


// ═════════════════════════════════════════════════════════════════════════════
// main()
// ═════════════════════════════════════════════════════════════════════════════
void main() {

    // ── Step 1: reconstruct UV ────────────────────────────────────────────────
    // "tile" = which repetition of the texture we are in (integer part of uv).
    // "uv2"  = fractional offset within the current tile, scaled to atlas texels.
    // The NV barycentric path reconstructs UV from barycentric coordinates because
    // the vertex shader emits only a single triangle / two triangles per quad to
    // avoid generating four vertices.  The standard path receives the interpolated
    // UV attribute directly from the rasteriser.
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    // Single-triangle mode: the quad is encoded in one degenerate triangle.
    // Barycentric coords outside [0,0.5]² are outside the quad — discard them.
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    // Two-triangle mode: alternate triangles cover the two halves of the quad.
    // gl_PrimitiveID&1 selects which triangle we are in, choosing the correct
    // barycentric interpolation formula so the UV is continuous across the seam.
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    // modf splits uv into integer tile index + [0,1) fractional part.
    // Scale the fraction to atlas texel space (one face tile = 1/(3*256) × 1/(2*256)).
    vec2 uv2 = modf(uv, tile)*(1.0/(vec2(3.0,2.0)*256.0));
    vec2 texPos = uv2 + getBaseUV();

    // ── Step 2: sample the atlas with correct mip ─────────────────────────────
    // We use textureGrad so the GPU can compute the correct mip level based on
    // the actual screen-space derivatives of the atlas UV, rather than the
    // automatic derivatives of the interpolated attribute (which can be wrong at
    // tile boundaries where uv wraps).  uvSmol converts the tile-space UV into
    // atlas-normalised space so dFdx/dFdy have the right scale.
    vec4 colour;
    {
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);
        vec2 dy = dFdy(uvSmol);
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
    }

    // ── Step 3: early-out helper invocations (post-derivative) ───────────────
    // Helper invocations are launched by the GPU to compute derivatives for quads
    // that straddle a primitive edge.  Their colour output is discarded but their
    // derivative work is needed.  We exit here — after the textureGrad call —
    // so the derivative computation is still correct but we skip all subsequent
    // work for these throw-away invocations.
    // (Not done inside PATCHED_SHADER_ALLOW_DERIVATIVES because Iris may need the
    // helper result for its own derivative passes.)
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    // ── Step 4: tile-boundary clip ────────────────────────────────────────────
    // The greedy mesher can merge multiple block faces into a single quad.  The
    // UV then spans [0, mergedWidth] × [0, mergedHeight] in tile space.  Fragments
    // outside that rectangle (at the quad edges due to triangle rasterisation
    // overshoot) must be discarded so we don't draw into neighbouring atlas tiles.
    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        discard;
        return;
    }

    // ── Step 5: LOD depth occlusion test ─────────────────────────────────────
    // The depth texture captures the closest opaque geometry rendered before this
    // LOD pass.  If this fragment is behind that geometry it would be invisible,
    // so we discard it here to save fill-rate and avoid z-fighting with vanilla
    // chunks that overlap the LOD region near the render-distance border.
#ifndef SHADOW_PASS
    if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {
        discard;
        return;
    }
#endif

    // ── Step 6: alpha discard / translucent setup ─────────────────────────────
    // Opaque / cutout path (#ifndef TRANSLUCENT):
    //   Use colour.a from the mip-filtered textureGrad sample above.  This is
    //   critical for large greedy-merged quads (e.g. a full leaf canopy merged
    //   into one quad): mip-0 sampling would hit a random mix of opaque and
    //   transparent texels and discard roughly half the fragments, making the
    //   canopy look see-through.  The mip-averaged alpha (~0.5 for typical leaf
    //   textures) reliably passes the 0.1 threshold so the canopy renders solid.
    //   After the test we force alpha to 1.0 — the opaque pass must not write
    //   partial alpha into the framebuffer.
    //
    // Translucent path (#else, for water / glass / tinted glass):
    //   We must keep the texture's real alpha so the framebuffer blend produces
    //   correct transparency.  We only discard fully-transparent (alpha == 0)
    //   fragments; partial alpha fragments are kept and blended by the GPU.
    //   We sample mip-0 here for an exact per-pixel decision because the block's
    //   opaque border pixels must not be discarded at the borders of the pane.
    #ifndef TRANSLUCENT
    // For leaf blocks (bit1 set), use the tile's mip-averaged alpha for the discard test.
    // This ensures "Better Leaves" sparse side textures (individual leaf silhouettes on a
    // transparent background) average out to solid at LOD distance, matching the canopy look.
    // For all other cutout blocks, use the textureGrad alpha which preserves detail.
    float cutoutAlpha = colour.a;
    if ((interData.x & 2u) == 2u) {
        cutoutAlpha = textureLod(blockModelAtlas, texPos, 3.0).a;
    }
    if (useDiscard() && (cutoutAlpha <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }
    #ifndef TRANSLUCENT
    colour.a = 1.0f; // force opaque — alpha must not bleed into the framebuffer
    #endif

    // ── Step 7: second helper-invocation guard ────────────────────────────────
    // After the alpha test we do a second helper cull.  Some drivers allow helpers
    // to survive the first guard above (e.g. when depth comparison is disabled)
    // but still need to write derivatives; this second guard ensures helpers never
    // reach the colour-output stage.
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    // ── Step 8: colour output ─────────────────────────────────────────────────
    #ifndef PATCHED_SHADER
    // Standard path: apply tinting and lighting, then write to the framebuffer.
    colour = computeColour(texPos, colour);
    outColour = colour;

    #ifdef DEBUG_RENDER
    // Debug mode: replace the colour with a per-quad pseudorandom hue so each
    // merged quad is visually distinct.  Useful for verifying greedy meshing.
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    // Iris (PATCHED_SHADER) path: gather all parameters and hand off to the
    // voxy_emitFragment hook so Iris can apply its own lighting model, shadows,
    // PBR shaders, etc. without needing to re-sample the atlas.
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];

    // Determine tinting for the Iris path (same logic as computeColour above,
    // but sampling with a large negative bias to get a very blurred result for
    // the greyscale test — avoids mip-0 artifacts on partial tint detection).
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;
    if (tintingFunction==1) {
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    // Normalise face direction for Iris: front-facing geometry always uses the
    // "outward" face (UP, NORTH, WEST) so the shader pack receives the correct
    // normal for directional lighting.  Back-facing interior cross-section faces
    // receive the inverse direction and appear dark rather than sky-blue.
    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));

    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId));

    #endif
}


// ── Dead code / future work ───────────────────────────────────────────────────
// Subgroup-quad mip computation (GL_KHR_shader_subgroup_quad, currently disabled):
// Instead of textureGrad we could compute derivatives manually via horizontal /
// vertical subgroup swaps, giving correct derivatives even across tile seams
// without needing uvSmol.  Disabled because the extension is not universally
// supported.
//#ifdef GL_KHR_shader_subgroup_quad
/*
uint hash = (uint(tile.x)*(1<<16))^uint(tile.y);
uint horiz = subgroupQuadSwapHorizontal(hash);
bool sameTile = horiz==hash;
uint sv = mix(uint(-1), hash, sameTile);
uint vert = subgroupQuadSwapVertical(sv);
sameTile = sameTile&&vert==hash;
mipBias = sameTile?0:-5.0;
*/
/*
vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
float lDx = subgroupQuadSwapHorizontal(uvSmol.x)-uvSmol.x;
float lDy = subgroupQuadSwapVertical(uvSmol.y)-uvSmol.y;
float dDx = subgroupQuadSwapDiagonal(lDx);
float dDy = subgroupQuadSwapDiagonal(lDy);
vec2 dx = vec2(lDx, dDx);
vec2 dy = vec2(lDy, dDy);
colour = textureGrad(blockModelAtlas, texPos, dx, dy);
*/
//#else
//colour = texture(blockModelAtlas, texPos);
//#endif

// Undefine depth macros so they don't leak into subsequent imports.
#import <voxy:util/depthutils.glsl>
