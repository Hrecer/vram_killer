# VRAM Killer

A VRAM optimization mod for Minecraft.

## License

**CC0 1.0 Universal (Public Domain)**

This project is dedicated to the public domain. You can freely use, modify, distribute, and incorporate this code in any project without any restrictions or attribution requirements.

See [LICENSE](LICENSE) for the full legal text.

## Features

### Texture Compression
- BC1 (DXT1) compression for opaque textures
- BC3 (DXT5) compression for textures with alpha channel
- BC5 compression for normal maps
- BC7 high-quality compression with Mode 6 encoding
- Multi-threaded compression pipeline
- Automatic format selection based on texture type
- Disk cache for compressed textures

### Texture Scheduling
- Hot/Warm/Cold zone classification for textures
- Path-based zone assignment (blocks/items → Hot, entities/mobs → Warm)
- Dynamic eviction thresholds based on VRAM pressure
- Intel GPU aware eviction (conservative thresholds for shared memory)
- Grace period for newly loaded textures

### VRAM Monitoring
- NVIDIA GPU support via `nvidia-smi` (Windows)
- NVIDIA GPU support via `GL_NVX_gpu_memory_info`
- AMD GPU support via `GL_ATI_meminfo`
- Background monitoring with failure backoff

### Leak Detection
- Tracks texture creation and destruction
- Identifies orphaned textures (textures not properly released)
- Configurable detection thresholds
- Optional auto-cleanup of orphaned textures

### Debug Overlay
- F3 debug screen integration (press F3+V to toggle VRAM panel)
- Shows VRAM usage percentage and bar
- Displays compression statistics
- Shows texture zone distribution
- Leak detection status

### Compatibility
- Sodium integration
- Iris shader pack support
- Dynamic shadow resolution optimization for Iris

## Requirements

- Minecraft 26.1.x
- Fabric Loader 0.19.2+
- Java 25+
- Fabric API
- Sodium 0.8.0+ (recommended)
- Iris (optional, for shader support)

## Configuration

Edit `config/vram_killer/vram_killer_config.toml`:

```toml
[Compression]
enabled = true
thread_count = 4
color_format = "BC7"      # BC1, BC3, BC5, BC7
normal_format = "BC5"
background_format = "BC1"

[Cache]
enabled = true
max_size_mb = 2048

[Scheduler]
enabled = true
cold_zone_delay_seconds = 30
max_vram_usage_percent = 90

[LeakDetection]
enabled = true
check_interval_seconds = 300
auto_cleanup = false
```

## Building

```bash
./gradlew build
```

The output JAR will be in `build/libs/`.

## Contributing

This project is in the public domain (CC0). Feel free to:

- Fix bugs
- Add new compression formats
- Improve documentation
- Port to other Minecraft versions

No attribution required, but appreciated.

## Disclaimer

This mod is provided as-is without any warranty. The author makes no guarantees about its effectiveness or stability. Use at your own risk.

---

*If this code is useful to you, feel free to use it in any way you like. No restrictions.*
