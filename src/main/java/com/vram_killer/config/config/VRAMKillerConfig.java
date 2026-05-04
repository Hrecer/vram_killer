package com.vram_killer.config.config;

import com.vram_killer.config.annotation.ClientOnly;
import com.vram_killer.config.annotation.Range;
import com.vram_killer.config.annotation.SubCategory;
import com.vram_killer.config.config.watcher.ConfigFileWatcher;
import com.vram_killer.VRAMKiller;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class VRAMKillerConfig {
    private static final Map<String, VRAMKillerConfig> CONFIG_REGISTRY = new ConcurrentHashMap<>();
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    private final String modId;
    private final Class<?> configClass;
    private final Map<String, ConfigValue<?>> configCache = new ConcurrentHashMap<>();
    private final Map<String, FieldAccessor> fieldAccessors = new ConcurrentHashMap<>();
    private final Object configLock = new Object();
    private final AtomicReference<Object> configRef = new AtomicReference<>();
    private final List<Runnable> reloadListeners = new ArrayList<>();
    private String configFileName;
    private boolean isClient = true;
    private boolean registered = false;
    private ConfigParser configParser;
    private ConfigFileWatcher fileWatcher;

    public VRAMKillerConfig(String modId, Class<?> configClass) {
        this.modId = modId;
        this.configClass = configClass;
        this.configFileName = modId + "_config";

        this.isClient = detectClientEnvironment();

        validateModIdOwnership(modId);
        initializeConfigInstance();
        registerShutdownHook();
    }

    public static VRAMKillerConfig register(String modId, Class<?> configClass) {
        if (CONFIG_REGISTRY.containsKey(modId)) {
            throw new IllegalStateException("Config already registered for mod: " + modId);
        }

        VRAMKillerConfig config = new VRAMKillerConfig(modId, configClass);
        config.register();
        CONFIG_REGISTRY.put(modId, config);

        return config;
    }

    public static VRAMKillerConfig getConfig(String modId) {
        VRAMKillerConfig config = CONFIG_REGISTRY.get(modId);
        if (config == null) {
            VRAMKiller.LOGGER.warn("Config not found for mod: {}", modId);
        }
        return config;
    }

    public static Map<String, VRAMKillerConfig> getAllConfigs() {
        return new ConcurrentHashMap<>(CONFIG_REGISTRY);
    }

    private static Object getTypeDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == String.class) return "";
        return null;
    }

    private static <T extends Number> T validateRange(Field field, T value, T defaultValue) {
        Range range = field.getAnnotation(Range.class);
        if (range == null) {
            return value;
        }

        double numValue = value.doubleValue();
        double min = range.min();
        double max = range.max();

        if (numValue < min || numValue > max) {
            VRAMKiller.LOGGER.warn(
                    "Config value {} = {} is out of range [{}, {}], using default: {}",
                    field.getName(), numValue, min, max, defaultValue
            );
            return defaultValue;
        }

        return value;
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() ||
                type == Boolean.class ||
                type == Integer.class ||
                type == Long.class ||
                type == Double.class ||
                type == Float.class ||
                type == String.class ||
                type.isEnum() ||
                type == List.class;
    }

    private static String formatFieldNameAsComment(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return fieldName;

        StringBuilder result = new StringBuilder();
        result.append(Character.toUpperCase(fieldName.charAt(0)));

        for (int i = 1; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append(' ').append(c);
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private boolean detectClientEnvironment() {
        try {
            Object envType = net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType();
            String envStr = envType.toString();
            return "CLIENT".equals(envStr);
        } catch (Exception e) {
            VRAMKiller.LOGGER.warn("Failed to detect environment, assuming server", e);
            return false;
        }
    }

    private void validateModIdOwnership(String modId) {
        if (CONFIG_REGISTRY.containsKey(modId)) {
            throw new SecurityException("Mod ID already registered: " + modId);
        }
    }

    private void initializeConfigInstance() {
        try {
            Object configInstance = configClass.getDeclaredConstructor().newInstance();
            configRef.set(configInstance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create config instance for mod: " + modId, e);
        }
    }

    private void registerShutdownHook() {
        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                CONFIG_REGISTRY.values().forEach(VRAMKillerConfig::stop);
                CONFIG_REGISTRY.clear();
            }));
        }
    }

    public VRAMKillerConfig register() {
        if (registered) {
            VRAMKiller.LOGGER.warn("Config for mod {} is already registered!", modId);
            return this;
        }

        registered = true;

        try {
            Object newConfig = rebuildConfigObject();
            configRef.set(newConfig);

            ConfigValidator.validateConfig(newConfig);
            VRAMKiller.LOGGER.info("Default configuration validation passed for mod: {}", modId);
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Default configuration validation failed for mod {}: {}", modId, e.getMessage());
            throw new RuntimeException("Invalid default configuration for mod: " + modId, e);
        }

        cacheFieldAccessors();
        initializeConfigSystem();
        VRAMKiller.LOGGER.info("Config registered successfully for mod: {} (environment: {})", modId, isClient ? "client" : "server");
        return this;
    }

    public void reload() {
        synchronized (configLock) {
            Path configPath = getConfigPath();

            try {
                configCache.values().forEach(ConfigValue::refresh);
                configCache.clear();

                if (configParser != null) {
                    configParser.load();
                    if (!ConfigMigration.migrateConfig(configPath, configParser, configClass)) {
                        throw new RuntimeException("Config migration failed");
                    }
                }

                Object newConfig = rebuildConfigObject();
                configRef.set(newConfig);

                ConfigValidator.validateConfig(newConfig);
                VRAMKiller.LOGGER.info("Configuration reloaded successfully for mod: {}", modId);

            } catch (Exception e) {
                VRAMKiller.LOGGER.error("Failed to reload configuration for mod: {}", modId, e);
                throw new RuntimeException("Config reload failed", e);
            }
        }
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                VRAMKiller.LOGGER.error("Config reload listener failed", e);
            }
        }
    }

    public void addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
    }

    @SuppressWarnings("unchecked")
    public <T> T get() {
        return (T) configRef.get();
    }

    public void stop() {
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        configCache.clear();
        fieldAccessors.clear();
    }

    public void save() {
        synchronized (configLock) {
            Path configPath = getConfigPath();

            try {
                String configContent = generateConfigFile();
                Files.write(configPath, configContent.getBytes());
                VRAMKiller.LOGGER.debug("Configuration saved for mod: {}", modId);
            } catch (IOException e) {
                VRAMKiller.LOGGER.error("Failed to save configuration for mod: {}", modId, e);
                throw new RuntimeException("Config save failed", e);
            }
        }
    }

    private void cacheFieldAccessors() {
        try {
            cacheFieldsRecursive(configClass, "");
            VRAMKiller.LOGGER.debug("Cached {} field accessors for mod: {}", fieldAccessors.size(), modId);
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Failed to cache field accessors for mod: {}", modId, e);
        }
    }

    private void initializeConfigSystem() {
        VRAMKiller.LOGGER.info("Starting config system initialization");
        Path configPath = getConfigPath();
        VRAMKiller.LOGGER.info("Config path: {}", configPath);
        if (!Files.exists(configPath)) {
            createDefaultConfig(configPath);
            VRAMKiller.LOGGER.info("Config file not found, creating: {}", configPath);
        } else {
            VRAMKiller.LOGGER.info("Config file already exists");
        }
        configParser = new ConfigParser(configPath);

        if (!ConfigMigration.migrateConfig(configPath, configParser, configClass)) {
            throw new RuntimeException("Initial config migration failed");
        }

        Object newConfig = rebuildConfigObject();
        configRef.set(newConfig);

        fileWatcher = new ConfigFileWatcher(configPath, this::reload);
        fileWatcher.start();
    }

    private Object rebuildConfigObject() {
        try {
            Object newConfig = configClass.getDeclaredConstructor().newInstance();
            configureObjectRecursive(newConfig, "");
            ConfigValidator.validateConfig(newConfig);
            VRAMKiller.LOGGER.debug("Configuration object rebuilt and validated for mod: {}", modId);
            return newConfig;
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Failed to rebuild configuration object for mod: {}", modId, e);
            throw new RuntimeException("Configuration rebuild failed", e);
        }
    }

    private void cacheFieldsRecursive(Class<?> clazz, String prefix) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            String fullPath = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();

            if (field.isAnnotationPresent(ClientOnly.class) && !isClient) {
                continue;
            }

            if (!field.isAnnotationPresent(SubCategory.class)) {
                MethodHandle getter = lookup.unreflectGetter(field);
                MethodHandle setter = lookup.unreflectSetter(field);

                fieldAccessors.put(fullPath, new MethodHandleFieldAccessor(
                        getter, setter, field.getType(), () -> {
                    try {
                        Object defaultInstance = field.getDeclaringClass().getDeclaredConstructor().newInstance();
                        return field.get(defaultInstance);
                    } catch (Exception e) {
                        return getTypeDefaultValue(field.getType());
                    }
                }
                ));
            } else {
                cacheFieldsRecursive(field.getType(), fullPath);
            }
        }
    }

    private void configureObjectRecursive(Object obj, String prefix) throws Exception {
        for (Field field : obj.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(ClientOnly.class) && !isClient) {
                continue;
            }

            String fieldPath = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();

            if (field.isAnnotationPresent(SubCategory.class)) {
                Object subObj = field.getType().getDeclaredConstructor().newInstance();
                configureObjectRecursive(subObj, fieldPath);
                field.set(obj, subObj);
            } else {
                if (isSimpleType(field.getType())) {
                    FieldAccessor accessor = fieldAccessors.get(fieldPath);
                    if (accessor != null) {
                        Object defaultValue = accessor.getDefaultValue();
                        ConfigValue<?> configValue = getCachedConfigValue(fieldPath, field.getType(), defaultValue);
                        Object value = configValue.get();

                        if (value instanceof Number) {
                            value = validateRange(field, (Number) value, (Number) defaultValue);
                        }

                        accessor.setValue(obj, value);
                    } else {
                        VRAMKiller.LOGGER.warn("No accessor found for field path: {}", fieldPath);
                    }
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConfigValue<?> getCachedConfigValue(String key, Class<?> type, Object defaultValue) {
        return configCache.computeIfAbsent(key, k -> createConfigValueSupplier(key, type, defaultValue));
    }

    private ConfigValue<?> createConfigValueSupplier(String key, Class<?> type, Object defaultValue) {
        if (type == boolean.class || type == Boolean.class) {
            return new ConfigValue<>(configParser.getBoolean(key, (Boolean) defaultValue));
        } else if (type == int.class || type == Integer.class) {
            return new ConfigValue<>(configParser.getInt(key, (Integer) defaultValue));
        } else if (type == long.class || type == Long.class) {
            return new ConfigValue<>(configParser.getLong(key, (Long) defaultValue));
        } else if (type == double.class || type == Double.class) {
            return new ConfigValue<>(configParser.getDouble(key, (Double) defaultValue));
        } else if (type == String.class) {
            return new ConfigValue<>(configParser.getString(key, (String) defaultValue));
        } else if (type.isEnum()) {
            return new ConfigValue<>(configParser.getEnum(key, (Enum) defaultValue));
        } else if (List.class.isAssignableFrom(type)) {
            return new ConfigValue<>(configParser.getStringList(key, (List<String>) defaultValue));
        } else {
            VRAMKiller.LOGGER.warn("Unsupported configuration type: {} for key: {} in mod: {}", type, key, modId);
            return new ConfigValue<>(() -> defaultValue);
        }
    }

    private void createDefaultConfig(Path configPath) {
        try {
            String configContent = generateConfigFile();
            Files.createDirectories(configPath.getParent());
            Files.write(configPath, configContent.getBytes());
            VRAMKiller.LOGGER.info("Default configuration created for mod {} at: {}", modId, configPath);
        } catch (IOException e) {
            VRAMKiller.LOGGER.error("Failed to create default configuration for mod: {}", modId, e);
        }
    }

    private Path getConfigPath() {
        return Paths.get("config", modId, configFileName + ".toml");
    }

    private String generateConfigFile() {
        VRAMKiller.LOGGER.info("Starting config file generation for mod: {}", modId);
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(modId).append(" Configuration\n");
        sb.append("# Generated by VRAMKillerConfig\n");
        sb.append("# Environment: ").append(isClient ? "client" : "server").append("\n");
        sb.append("# Client-only sections will not be generated on server side\n");
        sb.append("# Edit this file and it will be automatically reloaded\n");
        sb.append("\n");

        try {
            Object configObj = configRef.get();
            for (Field sectionField : configClass.getDeclaredFields()) {
                sectionField.setAccessible(true);
                if (sectionField.isAnnotationPresent(ClientOnly.class) && !isClient) {
                    continue;
                }

                Object section = sectionField.get(configObj);
                String sectionName = sectionField.getName();

                generateSectionContent(sb, section, sectionName, "");
            }
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Failed to generate configuration content for mod: {}", modId, e);
        }
        return sb.toString();
    }

    private void generateSectionContent(StringBuilder sb, Object section, String sectionPath, String indent) throws Exception {
        if (section == null) return;

        if (!sectionPath.isEmpty()) {
            sb.append(indent).append("[").append(sectionPath).append("]\n");
        }

        for (Field field : section.getClass().getDeclaredFields()) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(ClientOnly.class) && !isClient) {
                continue;
            }

            String fieldName = field.getName();
            Object value = field.get(section);

            if (field.isAnnotationPresent(SubCategory.class)) {
                String newSectionPath = sectionPath.isEmpty() ? fieldName : sectionPath + "." + fieldName;
                generateSectionContent(sb, value, newSectionPath, indent);
            } else {
                if (isSimpleType(field.getType())) {
                    sb.append(indent).append("## ").append(formatFieldNameAsComment(fieldName)).append("\n");
                    sb.append(indent).append(fieldName).append(" = ");

                    if (value instanceof Boolean) {
                        sb.append(value.toString().toLowerCase());
                    } else if (value instanceof String) {
                        sb.append("\"").append(value).append("\"");
                    } else if (value instanceof Enum) {
                        sb.append("\"").append(((Enum<?>) value).name()).append("\"");
                    } else if (value instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> list = (List<String>) value;
                        sb.append("[");
                        for (int i = 0; i < list.size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append("\"").append(list.get(i)).append("\"");
                        }
                        sb.append("]");
                    } else if (value instanceof Long) {
                        sb.append(value);
                    } else {
                        sb.append(value);
                    }
                    sb.append("\n\n");
                }
            }
        }

        if (!sectionPath.isEmpty()) {
            sb.append("\n");
        }
    }

    public VRAMKillerConfig filename(String name) {
        configFileName = name;
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        initializeConfigSystem();
        return this;
    }

    public String getModId() {
        return modId;
    }

    public Class<?> getConfigClass() {
        return configClass;
    }

    public boolean isClientEnvironment() {
        return isClient;
    }

    private interface FieldAccessor {
        Object getValue(Object obj) throws Exception;

        void setValue(Object obj, Object value) throws Exception;

        Object getDefaultValue() throws Exception;

        Class<?> getType();
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }

    private static class MethodHandleFieldAccessor implements FieldAccessor {
        private final MethodHandle getter;
        private final MethodHandle setter;
        private final Class<?> type;
        private final SupplierWithException<Object> defaultValueSupplier;

        public MethodHandleFieldAccessor(MethodHandle getter, MethodHandle setter,
                                         Class<?> type, SupplierWithException<Object> defaultValueSupplier) {
            this.getter = getter;
            this.setter = setter;
            this.type = type;
            this.defaultValueSupplier = defaultValueSupplier;
        }

        @Override
        public Object getValue(Object obj) throws Exception {
            try {
                return getter.invoke(obj);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void setValue(Object obj, Object value) throws Exception {
            try {
                Object convertedValue = convertValue(value, type);
                int parameterCount = setter.type().parameterCount();
                if (parameterCount == 1) {
                    setter.invoke(convertedValue);
                } else if (parameterCount == 2) {
                    setter.invoke(obj, convertedValue);
                } else {
                    throw new RuntimeException("Unexpected setter parameter count: " + parameterCount);
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        private Object convertValue(Object value, Class<?> targetType) {
            if (value == null) return getTypeDefaultValue(targetType);
            if (targetType.isInstance(value)) {
                return value;
            }

            try {
                if (targetType == boolean.class || targetType == Boolean.class) {
                    if (value instanceof Boolean) return value;
                    if (value instanceof String) return Boolean.parseBoolean((String) value);
                    if (value instanceof Number) return ((Number) value).intValue() != 0;
                    return false;
                } else if (targetType == int.class || targetType == Integer.class) {
                    if (value instanceof Integer) return value;
                    if (value instanceof Number) return ((Number) value).intValue();
                    if (value instanceof String) return Integer.parseInt((String) value);
                    return 0;
                } else if (targetType == double.class || targetType == Double.class) {
                    if (value instanceof Double) return value;
                    if (value instanceof Number) return ((Number) value).doubleValue();
                    if (value instanceof String) return Double.parseDouble((String) value);
                    return 0.0;
                } else if (targetType == long.class || targetType == Long.class) {
                    if (value instanceof Long) return value;
                    if (value instanceof Number) return ((Number) value).longValue();
                    if (value instanceof String) return Long.parseLong((String) value);
                    return 0L;
                } else if (targetType == float.class || targetType == Float.class) {
                    if (value instanceof Float) return value;
                    if (value instanceof Number) return ((Number) value).floatValue();
                    if (value instanceof String) return Float.parseFloat((String) value);
                    return 0.0f;
                } else if (targetType == String.class) {
                    return value.toString();
                } else if (targetType.isEnum()) {
                    if (value instanceof String) {
                        try {
                            return Enum.valueOf((Class<Enum>) targetType, ((String) value).trim().toUpperCase());
                        } catch (IllegalArgumentException e) {
                            VRAMKiller.LOGGER.warn("Invalid enum value '{}' for type {}, using first enum value", value, targetType.getSimpleName());
                            return targetType.getEnumConstants()[0];
                        }
                    }
                }
            } catch (Exception e) {
                VRAMKiller.LOGGER.warn("Failed to convert value '{}' to type {}, using default", value, targetType.getSimpleName(), e);
            }

            return getTypeDefaultValue(targetType);
        }

        @Override
        public Object getDefaultValue() throws Exception {
            return defaultValueSupplier.get();
        }

        @Override
        public Class<?> getType() {
            return type;
        }
    }
}
