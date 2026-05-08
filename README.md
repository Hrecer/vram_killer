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
---

*Public domain (CC0). No restrictions.*
