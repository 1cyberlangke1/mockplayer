package com.mockplayer.api.navigate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * 假人寻路门面（可插拔）：上层（命令/行为树/未来 AI）只依赖本接口，
 * 底层实现可替换（当前 BaritoneNavigator，未来自研 A* 或其他库）。
 *
 * 所有方法必须主线程调用；持续任务（goTo 等）由每 tick 驱动直至
 * {@link #stop()} / 到达 / 放弃。
 */
public interface BotNavigator {

    // ===== 元移动（核心）=====

    /**
     * 前往任意目标（替换旧任务；目标形状见 {@link NavigationGoal}）。
     *
     * @param goal 目标
     * @return this（链式）
     */
    BotNavigator navigate(NavigationGoal goal);

    /**
     * 切换移动模式（WALK/ELYTRA；任务运行时生效）。
     *
     * @param mode 移动方式
     * @return this（链式）
     */
    BotNavigator mode(NavigationMode mode);

    /**
     * 取消当前任务（路径 + 输入接管复位，恢复 BotActions 控制）。
     *
     * @return this（链式）
     */
    BotNavigator stop();

    /**
     * 是否有任务在执行（含路径计算中/执行中）。
     *
     * @return true 有任务
     */
    boolean isActive();

    /**
     * 当前任务目标（无任务返回 empty）。
     *
     * @return 目标方块坐标
     */
    Optional<BlockPos> currentGoal();

    /**
     * 当前任务类型（无任务返回 {@link NavigatorTask#NONE}）。
     *
     * @return 任务类型
     */
    NavigatorTask currentTask();

    // ===== 便捷方法（default 语法糖，内部就是 navigate）=====

    /**
     * 走到指定方块格。
     *
     * @param pos 目标方块
     * @return this（链式）
     */
    default BotNavigator goTo(BlockPos pos) {
        return navigate(new NavigationGoal.BlockGoal(pos));
    }

    /**
     * 靠近指定坐标水平半径内。
     *
     * @param pos    目标坐标
     * @param radius 停止半径
     * @return this（链式）
     */
    default BotNavigator goNear(BlockPos pos, int radius) {
        return navigate(new NavigationGoal.AreaGoal(pos, radius));
    }

    /**
     * 跟随实体（目标死亡/消失自动结束任务）。
     *
     * @param target 目标实体
     * @return this（链式）
     */
    default BotNavigator follow(Entity target) {
        return navigate(new NavigationGoal.EntityGoal(target, 2.0));
    }

    // ===== 任务（非纯导航，走 Baritone 进程）=====

    /**
     * 挖指定方块（Baritone MineProcess：自动寻路到矿、选工具、挖掘、拾取掉落物，一条龙）。
     *
     * @param target 目标方块
     * @return this（链式）
     */
    BotNavigator mine(BlockPos target);

    /**
     * 按方块类型挖矿（Baritone MineProcess：自动寻路到最近的该类方块、选工具、挖掘、拾取，一条龙）。
     *
     * @param blockId 方块注册表 id（如 "minecraft:diamond_ore"）
     * @return this（链式）
     */
    BotNavigator mineByType(String blockId);

}
