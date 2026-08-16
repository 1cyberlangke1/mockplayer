package com.mockplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件读写（原版自带 Gson，零第三方依赖，YACL 缺席也能用）。
 *
 * 输入：config/mockplayer.json（玩家手改）
 * 行为：缺字段补默认、非法值（类型错/越界）回退默认、保存原子化（临时文件 + 改名）
 * 输出：ModConfig；文件损坏时整体回退默认，绝不崩客户端
 */
public final class ModConfigIO {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModConfigIO() {
    }

    /**
     * 从 JSON 文件加载配置。
     *
     * @param file 配置文件路径（不存在 = 全新默认配置）
     * @return 规范化后的配置（缺字段/非法值全部回退默认）
     */
    public static ModConfig load(Path file) {
        ModConfig config = new ModConfig();
        if (!Files.isRegularFile(file)) {
            return config;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            config.setChatHistoryLimit(readInt(root, "chatHistoryLimit",
                    ModConfig.DEFAULT_CHAT_HISTORY_LIMIT,
                    ModConfig.MIN_CHAT_HISTORY_LIMIT, ModConfig.MAX_CHAT_HISTORY_LIMIT));
            config.setSoundLogLimit(readInt(root, "soundLogLimit",
                    ModConfig.DEFAULT_SOUND_LOG_LIMIT,
                    ModConfig.MIN_SOUND_LOG_LIMIT, ModConfig.MAX_SOUND_LOG_LIMIT));
            config.setParticleLogLimit(readInt(root, "particleLogLimit",
                    ModConfig.DEFAULT_PARTICLE_LOG_LIMIT,
                    ModConfig.MIN_PARTICLE_LOG_LIMIT, ModConfig.MAX_PARTICLE_LOG_LIMIT));
            config.setEventCacheSize(readInt(root, "eventCacheSize",
                    ModConfig.DEFAULT_EVENT_CACHE_SIZE,
                    ModConfig.MIN_EVENT_CACHE_SIZE, ModConfig.MAX_EVENT_CACHE_SIZE));
            config.setEventSummaryMaxLength(readInt(root, "eventSummaryMaxLength",
                    ModConfig.DEFAULT_EVENT_SUMMARY_MAX_LENGTH,
                    ModConfig.MIN_EVENT_SUMMARY_MAX_LENGTH, ModConfig.MAX_EVENT_SUMMARY_MAX_LENGTH));
            config.setEventTickSampleInterval(readInt(root, "eventTickSampleInterval",
                    ModConfig.DEFAULT_EVENT_TICK_SAMPLE_INTERVAL,
                    ModConfig.MIN_EVENT_TICK_SAMPLE_INTERVAL, ModConfig.MAX_EVENT_TICK_SAMPLE_INTERVAL));
            config.setEventMoveSampleDistance(readDouble(root, "eventMoveSampleDistance",
                    ModConfig.DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE,
                    ModConfig.MIN_EVENT_MOVE_SAMPLE_DISTANCE, ModConfig.MAX_EVENT_MOVE_SAMPLE_DISTANCE));
            config.setCommands(readCommands(root));
            // 假人显示三态（新字段；旧布尔 debugOverlayEnabled 已废弃，读不到不影响）
            config.setDebugOverlayMode(readEnum(root, "debugOverlayMode", RenderMode.F3_ONLY));
            // 寻路段（行为项归 baritone 全局 Settings/settings.txt；这里只留总开关 + 渲染三态）
            config.setNavigateEnabled(readBool(root, "navigateEnabled",
                    ModConfig.DEFAULT_NAVIGATE_ENABLED));
            config.setNavigateRenderMode(readEnum(root, "navigateRenderMode", RenderMode.F3_ONLY));
            // GUI 开关：缺失/非布尔 → 默认 true
            if (root.has("guiEnabled") && root.get("guiEnabled").isJsonPrimitive()
                    && root.get("guiEnabled").getAsJsonPrimitive().isBoolean()) {
                config.setGuiEnabled(root.get("guiEnabled").getAsBoolean());
            }
            if (root.has("guiOpacity") && root.get("guiOpacity").isJsonPrimitive()) {
                try {
                    config.setGuiOpacity(ModConfig.normalizeGuiOpacity(
                            root.get("guiOpacity").getAsFloat()));
                } catch (RuntimeException e) {
                    config.setGuiOpacity(ModConfig.DEFAULT_GUI_OPACITY);
                }
            }
            config.setGuiBlur(readInt(root, "guiBlur",
                    ModConfig.DEFAULT_GUI_BLUR, ModConfig.MIN_GUI_BLUR, ModConfig.MAX_GUI_BLUR));
            config.setFakePlayerChunkRadius(readInt(root, "fakePlayerChunkRadius",
                    ModConfig.DEFAULT_FAKE_PLAYER_CHUNK_RADIUS,
                    ModConfig.MIN_FAKE_PLAYER_CHUNK_RADIUS, ModConfig.MAX_FAKE_PLAYER_CHUNK_RADIUS));
            config.setBatchMaxCount(readInt(root, "batchMaxCount",
                    ModConfig.DEFAULT_BATCH_MAX_COUNT,
                    ModConfig.MIN_BATCH_MAX_COUNT, ModConfig.MAX_BATCH_MAX_COUNT));
            config.setPayloadInterceptEnabled(readBool(root, "payloadInterceptEnabled",
                    ModConfig.DEFAULT_PAYLOAD_INTERCEPT_ENABLED));
            config.setPayloadLogLimit(readInt(root, "payloadLogLimit",
                    ModConfig.DEFAULT_PAYLOAD_LOG_LIMIT,
                    ModConfig.MIN_PAYLOAD_LOG_LIMIT, ModConfig.MAX_PAYLOAD_LOG_LIMIT));
            config.setPayloadSendLogEnabled(readBool(root, "payloadSendLogEnabled",
                    ModConfig.DEFAULT_PAYLOAD_SEND_LOG_ENABLED));
            config.setPayloadSendLogLimit(readInt(root, "payloadSendLogLimit",
                    ModConfig.DEFAULT_PAYLOAD_SEND_LOG_LIMIT,
                    ModConfig.MIN_PAYLOAD_SEND_LOG_LIMIT, ModConfig.MAX_PAYLOAD_SEND_LOG_LIMIT));
            config.setPayloadPassthroughNamespaces(readStringList(root, "payloadPassthroughNamespaces"));
        } catch (Exception e) {
            // 文件损坏/非 JSON 对象 → 整体回退默认，不崩客户端
            return new ModConfig();
        }
        config.normalize();
        return config;
    }

    /**
     * 保存配置到 JSON 文件（原子写：先写临时文件再改名，中断不留半截文件）。
     *
     * @param file   目标路径
     * @param config 要持久化的配置（先规范化，非法内存值也回退默认）
     */
    public static void save(Path file, ModConfig config) {
        ModConfig normalized = new ModConfig();
        normalized.setChatHistoryLimit(config.getChatHistoryLimit());
        normalized.setSoundLogLimit(config.getSoundLogLimit());
        normalized.setParticleLogLimit(config.getParticleLogLimit());
        normalized.setEventCacheSize(config.getEventCacheSize());
        normalized.setEventSummaryMaxLength(config.getEventSummaryMaxLength());
        normalized.setEventTickSampleInterval(config.getEventTickSampleInterval());
        normalized.setEventMoveSampleDistance(config.getEventMoveSampleDistance());
        normalized.setCommands(config.getCommands());
        normalized.setDebugOverlayMode(config.getDebugOverlayMode());
        normalized.setNavigateEnabled(config.isNavigateEnabled());
        normalized.setNavigateRenderMode(config.getNavigateRenderMode());
        normalized.setGuiEnabled(config.isGuiEnabled());
        normalized.setGuiOpacity(config.getGuiOpacity());
        normalized.setGuiBlur(config.getGuiBlur());
        normalized.setFakePlayerChunkRadius(config.getFakePlayerChunkRadius());
        normalized.setBatchMaxCount(config.getBatchMaxCount());
        normalized.setPayloadInterceptEnabled(config.isPayloadInterceptEnabled());
        normalized.setPayloadLogLimit(config.getPayloadLogLimit());
        normalized.setPayloadSendLogEnabled(config.isPayloadSendLogEnabled());
        normalized.setPayloadSendLogLimit(config.getPayloadSendLogLimit());
        normalized.setPayloadPassthroughNamespaces(config.getPayloadPassthroughNamespaces());
        normalized.normalize();

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(normalized, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 磁盘只读/路径非法等：不崩客户端，调用方（保存按钮）只影响本次保存
            throw new IllegalStateException("Failed to save mockplayer config to " + file, e);
        }
    }

    /** 读 int 字段：缺失/非数字/越界 → 默认值。 */
    private static int readInt(JsonObject root, String key, int fallback, int min, int max) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            int value = root.get(key).getAsInt();
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 读 double 字段：缺失/非数字/越界 → 默认值。 */
    private static double readDouble(JsonObject root, String key, double fallback, double min, double max) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            double value = root.get(key).getAsDouble();
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 读 bool 字段：缺失/非布尔 → 默认值。 */
    private static boolean readBool(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return root.get(key).getAsBoolean();
    }

    /** 读枚举字段：缺失/非字符串/未知值 → 默认值。 */
    private static RenderMode readEnum(JsonObject root, String key, RenderMode fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isString()) {
            return fallback;
        }
        try {
            return RenderMode.valueOf(root.get(key).getAsString().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** 读字符串列表字段：缺失/非数组/含非字符串元素 → 回退默认（空列表）。 */
    private static List<String> readStringList(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return new ArrayList<>();
        }
        JsonArray array = root.getAsJsonArray(key);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isJsonPrimitive() && array.get(i).getAsJsonPrimitive().isString()) {
                out.add(array.get(i).getAsString());
            }
        }
        return out;
    }

    /** 读 commands 对象：缺失/非对象 → 默认；逐条交给 ModCommands 规范化。 */
    private static java.util.Map<String, String> readCommands(JsonObject root) {
        if (!root.has("commands") || !root.get("commands").isJsonObject()) {
            return ModCommands.defaults();
        }
        JsonObject obj = root.getAsJsonObject("commands");
        java.util.Map<String, String> raw = new java.util.LinkedHashMap<>();
        for (String key : ModCommands.ALL) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()
                    && obj.get(key).getAsJsonPrimitive().isString()) {
                raw.put(key, obj.get(key).getAsString());
            }
        }
        return raw;
    }

}
