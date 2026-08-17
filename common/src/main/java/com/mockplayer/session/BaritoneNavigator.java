package com.mockplayer.session;

import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.pathing.goals.GoalBlock;
import com.mockplayer.baritone.api.pathing.goals.GoalComposite;
import com.mockplayer.baritone.api.pathing.goals.GoalNear;
import com.mockplayer.baritone.api.utils.BlockOptionalMetaLookup;

import com.mockplayer.api.Bot;
import com.mockplayer.api.navigate.BotNavigator;
import com.mockplayer.api.navigate.NavigationGoal;
import com.mockplayer.api.navigate.NavigationMode;
import com.mockplayer.api.navigate.NavigatorTask;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link BotNavigator} 的 Baritone 适配实现（common，依赖 vendor API）。
 *
 * 输入：假人专属 {@link IBaritone} 实例（FakePlayListener.handleLogin 创建并绑定
 * 假人 player/gameMode）
 * 输出：把我们的目标形状翻译成 Baritone Goal 系列，交给进程执行；
 * 激活期间置 {@link BotImpl#setNavigating} 标志 → BotActionsImpl 跳过输入写入
 * （Baritone InputOverrideHandler 已替换假人 input 为 PlayerMovementInput，两者互斥）。
 *
 * 所有方法必须主线程调用；{@link #tick()} 由 BotImpl.tick 每 tick 驱动，
 * 进程自然结束（到达/放弃/挖完）时复位任务状态。
 */
public final class BaritoneNavigator implements BotNavigator {

    private final BotImpl bot;
    /** 假人专属 Baritone 实例（未 PLAYING 时为 null；方法调用安全返回，无操作）。 */
    private final IBaritone baritone;
    /** 移动模式（WALK/ELYTRA；goTo 按模式选择进程）。 */
    private NavigationMode mode = NavigationMode.WALK;
    /** 当前任务类型（tick 与进程状态同步）。 */
    private NavigatorTask task = NavigatorTask.NONE;
    /** 当前任务目标（查询用；任务结束清空）。 */
    private BlockPos goal;
    /** mockplayer P15：elytra 任务中假人未滑翔（没起飞）的连续 tick 计数，
     *  超时强制解锁（防止寻路失败但 process 仍 active 时输入被锁死）。 */
    private int elytraNoFlyTicks;

    public BaritoneNavigator(BotImpl bot, IBaritone baritone) {
        this.bot = bot;
        this.baritone = baritone;
    }

    @Override
    public BotNavigator navigate(NavigationGoal goal) {
        IBaritone b = this.baritone;
        if (b == null) {
            return this;
        }
        // 替换旧任务：清掉所有进程（含 follow/mine），路径段也取消
        b.getPathingBehavior().cancelEverything();
        if (goal instanceof NavigationGoal.BlockGoal g) {
            this.goTo(b, g.pos());
        } else if (goal instanceof NavigationGoal.AreaGoal g) {
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(g.center(), g.radius()));
            this.task = NavigatorTask.GO_NEAR;
            this.goal = g.center();
        } else if (goal instanceof NavigationGoal.EntityGoal g) {
            Entity target = g.target();
            b.getFollowProcess().follow(e -> e == target);
            this.task = NavigatorTask.FOLLOW;
            this.goal = target != null ? target.blockPosition() : null;
        } else if (goal instanceof NavigationGoal.CompositeGoal g) {
            List<com.mockplayer.baritone.api.pathing.goals.Goal> list = new ArrayList<>();
            NavigatorTask fallback = NavigatorTask.GO_TO;
            for (NavigationGoal child : g.goals()) {
                if (child instanceof NavigationGoal.BlockGoal cg) {
                    list.add(new GoalBlock(cg.pos()));
                    fallback = NavigatorTask.GO_TO;
                } else if (child instanceof NavigationGoal.AreaGoal cg) {
                    list.add(new GoalNear(cg.center(), cg.radius()));
                    fallback = NavigatorTask.GO_NEAR;
                } else if (child instanceof NavigationGoal.EntityGoal cg && cg.target() != null) {
                    // 复合内嵌实体目标：退化为跟随最后一个实体（Baritone 无复合实体目标）
                    b.getFollowProcess().follow(e -> e == cg.target());
                    this.task = NavigatorTask.FOLLOW;
                    this.goal = cg.target().blockPosition();
                    this.bot.setNavigating(true);
                    return this;
                }
            }
            b.getCustomGoalProcess().setGoalAndPath(new GoalComposite(list.toArray(new com.mockplayer.baritone.api.pathing.goals.Goal[0])));
            this.task = fallback;
            this.goal = g.goals().isEmpty() ? null : firstBlock(g.goals());
        } else {
            return this;
        }
        this.bot.setNavigating(true);
        return this;
    }

    /** goTo 按当前模式分流：WALK → customGoalProcess；ELYTRA → elytraProcess。 */
    private void goTo(IBaritone b, BlockPos pos) {
        if (this.mode == NavigationMode.ELYTRA) {
            b.getElytraProcess().pathTo(pos);
            this.task = NavigatorTask.GO_TO;
            this.goal = pos;
        } else {
            b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
            this.task = NavigatorTask.GO_TO;
            this.goal = pos;
        }
    }

    /** 复合目标的第一个方块目标（查询用；没有方块目标返回 null）。 */
    private static BlockPos firstBlock(List<NavigationGoal> goals) {
        for (NavigationGoal g : goals) {
            if (g instanceof NavigationGoal.BlockGoal bg) {
                return bg.pos();
            }
            if (g instanceof NavigationGoal.AreaGoal ag) {
                return ag.center();
            }
        }
        return null;
    }

    @Override
    public BotNavigator mode(NavigationMode mode) {
        this.mode = mode != null ? mode : NavigationMode.WALK;
        return this;
    }

    @Override
    public BotNavigator stop() {
        IBaritone b = this.baritone;
        if (b != null) {
            b.getPathingBehavior().cancelEverything();
        }
        this.resetTask();
        return this;
    }

    /** 复位任务状态 + 清 Baritone 残留输入（直写字段方案下 stop 不换 input 对象）。 */
    private void resetTask() {
        this.task = NavigatorTask.NONE;
        this.goal = null;
        this.bot.setNavigating(false);
        if (this.baritone != null) {
            // 输入写入/清理职责归位到 baritone（InputOverrideHandler.clearInput）
            this.baritone.getInputOverrideHandler().clearInput();
        }
    }

    @Override
    public boolean isActive() {
        return this.task != NavigatorTask.NONE;
    }

    @Override
    public Optional<BlockPos> currentGoal() {
        return Optional.ofNullable(this.goal);
    }

    @Override
    public NavigatorTask currentTask() {
        return this.task;
    }

    @Override
    public BotNavigator mine(BlockPos target) {
        IBaritone b = this.baritone;
        if (b == null) {
            return this;
        }
        b.getPathingBehavior().cancelEverything();
        net.minecraft.world.level.block.state.BlockState state = this.bot.getBlockState(target);
        // quantity=0：挖到没有目标/手动 stop 为止。quantity>0 时 MineProcess 检查背包
        // 已有数量（desiredQuantity 达成即取消）——生产实测：假人背包已有 oak_log 时
        // mine oak_log 第一拍就「have_items」取消，假人完全不动（背包空的新假人才正常）。
        b.getMineProcess().mine(0, new BlockOptionalMetaLookup(state.getBlock()));
        this.task = NavigatorTask.MINE;
        this.goal = target;
        this.bot.setNavigating(true);
        return this;
    }

    @Override
    public BotNavigator mineByType(String blockId) {
        IBaritone b = this.baritone;
        if (b == null) {
            return this;
        }
        b.getPathingBehavior().cancelEverything();
        // 同上：quantity=0（背包已有目标物品不阻断挖矿，生产根因回归见 mine(BlockPos) 注释）
        b.getMineProcess().mine(0, new BlockOptionalMetaLookup(blockId));
        this.task = NavigatorTask.MINE;
        // 目标由 MineProcess 按类型就近寻找，无固定坐标
        this.goal = null;
        this.bot.setNavigating(true);
        return this;
    }

    @Override
    public BotNavigator elytra(BlockPos target) {
        IBaritone b = this.baritone;
        if (b == null) {
            return this;
        }
        b.getPathingBehavior().cancelEverything();
        b.getElytraProcess().pathTo(target);
        this.task = NavigatorTask.ELYTRA;
        this.goal = target;
        this.bot.setNavigating(true);
        return this;
    }

    /**
     * 每 tick 与进程状态同步：任务自然结束（到达/放弃/挖完）时复位
     * 任务类型/目标/navigating 标志（BotImpl.tick 调用）。
     */
    public void tick() {
        if (this.task == NavigatorTask.NONE) {
            return;
        }
        IBaritone b = this.baritone;
        boolean active;
        if (b == null) {
            active = false;
        } else {
            active = switch (this.task) {
                case GO_TO, GO_NEAR ->
                        b.getCustomGoalProcess().isActive() || b.getElytraProcess().isActive();
                case FOLLOW -> b.getFollowProcess().isActive();
                case MINE -> b.getMineProcess().isActive();
                case ELYTRA -> {
                    boolean elytraActive = b.getElytraProcess().isActive();
                    if (elytraActive && this.bot.getLocalPlayer() != null
                            && !this.bot.getLocalPlayer().isFallFlying()
                            && ++this.elytraNoFlyTicks > 400) {
                        // mockplayer P15（解锁兜底）：elytra 任务 active 但假人
                        // 20 秒没起飞（未穿鞘翅/flag 未生效/寻路失败未收尾）→
                        // 强制取消释放输入控制，用户恢复可操作
                        b.getPathingBehavior().cancelEverything();
                        elytraActive = false;
                    } else if (this.bot.getLocalPlayer() == null
                            || this.bot.getLocalPlayer().isFallFlying()) {
                        this.elytraNoFlyTicks = 0;
                    }
                    yield elytraActive;
                }
                default -> false;
            };
        }
        if (!active) {
            this.resetTask();
        }
    }
}
