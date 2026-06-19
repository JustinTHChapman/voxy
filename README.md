# Voxy LOD — NeoForge 1.21.1 Port

Unofficial NeoForge 1.21.1 port of [Voxy](https://github.com/corvesive/voxy), a Level-of-Detail (LOD) mod that renders distant terrain at reduced geometry cost, extending your visible world far beyond vanilla render distance.

> **Personal use port.** No support is provided. Use at your own risk.

---

## Features

### Rendering
- **Far-plane LOD rendering** — distant chunks rendered as merged, low-polygon geometry at configurable radius (default 256 sections, up to 8192)
- **Biome-accurate colours** — grass, foliage, and water tints are computed per-biome using the game's registered `ColorResolver`; mod-replaced colour providers (Quark GreenerGrass, Aether, etc.) are captured automatically
- **Correct transparency** — water, lava, stained glass, tinted glass, and ice render in the translucent pass with proper alpha blending
- **Leaf canopy rendering** — leaf blocks render solid at LOD distance using mip-averaged alpha cutout; no grid/sparse-canopy artefact
- **Partial-height block support** — slabs, snow layers, and similar blocks clip neighbouring faces correctly
- **Waterlogged block detection** — blocks containing fluid are flagged so fluid surfaces render correctly
- **Face occlusion / greedy meshing** — adjacent same-model faces are merged and culled to minimise draw calls
- **Environmental fog** — LOD geometry integrates with Minecraft's sky and terrain fog
- **SSAO (Screen-Space Ambient Occlusion)** — optional, configurable (AUTO / BASIC / OFF)
- **OpenGL 4.5 DSA renderer** — uses named buffers, `glNamedBufferSubData`, and `glGetTextureSubImage` for efficient GPU transfers

### World Generation & Sync
- **Background auto-generation** — client scans for un-generated sections within the LOD radius and builds them in closest-first order without blocking gameplay; throttles automatically when the server is under load (MSPT-based rate limiter)
- **Server-to-client streaming** — server pushes LOD section data to connecting clients; delta-sync via manifest (only sections with changed content are transferred)
- **Client-to-server upload** — client-generated LOD data is uploaded back to the server so other players benefit without regenerating
- **SQLite persistence** — LOD data is stored on-disk in a compressed SQLite database and survives world restarts
- **Multi-threaded ingest** — dedicated worker threads process incoming chunk sections in parallel; service manager balances load across ingest, save, and render-build tasks
- **Distant force-loading** (singleplayer) — columns beyond render distance are force-loaded via transient, auto-expiring tickets (non-blocking — never `managedBlock`) so the world generates far past vanilla render distance; concurrent loads are capped by config
- **Never skips a column** — a column that hasn't finished generating keeps retrying until it loads; it is never silently dropped (which would leave permanent gaps and break neighbour face-culling at the hole edges). A column that genuinely can't load (outside the world border) or is stuck far past normal worldgen logs an **error** so the failure is visible and reportable, not hidden
- **Correct distant lighting** — force-loaded chunks are voxelized once their lighting has settled, so distant LODs aren't baked dark (with a short fallback so a never-settling light state can't leave a gap; a deterministic light path is planned)
- **Save-safe generation** — chunks force-loaded purely to build LODs are marked not-to-save, so distant generation never bloats or hangs the world save; the LOD lives in voxy's own store and the source chunk regenerates deterministically if the player ever visits

### Iris Shader Pack Support
- **Full compatibility** — LOD terrain renders correctly alongside Iris shader packs; fog, water translucency, and sky all integrate with the active shader pack's pipeline
- **LOD shadow casting** — currently disabled; LOD geometry outside vanilla render distance can project shadows back onto vanilla terrain at oblique sun angles, producing phantom shadow patches that shift as the player moves
- **No shader pack required** — all features work without Iris installed

### Mod Compatibility
- **Lithium** — `LithiumHashPalette` and other mod-replaced chunk palette types are handled via generic fallback; no crash or silent data loss
- **Embeddium / Sodium** — sprite textures are read via GL atlas fallback when the mod frees the CPU-side `NativeImage` after GPU upload (prevents pink/magenta blocks)
- **Quark (Greener Grass module)** — custom `ColorResolver` is captured and used directly for biome colour LUT generation
- **Aether** — same resolver-capture approach covers Aether's grass colour overrides
- **Separated Leaves** — leaf rendering uses `BlockTags.LEAVES` (not `instanceof LeavesBlock`) so modded leaf blocks are handled correctly
- **Terrain Slabs** — thin surface blocks (snow, carpet) placed on half-height slab terrain are collapsed down one LOD voxel during ingest so they appear at the correct elevation in distant LOD rather than floating one block above the surface

### Configuration

**Client** (`config/voxy-config.json`):
| Option | Default | Description |
|---|---|---|
| `draw_lods` | `true` | Draw stored LODs. Off = ingest-only (build the LOD database without rendering it) |
| `generate_chunks` | `true` | Capture/voxelize loaded chunks into the LOD store. Off = render-only (freeze the stored world) |
| `section_render_distance` | `16` | Section render distance |
| `service_threads` | auto | Worker thread count (defaults to ~⅔ CPU cores) |
| `use_environmental_fog` | `true` | Blend LOD with vanilla fog |
| `ssao_mode` | `auto` | SSAO quality (`auto` / `basic` / `off`) |

**Client** (`config/voxy-common-client.toml`):
| Option | Default | Description |
|---|---|---|
| `auto_generation_enabled` | `true` | Enable background chunk generation |
| `auto_generation_rate` | `2` | Target sections generated per tick |
| `lod_radius` | `256` | LOD render radius in sections |

**Server** (`serverconfig/voxy-common-server.toml`):
| Option | Default | Description |
|---|---|---|
| `lod_chunks_per_tick` | `16` | Max LOD packets sent to a player per tick |
| `lod_generation_rate_cap` | `8` | Server-enforced cap on client generation rate |
| `auto_generation_enabled` | `true` | Allow clients to generate and upload LOD |
| `lod_radius_max` | `4096` | Server ceiling on client LOD radius |
| `lod_send_on_join` | `true` | Send LOD manifest on player join |
| `max_transfer_queue_per_client` | `2048` | Per-client packet queue depth |

---

## Commands

| Command | Description |
|---|---|
| `/voxy regen` | Wipe and regenerate all stored LODs for the current dimension. Use it to clear dark / stale / corrupt LODs baked into the database — regeneration overwrites them. Race-free: auto-generation is paused for the duration of the off-thread wipe so the background DB scan can't re-mark the wiped columns as already-generated. |

---

## Requirements

- **Minecraft** 1.21.1
- **NeoForge** 21.1.230 or newer
- **Sodium** 0.6.13 (0.6.x) — **required** on the client. Voxy's renderer mixes into Sodium's chunk
  renderer; the mixins target 0.6.x internals, so Sodium **0.7+ is not supported** (excluded until the
  mixins are rewritten). Reese's Sodium Options and other Sodium add-ons are fine.
- **OpenGL 4.5** capable GPU
- **Iris** 1.8+ (optional) — when installed, LODs render through the active shader pack (detected via
  the reflective IrisApi v0); without Iris everything still works. Tested with Iris + Complementary
  Reimagined; other shader packs may work.

These minimums are enforced by the mod's dependency metadata — launching with an incompatible
NeoForge/Sodium version produces a clear NeoForge error rather than a crash.

---

## Recent Changes (this fork)

- **No terrain scramble** — unreadable stored block-state mappings map to a fixed STONE placeholder instead of a random block, so one corrupt entry can't remap large areas of stored LODs (previously seen as scrambled nether terrain)
- **No dark distant LODs** — force-loaded chunks are voxelized only after their lighting has settled (with a short fallback so a never-settling light state can't leave a gap; a deterministic light path is planned)
- **`/voxy regen` command** — wipe + regenerate the current dimension's LODs, race-free
- **Save-safe distant generation** — chunks force-loaded only to build LODs are never written to the world save, so distant generation can't bloat or hang it
- **No silently-skipped columns** — a column is never dropped; genuine load failures (outside the world border, or stuck far past normal worldgen) are logged as errors so they're visible and reportable
- **Fog edge fix** — environmental fog is pulled in one chunk so the hard LOD edge stays hidden

---

## Known Limitations

- No support provided — personal use port
- LOD data is not generated for dimensions other than the one the player is currently in
- Biome zoom smoothing at LOD level boundaries is not yet implemented (stub)
- Some highly modded block models may fall back to the particle icon texture in LOD
