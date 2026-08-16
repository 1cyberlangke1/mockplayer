package com.mockplayer.baritone.utils;

import java.util.function.BooleanSupplier;

/**
 * 渲染闸门（主项目注册，Baritone 查询）：渲染路径/目标前问主项目「现在该不该渲染」。
 *
 * 输入：主项目 {@link #register(BooleanSupplier)} 注册的三态判定（ALWAYS/F3_ONLY/OFF）
 * 输出：PathRenderer 每帧调用 {@link #shouldRender()}，实时判定，无缓存无每 tick 同步。
 * 未注册时默认 true（保持原版 Baritone 渲染行为）。
 */
public final class RenderGate {

    private static volatile BooleanSupplier gate = () -> true;

    private RenderGate() {
    }

    /** 主项目注册判定（null 恢复默认全渲染）。 */
    public static void register(BooleanSupplier gate) {
        RenderGate.gate = gate != null ? gate : () -> true;
    }

    /** 当前是否渲染（渲染线程每帧调用，实时读配置）。 */
    public static boolean shouldRender() {
        return gate.getAsBoolean();
    }
}
