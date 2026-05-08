# VRAM Killer

Minecraft Fabric mod for runtime VRAM optimization via **RGB5_A1 texture format conversion**.

## License

**CC0 1.0 Universal (Public Domain)**

Dedicated to the public domain. Free to use, modify, distribute without restrictions.

## Features

### RGBA5551 Texture Conversion (Core Feature)
- Converts textures with **binary alpha** (all pixels α=0 or α=255) to **16-bit RGB5_A1 format**
- **50% VRAM savings** per converted texture (32bpp → 16bpp)
- Uses standard OpenGL `GL_RGB5_A1` format — fully compatible with MC 1.21+ GpuDevice
- Automatic binary-alpha detection — skips textures with semi-transparent pixels
- Inline conversion during texture upload — no async pipeline needed

**Supported Texture Types:**
- Block textures (atlas-based)
- Item textures
- Entity/Mob sprites (via `SpriteContents.uploadFirstFrame()` hook)
- GUI elements with opaque/transparency-only regions
- Environment textures (sky, end sky, etc.)
- Particle textures

**Excluded (correctly skipped):**
- Textures with gradient/semi-transparent alpha (e.g., glass, stained glass)
- Font glyphs
- UI icons with anti-aliased edges

### Texture Caching System
- Pre-upload pixel caching via `TextureContentsMixin`
- Multi-point sampling validation (512 sample points, <2% non-zero threshold)
- Smart zero-pixel detection for top-transparent UI icons (edition.png, minecraft.png)
- Sprite pixel capture at correct timing (`uploadFirstFrame` for async-loaded entities)

### Texture Scheduling
- Hot/Warm/Cold zone classification for textures
- Path-based zone assignment (blocks/items → Hot, entities → Warm)
- Dynamic eviction thresholds based on VRAM pressure
- Grace period for newly loaded textures (30s default)

### VRAM Monitoring
- NVIDIA GPU support via `nvidia-smi` (Windows API)
- AMD GPU support via `GL_ATI_meminfo`
- Background monitoring with failure backoff

### Leak Detection
- Tracks texture creation and destruction
- Identifies orphaned textures
- Configurable detection thresholds (default: 300s interval)

### Debug Overlay
- **F3+V** to toggle VRAM stats panel in F3 debug screen
- Shows:
  - Converted/Skipped texture counts
  - Total VRAM saved (MB)
  - Conversion statistics

### Compatibility
- ✅ **Sodium** 0.8.9+ (automatic detection)
- ✅ **Iris** shaders (shadow resolution optimization)
- ✅ **MC 1.21+ GpuDevice** (Vulkan-like rendering pipeline)

## Why RGBA5551 instead of BC7?

| Feature | BC7/BPTC | RGBA5551 (RGB5_A1) |
|---------|----------|---------------------|
| Compression Ratio | 4:1 | 2:1 (50% savings) |
| Quality Loss | Visible artifacts on detailed textures | Minimal (8→5 bit color, 8→1 bit alpha) |
| GpuDevice Compatible | ❌ Causes state desync | ✅ Standard GL format |
| Alpha Support | Full 8-bit | Binary only (0 or 255) |
| Implementation Complexity | Native C library (bc7enc) | Pure Java (~130 lines) |
| Dependencies | JNA, MinGW-w64, GCC | None (LWJGL GL11/GL12) |

BC7 was **proven incompatible** with Minecraft 1.21+'s GpuDevice rendering pipeline. The `glCompressedTexImage2D` call bypasses GpuDevice's internal state management, causing:
- All blocks becoming transparent
- All GUI becoming transparent
- Only entities visible

**RGBA5551 is the correct solution** because it uses `glTexImage2D` with a standard OpenGL internal format that GpuDevice understands natively.

## Requirements

- **Minecraft**: 26.1.x (MC 1.21.5+)
- **Fabric Loader**: 0.19.2+
- **Java**: 25+
- **Sodium**: 0.8.0+ (recommended, auto-detected)
- **Iris**: optional (for shader support)

## Configuration

Edit `config/vram_killer/vram_killer_config.toml`:

```toml
[Compression]
enabled = true              # Master switch for RGBA5551 conversion
rgb5a1Conversion = true     # Enable RGB5_A1 format conversion
minTextureSize = 4          # Minimum texture dimension (skip smaller)
asyncProcessing = true      # Enable async processing where possible

[Cache]
enabled = true              # Enable pixel data cache
max_size_mb = 2048          # Max cache size in MB
enableColdZone = true       # Enable cold zone tracking

[Scheduler]
enabled = true              # Enable texture scheduling
cold_zone_delay_seconds = 30  # Delay before moving to cold zone
max_vram_usage_percent = 90   # VRAM usage threshold for eviction

[LeakDetection]
enabled = true              # Enable leak detection
check_interval_seconds = 300  # Check interval (5 minutes)
max_orphan_reports = 10      # Max orphan reports to keep
auto_cleanup = false         # Auto-cleanup orphans (risky)

[Animation]
enabled = true               # Enable animation frame skipping
max_skipped_frames = 5       # Max frames to skip for off-screen animations

[Shadow]                     # Iris shader optimization
resolutionMin = 1024         # Min shadow resolution
resolutionMax = 4096         # Max shadow resolution
dynamicResolution = true     # Adjust based on VRAM pressure

[Mipmap]
disableGUIMipmaps = true     # Disable mipmaps for GUI (saves VRAM)
disableFontMipmaps = true    # Disable mipmaps for fonts
particleMipmapLimit = 1      # Mipmap limit for particles
```

## Architecture

### Core Components

```
src/
├── main/
│   ├── java/com/vram_killer/
│   │   ├── config/
│   │   │   └── VRAMKillerConfigBase.java    # Configuration constants
│   │   └── VRAMKiller.java                   # Mod entrypoint (common)
│   └── resources/
│       ├── fabric.mod.json                    # Mod metadata
│       └── vram_killer.mixins.json           # Common mixins
├── client/
│   ├── java/com/vram_killer/client/
│   │   ├── render/
│   │   │   └── TextureUploadInterceptor.java  # ★ CORE: RGBA5551 converter
│   │   ├── mixin/
│   │   │   ├── client/
│   │   │   │   ├── TextureContentsMixin.java    # Pixel cache provider
│   │   │   │   ├── SpriteContentsMixin.java     # Entity sprite capture
│   │   │   │   ├── ReloadableTextureMixin.java  # Upload hook
│   │   │   │   └── ...                          # Other client hooks
│   │   │   └── atlas/
│   │   │       └── SpriteAtlasTextureMixin.java # Atlas tracking
│   │   ├── VRAMKillerClient.java                # Client entrypoint
│   │   ├── VRAMManager.java                     # VRAM coordination
│   │   └── hud/
│   │       └── VRAMDebugEntry.java              # F3 overlay
│   └── resources/
│       └── vram_killer.client.mixins.json      # Client mixins
```

### Data Flow

```
Texture Load Request
        ↓
[TextureContentsMixin] ← Captures ABGR pixels at load()
        ↓ converts to RGBA
        ↓ validates (multi-point sampling, zero-pixel check)
        ↓ caches in PixelDataCache
        ↓
[ReloadableTextureMixin.doLoad() RETURN]
        ↓ retrieves cached pixels
        ↓ calls convertToRGB5A1()
        ↓
[TextureUploadInterceptor.convertToRGB5A1()]
        ↓ checks binary alpha (all α ∈ {0, 255})
        ↓ if binary: converts to short[] (RGB5_A1 packed)
        ↓ uploads via glTexImage2D(GL_RGB5_A1, UNSIGNED_SHORT_5_5_5_1)
        ↓ tracks stats (convertedCount, savedBytes)
        ↓ returns success/failure
```

For entity textures (async-loaded):
```
[SpriteContentsMixin.uploadFirstFrame() HEAD]
        ↓ captures originalImage pixels (100% ready here)
        ↓ caches as "sprite:WxH:hash"
        ↓ later picked up by ReloadableTextureMixin
```

## Building

```bash
# Build JAR
./gradlew build

# Run client (for testing)
./gradlew runClient
```

Output: `build/libs/vram_killer-1.1.0.jar`

## Testing & Verification

1. **Check logs** for `[VRAM-5551] ✅ CONVERTED:` messages
2. **Press F3+V** to see debug overlay with conversion stats
3. **Expected output:**
   ```
   [VRAM-5551] === Stats === Converted: X, Skipped: Y, Saved: Z.Z MB
   ```

4. **Visual verification:** No rainbow/garbled/transparent artifacts on blocks, items, entities

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| No textures converted | All have semi-transparent alpha | Normal — RGBA5551 only handles binary alpha |
| Rainbow/garbled textures | Channel swap bug | Check ABGR→RGBA mapping in Mixin |
| Blocks transparent | Old BC7 code active | Ensure bc7enc files deleted |
| GL_INVALID_ENUM errors | Format not supported | Check GL_RGB5_A1 availability |
| Low conversion count | Most MC textures use gradient alpha | Expected behavior |

## Version History

- **1.1.0** — RGBA5551 fully functional: 3266+ textures converted, 4.8MB+ VRAM saved per session, zero crashes
- **1.0.2x** — BC7 compression (removed: GpuDevice incompatible)
- **1.0.1x** — Initial BCn pipeline (experimental)

## Disclaimer

Provided as-is without warranty. Use at your own risk.

Tested on: NVIDIA RTX 4070 Laptop, Minecraft 26.1, Sodium 0.8.9, Iris 1.10.9

---

*Public domain (CC0). No restrictions.*
