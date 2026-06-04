# Voxy Rendering Diagnostics Skill

When invoked via `/voxy-diagnose`, run a rapid diagnostic sweep of the current rendering state and report findings.

## What to check

### 1. Build state
- Run `git status` to see uncommitted changes
- Run `git log --oneline -5` to confirm latest commits
- Check if jar is newer than source: `ls -la build/libs/*.jar` vs most recently modified source file

### 2. Texture pipeline
Key file: `src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java`

Check `uploadFaceTexture()` (around line 455):
- `srcY` formula: must be `((MODEL_TEXTURE_SIZE - 1 - y) * sh) / MODEL_TEXTURE_SIZE` (Y-flipped)
  - Without flip: side-face textures appear upside-down (grass green at bottom, dirt at top)
- Cross-plant guard: must have `hasAnyDirectionalQuad` pre-scan before the face loop
  - Without guard: flowers/short-grass render as solid cubes in LOD

Check `uploadBlockModelStruct()` (around line 397):
- Full-face faceData value must be `0x0000F0F0` (start_x=0, end_x=15, start_z=0, end_z=15)

### 3. LightMap binding
Key file: `src/main/java/me/cortex/voxy/client/core/rendering/util/LightMapHelper.java`

- Must NOT reference `MixinLightTexture` (that file was deleted — it causes Sodium crash)
- Must use reflection-based `lightTextureField` approach
- `getLightmapTextureId()` should return non-zero (test: add a LOGGER.info in `bind()`)

### 4. Mixin list
Key file: `src/main/resources/client.voxy.mixins.json`
- Must NOT contain `"minecraft.MixinLightTexture"` (deleted)
- Must contain `"sodium.MixinDefaultChunkRenderer"` in the sodium compat mixin file

### 5. Atlas layout verification
The Voxy block model atlas layout:
- Each model gets 3×2 tiles of 16×16 pixels
- Tile column (X): `faceIdx >> 1` → 0=Y faces, 1=Z faces, 2=X faces
- Tile row (Y): `faceIdx & 1` → 0=negative, 1=positive
- Face indices: 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST

To verify a specific block's atlas slot: find `modelId` in logs (search "bakeBlock" log line), then calculate:
```
slotX = (modelId & 0xFF) * 3 * 16 + (faceIdx >> 1) * 16
slotY = ((modelId >> 8) & 0xFF) * 2 * 16 + (faceIdx & 1) * 16
```

### 6. Shader face→UV mapping (quads.frag)
```glsl
getBaseUV().y = (modelId>>8)/256.0 + (face&1u)/(2.0*256.0)
```
Face 0 (DOWN) → tile row 0 (bottom of model's 2-row slot)
Face 1 (UP) → tile row 1

### 7. Recent log evidence
If player has `debug.log` access, grep for:
- `[AutoGeneration]` — generation service activity
- `bakeBlock:` — model baking events
- `ModelFactory:` — diagnostic output (logs every 2s)
- `addBiome:` — biome registration

## Quick deploy after fixes
```
./gradlew deployLocal
```
Copies jar to `%APPDATA%/PrismLauncher/instances/World of Titans/.minecraft/mods/voxy.jar`.

## Common failure modes

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Grass side texture upside-down | Missing Y-flip in `uploadFaceTexture` | Add `(MODEL_TEXTURE_SIZE - 1 - y)` formula |
| Flowers/grass rendered as solid cubes | Missing `hasAnyDirectionalQuad` guard | Add pre-scan + guard in `bakeBlock` |
| Crash at `GameRenderer.<init>` with Sodium | `MixinLightTexture` still in codebase | Delete file + remove from mixins.json |
| LOD terrain dark / no lighting | LightMapHelper returning 0 | Check reflection field lookup succeeds |
| Chunks at LOD boundary show underground | Missing neighbor-empty culling | Check `RenderDataFactory.neighborSectionEmpty[]` |
