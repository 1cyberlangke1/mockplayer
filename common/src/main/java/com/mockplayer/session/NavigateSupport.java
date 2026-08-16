package com.mockplayer.session;

import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.Settings;

import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.config.RenderMode;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * 寻路配置接线（common）：全局 ModConfig + per-bot 覆盖 → 假人 Baritone Settings；
 * 渲染三态（navigateRenderMode，全局）经 {@link com.mockplayer.baritone.utils.RenderGate}
 * 实时判定（PathRenderer 每帧查询），无缓存无每 tick 同步。
 *
 * 输入：MockplayerConfig.get() + FakeSession.navigateOverrides
 * 输出：baritone.settings() 各 Setting.value（热生效：Baritone 每 tick/每帧直接读 value）
 */
public final class NavigateSupport {

    /** /control config set 支持的 key 白名单（与 {@link #effectiveValue} 对应）。 */
    public static final List<String> CONFIG_KEYS = List.of(
            "enabled", "allowSprint", "allowBreak", "allowPlace", "pathTimeoutMs");

    private NavigateSupport() {
    }

    /** 读取假人生效配置值（per-bot 覆盖优先，否则全局默认；未知 key 返回 null）。 */
    public static Object effectiveValue(FakeSession session, String key) {
        Object override = session.getNavigateOverride(key);
        if (override != null) {
            return override;
        }
        ModConfig cfg = MockplayerConfig.get();
        return switch (key) {
            case "enabled" -> cfg.isNavigateEnabled();
            case "allowSprint" -> cfg.isNavigateAllowSprint();
            case "allowBreak" -> cfg.isNavigateAllowBreak();
            case "allowPlace" -> cfg.isNavigateAllowPlace();
            case "pathTimeoutMs" -> cfg.getNavigatePathTimeoutMs();
            default -> null;
        };
    }

    /** 解析 config set 的字符串值（布尔/整数；非法返回 null）。 */
    public static Object parseValue(String key, String raw) {
        if ("pathTimeoutMs".equals(key)) {
            try {
                int v = Integer.parseInt(raw.trim());
                return v >= ModConfig.MIN_NAVIGATE_PATH_TIMEOUT_MS
                        && v <= ModConfig.MAX_NAVIGATE_PATH_TIMEOUT_MS ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 应用寻路配置到假人 baritone 实例（创建时 / 配置热重载 / config set 后调用）。 */
    public static void applyToSession(FakeSession session) {
        IBaritone baritone = session.getBaritone();
        if (baritone == null) {
            return;
        }
        Settings settings = baritone.settings();
        settings.allowSprint.value = Boolean.TRUE.equals(effectiveValue(session, "allowSprint"));
        settings.allowBreak.value = Boolean.TRUE.equals(effectiveValue(session, "allowBreak"));
        settings.allowPlace.value = Boolean.TRUE.equals(effectiveValue(session, "allowPlace"));
        Object timeout = effectiveValue(session, "pathTimeoutMs");
        if (timeout instanceof Number n) {
            settings.primaryTimeoutMS.value = n.longValue();
        }
    }

    /** 配置热重载：全部在线假人重应用（MockplayerConfig.onReload 注册）。 */
    public static void applyAll() {
        for (String name : SessionManager.getInstance().getSessionNames()) {
            FakeSession session = SessionManager.getInstance().getSession(name);
            if (session != null) {
                applyToSession(session);
            }
        }
    }

    /** 渲染闸门是否已注册（幂等，只注册一次）。 */
    private static boolean renderGateRegistered;

    /** 注册渲染闸门（SessionManager.tick 调用）：PathRenderer 每帧实时判定三态，无缓存无同步。 */
    public static void ensureRenderGate() {
        if (NavigateSupport.renderGateRegistered) {
            return;
        }
        NavigateSupport.renderGateRegistered = true;
        com.mockplayer.baritone.utils.RenderGate.register(() ->
                switch (MockplayerConfig.get().getNavigateRenderMode()) {
                    case ALWAYS -> true;
                    case OFF -> false;
                    case F3_ONLY -> Minecraft.getInstance().getDebugOverlay().showDebugScreen();
                });
    }
}
