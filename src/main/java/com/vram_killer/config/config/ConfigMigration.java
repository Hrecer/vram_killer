package com.vram_killer.config.config;

import com.vram_killer.config.annotation.ConfigVersion;
import com.vram_killer.VRAMKiller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ConfigMigration {
    public static boolean migrateConfig(Path configPath, ConfigParser parser, Class<?> configClass) {
        try {
            int currentVersion = getCurrentConfigVersion(configClass);
            int fileVersion = detectConfigVersion(parser);

            if (fileVersion < currentVersion) {
                VRAMKiller.LOGGER.info("Migrating config from version {} to {}", fileVersion, currentVersion);
                return performMigration(configPath, parser, fileVersion, currentVersion, configClass);
            }
            return true;
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Config migration failed: {}", e.getMessage());
            return false;
        }
    }

    private static int getCurrentConfigVersion(Class<?> configClass) {
        try {
            ConfigVersion version = configClass.getAnnotation(ConfigVersion.class);
            return version != null ? version.value() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private static int detectConfigVersion(ConfigParser parser) {
        if (parser.hasKey("config_version")) {
            return parser.getInt("config_version", 1).get();
        }
        return 1;
    }

    private static boolean performMigration(Path configPath, ConfigParser parser, int fromVersion, int toVersion, Class<?> configClass) throws IOException {
        Map<String, String> migratedValues = new HashMap<>(parser.configValues);

        try {
            for (int version = fromVersion; version < toVersion; version++) {
                if (!migrateFromVersion(migratedValues, version, configClass)) {
                    return false;
                }
            }

            migratedValues.put("config_version", String.valueOf(toVersion));
            StringBuilder newConfig = new StringBuilder();
            newConfig.append("# Configuration\n");
            newConfig.append("# Migrated from version ").append(fromVersion).append(" to ").append(toVersion).append("\n");
            newConfig.append("config_version = ").append(toVersion).append("\n\n");

            for (Map.Entry<String, String> entry : migratedValues.entrySet()) {
                if (!entry.getKey().equals("config_version")) {
                    newConfig.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            }

            Files.write(configPath, newConfig.toString().getBytes());
            VRAMKiller.LOGGER.info("Config migration completed successfully");
            return true;
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Migration failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean migrateFromVersion(Map<String, String> values, int fromVersion, Class<?> configClass) {
        try {
            String methodName = "migrateFromV" + fromVersion;
            Method migrationMethod = configClass.getMethod(methodName, Map.class);
            migrationMethod.invoke(null, values);
            VRAMKiller.LOGGER.debug("Applied migration from version {}", fromVersion);
            return true;
        } catch (NoSuchMethodException e) {
            return applyDefaultMigration(values, fromVersion);
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Failed to apply custom migration for version {}: {}", fromVersion, e.getMessage());
            return false;
        }
    }

    private static boolean applyDefaultMigration(Map<String, String> values, int fromVersion) {
        return true;
    }
}
