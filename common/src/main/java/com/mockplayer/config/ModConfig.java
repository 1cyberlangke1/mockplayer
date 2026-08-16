package com.mockplayer.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mockplayer 客户端配置（纯数据类，零第三方依赖）。
 *
 * 输入：config/mockplayer.json（玩家可手改，缺字段/非法值由 ModConfigIO 回退默认）
 * 输出：运行时常量（FakePlayerState 日志上限、EventRecorder 事件缓存/采样参数）
 *
 * 默认值与历史硬编码常量一致，保证升级后行为不变。
 */
public class ModConfig {

    /** 聊天历史保留条数。 */
    public static final int DEFAULT_CHAT_HISTORY_LIMIT = 200;
    public static final int MIN_CHAT_HISTORY_LIMIT = 10;
    public static final int MAX_CHAT_HISTORY_LIMIT = 1000;

    /** 音效日志保留条数。 */
    public static final int DEFAULT_SOUND_LOG_LIMIT = 20;
    public static final int MIN_SOUND_LOG_LIMIT = 10;
    public static final int MAX_SOUND_LOG_LIMIT = 500;

    /** 粒子日志保留条数。 */
    public static final int DEFAULT_PARTICLE_LOG_LIMIT = 20;
    public static final int MIN_PARTICLE_LOG_LIMIT = 10;
    public static final int MAX_PARTICLE_LOG_LIMIT = 500;

    /** /query listen on 的事件环形缓存条数。 */
    public static final int DEFAULT_EVENT_CACHE_SIZE = 50;
    public static final int MIN_EVENT_CACHE_SIZE = 10;
    public static final int MAX_EVENT_CACHE_SIZE = 500;

    /** 事件推送摘要最大长度（防刷屏）。 */
    public static final int DEFAULT_EVENT_SUMMARY_MAX_LENGTH = 120;
    public static final int MIN_EVENT_SUMMARY_MAX_LENGTH = 20;
    public static final int MAX_EVENT_SUMMARY_MAX_LENGTH = 500;

    /** onTick 高频事件采样间隔（tick）。 */
    public static final int DEFAULT_EVENT_TICK_SAMPLE_INTERVAL = 20;
    public static final int MIN_EVENT_TICK_SAMPLE_INTERVAL = 1;
    public static final int MAX_EVENT_TICK_SAMPLE_INTERVAL = 200;

    /** onMove 高频事件采样位移阈值（方块）。 */
    public static final double DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE = 0.5;
    public static final double MIN_EVENT_MOVE_SAMPLE_DISTANCE = 0.1;
    public static final double MAX_EVENT_MOVE_SAMPLE_DISTANCE = 10.0;

    private int chatHistoryLimit = DEFAULT_CHAT_HISTORY_LIMIT;
    private int soundLogLimit = DEFAULT_SOUND_LOG_LIMIT;
    private int particleLogLimit = DEFAULT_PARTICLE_LOG_LIMIT;
    private int eventCacheSize = DEFAULT_EVENT_CACHE_SIZE;
    private int eventSummaryMaxLength = DEFAULT_EVENT_SUMMARY_MAX_LENGTH;
    private int eventTickSampleInterval = DEFAULT_EVENT_TICK_SAMPLE_INTERVAL;
    private double eventMoveSampleDistance = DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE;
    /** 根命令名配置（字段固定，值可改/可禁用；默认 = 现在的名字）。 */
    private Map<String, String> commands = new LinkedHashMap<>(ModCommands.defaults());
    /** 假人 F3 信息标签显示三态（替换布尔 debugOverlayEnabled；默认 F3_ONLY 与原行为等价）。 */
    private RenderMode debugOverlayMode = RenderMode.F3_ONLY;

    // ===== 寻路段（行为项 per-bot 可覆盖；渲染三态全局）=====

    /** 假人寻路接线默认开关。 */
    public static final boolean DEFAULT_NAVIGATE_ENABLED = true;
    /** 寻路路径超时（ms）范围。 */
    public static final int DEFAULT_NAVIGATE_PATH_TIMEOUT_MS = 2000;
    public static final int MIN_NAVIGATE_PATH_TIMEOUT_MS = 500;
    public static final int MAX_NAVIGATE_PATH_TIMEOUT_MS = 60000;

    private boolean navigateEnabled = DEFAULT_NAVIGATE_ENABLED;
    private boolean navigateAllowSprint = true;
    private boolean navigateAllowBreak = true;
    private boolean navigateAllowPlace = true;
    private boolean navigateAllowParkour = false;
    private boolean navigateAllowDiagonal = false;
    private boolean navigateAvoidance = false;
    private boolean navigatePreferSilkTouch = false;
    private boolean navigateMineScanDroppedItems = true;
    private int navigatePathTimeoutMs = DEFAULT_NAVIGATE_PATH_TIMEOUT_MS;
    /** 寻路路径/目标渲染三态（全局，主玩家视角；YACL GUI 可配）。 */
    private RenderMode navigateRenderMode = RenderMode.F3_ONLY;

    /** GUI 功能总开关（默认启用；关闭后按键/快捷键不打开 BotControlScreen，命令不受影响）。 */
    public static final boolean DEFAULT_GUI_ENABLED = true;
    /** GUI 背景不透明度（0.05-1.0，默认 0.25 = 低透明，透出游戏场景）。 */
    public static final float DEFAULT_GUI_OPACITY = 0.25F;
    public static final float MIN_GUI_OPACITY = 0.05F;
    public static final float MAX_GUI_OPACITY = 1.0F;
    /** GUI 背景高斯模糊强度（0 = 关闭，1-10 强度，默认 3 适中）。 */
    public static final int DEFAULT_GUI_BLUR = 3;
    public static final int MIN_GUI_BLUR = 0;
    public static final int MAX_GUI_BLUR = 10;

    private boolean guiEnabled = DEFAULT_GUI_ENABLED;
    private float guiOpacity = DEFAULT_GUI_OPACITY;
    private int guiBlur = DEFAULT_GUI_BLUR;

    /** 假人默认区块加载半径（节约性能：默认 1，范围 1-32）。 */
    public static final int DEFAULT_FAKE_PLAYER_CHUNK_RADIUS = 1;
    public static final int MIN_FAKE_PLAYER_CHUNK_RADIUS = 1;
    public static final int MAX_FAKE_PLAYER_CHUNK_RADIUS = 32;

    private int fakePlayerChunkRadius = DEFAULT_FAKE_PLAYER_CHUNK_RADIUS;

    /** 单次批量创建假人数量上限（性能测试保护）。 */
    public static final int DEFAULT_BATCH_MAX_COUNT = 100;
    public static final int MIN_BATCH_MAX_COUNT = 1;
    public static final int MAX_BATCH_MAX_COUNT = 1000;

    private int batchMaxCount = DEFAULT_BATCH_MAX_COUNT;

    /** mod payload 入站拦截总开关（默认开：假人连接收到的 mod payload 不交给双端分发链，记录到 state）。 */
    public static final boolean DEFAULT_PAYLOAD_INTERCEPT_ENABLED = true;
    /** 入站 mod payload 记录条数上限（环形截断）。 */
    public static final int DEFAULT_PAYLOAD_LOG_LIMIT = 50;
    public static final int MIN_PAYLOAD_LOG_LIMIT = 10;
    public static final int MAX_PAYLOAD_LOG_LIMIT = 500;
    /** 出站 mod payload 记录开关（只记录不拦截，服务端无感知，默认开）。 */
    public static final boolean DEFAULT_PAYLOAD_SEND_LOG_ENABLED = true;
    /** 出站 mod payload 记录条数上限（环形截断）。 */
    public static final int DEFAULT_PAYLOAD_SEND_LOG_LIMIT = 50;
    public static final int MIN_PAYLOAD_SEND_LOG_LIMIT = 10;
    public static final int MAX_PAYLOAD_SEND_LOG_LIMIT = 500;

    private boolean payloadInterceptEnabled = DEFAULT_PAYLOAD_INTERCEPT_ENABLED;
    private int payloadLogLimit = DEFAULT_PAYLOAD_LOG_LIMIT;
    private boolean payloadSendLogEnabled = DEFAULT_PAYLOAD_SEND_LOG_ENABLED;
    private int payloadSendLogLimit = DEFAULT_PAYLOAD_SEND_LOG_LIMIT;
    /** 放行逃生舱：这些 namespace 的 mod payload 不拦截（走原版分发链，mod handler 处理）。 */
    private List<String> payloadPassthroughNamespaces = new ArrayList<>();

    public int getChatHistoryLimit() {
        return this.chatHistoryLimit;
    }

    public void setChatHistoryLimit(int chatHistoryLimit) {
        this.chatHistoryLimit = chatHistoryLimit;
    }

    public int getSoundLogLimit() {
        return this.soundLogLimit;
    }

    public void setSoundLogLimit(int soundLogLimit) {
        this.soundLogLimit = soundLogLimit;
    }

    public int getParticleLogLimit() {
        return this.particleLogLimit;
    }

    public void setParticleLogLimit(int particleLogLimit) {
        this.particleLogLimit = particleLogLimit;
    }

    public int getEventCacheSize() {
        return this.eventCacheSize;
    }

    public void setEventCacheSize(int eventCacheSize) {
        this.eventCacheSize = eventCacheSize;
    }

    public int getEventSummaryMaxLength() {
        return this.eventSummaryMaxLength;
    }

    public void setEventSummaryMaxLength(int eventSummaryMaxLength) {
        this.eventSummaryMaxLength = eventSummaryMaxLength;
    }

    public int getEventTickSampleInterval() {
        return this.eventTickSampleInterval;
    }

    public void setEventTickSampleInterval(int eventTickSampleInterval) {
        this.eventTickSampleInterval = eventTickSampleInterval;
    }

    public double getEventMoveSampleDistance() {
        return this.eventMoveSampleDistance;
    }

    public void setEventMoveSampleDistance(double eventMoveSampleDistance) {
        this.eventMoveSampleDistance = eventMoveSampleDistance;
    }

    /** 根命令名配置（只读视图；修改用 setCommandName / setCommands）。 */
    public Map<String, String> getCommands() {
        return Collections.unmodifiableMap(this.commands);
    }

    /** 取某个根命令名（缺失回默认；空串 = 禁用，见 {@link ModCommands#isDisabled}）。 */
    public String getCommandName(String key) {
        String name = this.commands.get(key);
        return name != null ? name : ModCommands.defaults().get(key);
    }

    public void setCommandName(String key, String name) {
        Map<String, String> copy = new LinkedHashMap<>(this.commands);
        copy.put(key, name);
        this.commands = copy;
    }

    public void setCommands(Map<String, String> commands) {
        this.commands = commands != null
                ? new LinkedHashMap<>(commands)
                : new LinkedHashMap<>(ModCommands.defaults());
    }

    public RenderMode getDebugOverlayMode() {
        return this.debugOverlayMode;
    }

    public void setDebugOverlayMode(RenderMode debugOverlayMode) {
        this.debugOverlayMode = debugOverlayMode != null ? debugOverlayMode : RenderMode.F3_ONLY;
    }

    public boolean isNavigateEnabled() {
        return this.navigateEnabled;
    }

    public void setNavigateEnabled(boolean navigateEnabled) {
        this.navigateEnabled = navigateEnabled;
    }

    public boolean isNavigateAllowSprint() {
        return this.navigateAllowSprint;
    }

    public void setNavigateAllowSprint(boolean navigateAllowSprint) {
        this.navigateAllowSprint = navigateAllowSprint;
    }

    public boolean isNavigateAllowBreak() {
        return this.navigateAllowBreak;
    }

    public void setNavigateAllowBreak(boolean navigateAllowBreak) {
        this.navigateAllowBreak = navigateAllowBreak;
    }

    public boolean isNavigateAllowPlace() {
        return this.navigateAllowPlace;
    }

    public void setNavigateAllowPlace(boolean navigateAllowPlace) {
        this.navigateAllowPlace = navigateAllowPlace;
    }

    public boolean isNavigateAllowParkour() {
        return this.navigateAllowParkour;
    }

    public void setNavigateAllowParkour(boolean navigateAllowParkour) {
        this.navigateAllowParkour = navigateAllowParkour;
    }

    public boolean isNavigateAllowDiagonal() {
        return this.navigateAllowDiagonal;
    }

    public void setNavigateAllowDiagonal(boolean navigateAllowDiagonal) {
        this.navigateAllowDiagonal = navigateAllowDiagonal;
    }

    public boolean isNavigateAvoidance() {
        return this.navigateAvoidance;
    }

    public void setNavigateAvoidance(boolean navigateAvoidance) {
        this.navigateAvoidance = navigateAvoidance;
    }

    public boolean isNavigatePreferSilkTouch() {
        return this.navigatePreferSilkTouch;
    }

    public void setNavigatePreferSilkTouch(boolean navigatePreferSilkTouch) {
        this.navigatePreferSilkTouch = navigatePreferSilkTouch;
    }

    public boolean isNavigateMineScanDroppedItems() {
        return this.navigateMineScanDroppedItems;
    }

    public void setNavigateMineScanDroppedItems(boolean navigateMineScanDroppedItems) {
        this.navigateMineScanDroppedItems = navigateMineScanDroppedItems;
    }

    public int getNavigatePathTimeoutMs() {
        return this.navigatePathTimeoutMs;
    }

    public void setNavigatePathTimeoutMs(int navigatePathTimeoutMs) {
        this.navigatePathTimeoutMs = navigatePathTimeoutMs;
    }

    public RenderMode getNavigateRenderMode() {
        return this.navigateRenderMode;
    }

    public void setNavigateRenderMode(RenderMode navigateRenderMode) {
        this.navigateRenderMode = navigateRenderMode != null ? navigateRenderMode : RenderMode.F3_ONLY;
    }

    public boolean isGuiEnabled() {
        return this.guiEnabled;
    }

    public void setGuiEnabled(boolean guiEnabled) {
        this.guiEnabled = guiEnabled;
    }

    public float getGuiOpacity() {
        return this.guiOpacity;
    }

    public void setGuiOpacity(float guiOpacity) {
        this.guiOpacity = guiOpacity;
    }

    public int getGuiBlur() {
        return this.guiBlur;
    }

    public void setGuiBlur(int guiBlur) {
        this.guiBlur = guiBlur;
    }

    /** 不透明度规范化：NaN/非法 → 默认；范围钳制到 MIN..MAX。 */
    static float normalizeGuiOpacity(float value) {
        if (!Float.isFinite(value)) {
            return DEFAULT_GUI_OPACITY;
        }
        return Math.max(MIN_GUI_OPACITY, Math.min(MAX_GUI_OPACITY, value));
    }

    public int getFakePlayerChunkRadius() {
        return this.fakePlayerChunkRadius;
    }

    public void setFakePlayerChunkRadius(int fakePlayerChunkRadius) {
        this.fakePlayerChunkRadius = fakePlayerChunkRadius;
    }

    public int getBatchMaxCount() {
        return this.batchMaxCount;
    }

    public void setBatchMaxCount(int batchMaxCount) {
        this.batchMaxCount = batchMaxCount;
    }

    public boolean isPayloadInterceptEnabled() {
        return this.payloadInterceptEnabled;
    }

    public void setPayloadInterceptEnabled(boolean payloadInterceptEnabled) {
        this.payloadInterceptEnabled = payloadInterceptEnabled;
    }

    public int getPayloadLogLimit() {
        return this.payloadLogLimit;
    }

    public void setPayloadLogLimit(int payloadLogLimit) {
        this.payloadLogLimit = payloadLogLimit;
    }

    public boolean isPayloadSendLogEnabled() {
        return this.payloadSendLogEnabled;
    }

    public void setPayloadSendLogEnabled(boolean payloadSendLogEnabled) {
        this.payloadSendLogEnabled = payloadSendLogEnabled;
    }

    public int getPayloadSendLogLimit() {
        return this.payloadSendLogLimit;
    }

    public void setPayloadSendLogLimit(int payloadSendLogLimit) {
        this.payloadSendLogLimit = payloadSendLogLimit;
    }

    public List<String> getPayloadPassthroughNamespaces() {
        return this.payloadPassthroughNamespaces;
    }

    public void setPayloadPassthroughNamespaces(List<String> payloadPassthroughNamespaces) {
        this.payloadPassthroughNamespaces = payloadPassthroughNamespaces;
    }

    /** 越界字段回退默认（非法值不保留，保证配置文件永远可手改且不崩）。 */
    public void normalize() {
        this.chatHistoryLimit = clampInt(this.chatHistoryLimit,
                MIN_CHAT_HISTORY_LIMIT, MAX_CHAT_HISTORY_LIMIT, DEFAULT_CHAT_HISTORY_LIMIT);
        this.soundLogLimit = clampInt(this.soundLogLimit,
                MIN_SOUND_LOG_LIMIT, MAX_SOUND_LOG_LIMIT, DEFAULT_SOUND_LOG_LIMIT);
        this.particleLogLimit = clampInt(this.particleLogLimit,
                MIN_PARTICLE_LOG_LIMIT, MAX_PARTICLE_LOG_LIMIT, DEFAULT_PARTICLE_LOG_LIMIT);
        this.eventCacheSize = clampInt(this.eventCacheSize,
                MIN_EVENT_CACHE_SIZE, MAX_EVENT_CACHE_SIZE, DEFAULT_EVENT_CACHE_SIZE);
        this.eventSummaryMaxLength = clampInt(this.eventSummaryMaxLength,
                MIN_EVENT_SUMMARY_MAX_LENGTH, MAX_EVENT_SUMMARY_MAX_LENGTH, DEFAULT_EVENT_SUMMARY_MAX_LENGTH);
        this.eventTickSampleInterval = clampInt(this.eventTickSampleInterval,
                MIN_EVENT_TICK_SAMPLE_INTERVAL, MAX_EVENT_TICK_SAMPLE_INTERVAL, DEFAULT_EVENT_TICK_SAMPLE_INTERVAL);
        this.eventMoveSampleDistance = clampDouble(this.eventMoveSampleDistance,
                MIN_EVENT_MOVE_SAMPLE_DISTANCE, MAX_EVENT_MOVE_SAMPLE_DISTANCE, DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE);
        this.navigatePathTimeoutMs = clampInt(this.navigatePathTimeoutMs,
                MIN_NAVIGATE_PATH_TIMEOUT_MS, MAX_NAVIGATE_PATH_TIMEOUT_MS, DEFAULT_NAVIGATE_PATH_TIMEOUT_MS);
        if (this.debugOverlayMode == null) {
            this.debugOverlayMode = RenderMode.F3_ONLY;
        }
        if (this.navigateRenderMode == null) {
            this.navigateRenderMode = RenderMode.F3_ONLY;
        }
        this.fakePlayerChunkRadius = clampInt(this.fakePlayerChunkRadius,
                MIN_FAKE_PLAYER_CHUNK_RADIUS, MAX_FAKE_PLAYER_CHUNK_RADIUS, DEFAULT_FAKE_PLAYER_CHUNK_RADIUS);
        this.batchMaxCount = clampInt(this.batchMaxCount,
                MIN_BATCH_MAX_COUNT, MAX_BATCH_MAX_COUNT, DEFAULT_BATCH_MAX_COUNT);
        this.payloadLogLimit = clampInt(this.payloadLogLimit,
                MIN_PAYLOAD_LOG_LIMIT, MAX_PAYLOAD_LOG_LIMIT, DEFAULT_PAYLOAD_LOG_LIMIT);
        this.payloadSendLogLimit = clampInt(this.payloadSendLogLimit,
                MIN_PAYLOAD_SEND_LOG_LIMIT, MAX_PAYLOAD_SEND_LOG_LIMIT, DEFAULT_PAYLOAD_SEND_LOG_LIMIT);
        // 放行名单规范化：trim、去空、去重；null 视为空
        List<String> cleaned = new ArrayList<>();
        if (this.payloadPassthroughNamespaces != null) {
            for (String ns : this.payloadPassthroughNamespaces) {
                if (ns != null) {
                    String trimmed = ns.trim();
                    if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                        cleaned.add(trimmed);
                    }
                }
            }
        }
        this.payloadPassthroughNamespaces = cleaned;
        this.commands = ModCommands.normalize(this.commands);
        this.guiOpacity = normalizeGuiOpacity(this.guiOpacity);
        this.guiBlur = Math.max(MIN_GUI_BLUR, Math.min(MAX_GUI_BLUR, this.guiBlur));
    }

    private static int clampInt(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    private static double clampDouble(double value, double min, double max, double fallback) {
        return value >= min && value <= max ? value : fallback;
    }
}
