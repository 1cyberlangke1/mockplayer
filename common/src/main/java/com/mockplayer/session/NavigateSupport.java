package com.mockplayer.session;

import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.Settings;

import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.config.RenderMode;

import net.minecraft.client.Minecraft;
import java.util.List;

/**
 * 寻路配置接线（common）：全局 baritone Settings（settings.txt）+ per-bot 覆盖 → 假人实例 Settings；
 * 渲染三态（navigateRenderMode，全局）经 {@link com.mockplayer.baritone.utils.RenderGate}
 * 实时判定（PathRenderer 每帧查询），无缓存无每 tick 同步。
 *
 * 输入：BaritoneAPI.getSettings()（全局默认，settings.txt 持久化）+ FakeSession.navigateOverrides
 * 输出：baritone.settings() 各 Setting.value（热生效：Baritone 每 tick/每帧直接读 value）
 *
 * 全局行为项（allowSprint/allowBreak/.../pathTimeoutMs/日志开关）归 baritone 侧，由
 * BaritoneConfigScreen（YACL）或手改 settings.txt 修改；本类只做「全局 → 实例」继承
 * （copyFrom）+ per-bot 覆盖。假人创建时继承全局，之后全局变更对已存在假人由
 * 配置界面保存传播（copyFrom 全部实例）或 config set/reset 覆盖。
 */
public final class NavigateSupport {

    /** /control config set 支持的 key 白名单（与 {@link #effectiveValue} 对应）。 */
    public static final List<String> CONFIG_KEYS = List.of(
            "enabled", "allowSprint", "allowBreak", "allowPlace",
            "allowParkour", "allowDiagonal", "avoidance",
            "preferSilkTouch", "mineScanDroppedItems", "pathTimeoutMs",
            "logToChat", "logDebugToChat", "logNotificationToChat", "logToastToChat");

    private NavigateSupport() {
    }

    /** pathTimeoutMs 合法范围（baritone primaryTimeoutMS 无原生范围，命令侧收窄）。 */
    private static final int MIN_PATH_TIMEOUT_MS = 500;
    private static final int MAX_PATH_TIMEOUT_MS = 60000;

    /** 读取假人生效配置值（per-bot 覆盖优先，否则全局默认；未知 key 返回 null）。 */
    public static Object effectiveValue(FakeSession session, String key) {
        Object override = session.getNavigateOverride(key);
        if (override != null) {
            return override;
        }
        Settings g = BaritoneAPI.getSettings();
        return switch (key) {
            case "enabled" -> MockplayerConfig.get().isNavigateEnabled();
            case "allowSprint" -> g.allowSprint.value;
            case "allowBreak" -> g.allowBreak.value;
            case "allowPlace" -> g.allowPlace.value;
            case "allowParkour" -> g.allowParkour.value;
            case "allowDiagonal" -> g.allowDiagonalAscend.value;
            case "avoidance" -> g.avoidance.value;
            case "preferSilkTouch" -> g.preferSilkTouch.value;
            case "mineScanDroppedItems" -> g.mineScanDroppedItems.value;
            case "pathTimeoutMs" -> g.primaryTimeoutMS.value;
            case "logToChat" -> g.logToChat.value;
            case "logDebugToChat" -> g.logDebugToChat.value;
            case "logNotificationToChat" -> g.logNotificationToChat.value;
            case "logToastToChat" -> g.logToastToChat.value;
            default -> null;
        };
    }

    /** 解析 config set 的字符串值（布尔/整数；非法返回 null）。 */
    public static Object parseValue(String key, String raw) {
        if ("pathTimeoutMs".equals(key)) {
            try {
                int v = Integer.parseInt(raw.trim());
                return v >= MIN_PATH_TIMEOUT_MS && v <= MAX_PATH_TIMEOUT_MS ? v : null;
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
        // 先继承全局（settings.txt 默认），再 per-bot 覆盖（navigateOverrides 命中优先）
        settings.copyFrom(BaritoneAPI.getSettings());
        settings.allowSprint.value = Boolean.TRUE.equals(effectiveValue(session, "allowSprint"));
        settings.allowBreak.value = Boolean.TRUE.equals(effectiveValue(session, "allowBreak"));
        settings.allowPlace.value = Boolean.TRUE.equals(effectiveValue(session, "allowPlace"));
        settings.allowParkour.value = Boolean.TRUE.equals(effectiveValue(session, "allowParkour"));
        // baritone 无单一 allowDiagonal：斜向由 上下坡 两个开关控制，同值写入
        boolean diagonal = Boolean.TRUE.equals(effectiveValue(session, "allowDiagonal"));
        settings.allowDiagonalAscend.value = diagonal;
        settings.allowDiagonalDescend.value = diagonal;
        settings.avoidance.value = Boolean.TRUE.equals(effectiveValue(session, "avoidance"));
        settings.preferSilkTouch.value = Boolean.TRUE.equals(effectiveValue(session, "preferSilkTouch"));
        settings.mineScanDroppedItems.value = Boolean.TRUE.equals(effectiveValue(session, "mineScanDroppedItems"));
        settings.logToChat.value = Boolean.TRUE.equals(effectiveValue(session, "logToChat"));
        settings.logDebugToChat.value = Boolean.TRUE.equals(effectiveValue(session, "logDebugToChat"));
        settings.logNotificationToChat.value = Boolean.TRUE.equals(effectiveValue(session, "logNotificationToChat"));
        settings.logToastToChat.value = Boolean.TRUE.equals(effectiveValue(session, "logToastToChat"));
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
