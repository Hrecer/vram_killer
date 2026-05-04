# VRAM Killer

A VRAM optimization mod for Minecraft.

## License

**CC0 1.0 Universal (Public Domain)**

This project is dedicated to the public domain. You can freely use, modify, distribute, and incorporate this code in any project without any restrictions or attribution requirements.

See [LICENSE](LICENSE) for the full legal text.

## Implemented Features

The following features are **fully implemented and functional**:

### Texture Compression
- BC1 (DXT1) compression for opaque textures
- BC3 (DXT5) compression for textures with alpha channel
- BC5 compression for normal maps
- Multi-threaded compression pipeline
- Automatic format selection based on texture type

### Leak Detection
- Tracks texture creation and destruction
- Identifies orphaned textures (textures not properly released)
- Configurable detection thresholds
- Optional auto-cleanup of orphaned textures

### Texture Tracking
- Monitors texture memory usage estimates
- Hot/Warm/Cold zone classification for textures
- Access pattern tracking for eviction decisions

### VRAM Monitoring
- NVIDIA GPU support via `GL_NVX_gpu_memory_info`
- AMD GPU support via `GL_ATI_meminfo`
- Windows NVIDIA support via `nvidia-smi`
- Real-time VRAM usage display in debug overlay

### Debug Overlay
- F3 debug screen integration
- Shows VRAM usage percentage
- Displays compression statistics
- Shows texture zone distribution

## Known Limitations

Some originally planned features were not successfully implemented due to technical challenges:

- **Dynamic shadow resolution**: Calculated but not applied to actual rendering
- **Animation texture optimization**: Visibility tracking not connected to rendering pipeline
- **Atlas repacking**: Optimization analysis runs but results are not applied

## Requirements

- Minecraft 26.1.x
- Fabric Loader 0.18.4+
- Java 25+
- Fabric API
- Sodium 0.8.0+

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
- Implement incomplete features
- Add new compression formats
- Improve documentation
- Port to other Minecraft versions

No attribution required, but appreciated.

## Disclaimer

This mod is provided as-is without any warranty. The author makes no guarantees about its effectiveness or stability. Use at your own risk.

---

*If this code is useful to you, feel free to use it in any way you like. No restrictions.*
