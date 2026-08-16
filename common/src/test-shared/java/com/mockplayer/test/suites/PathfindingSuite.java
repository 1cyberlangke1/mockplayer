package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.navigate.NavigationGoal;
import com.mockplayer.api.navigate.NavigationMode;
import com.mockplayer.api.navigate.NavigatorTask;
import com.mockplayer.session.BotImpl;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;

import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.baritone.api.IBaritone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * pathfinding：假人寻路接线硬测试（不测寻路质量，只测
 * 「命令/API → BotNavigator → Baritone → tick 驱动 → 假人真的被驱动」全链贯通）。
 *
 * mocktest 世界是超平坦（SuiteRunner 创建 flat 世界），适合行走测试。
 */
public class PathfindingSuite extends TestSuite {

    private static final String BOT_A = "tbot-navA";
    private static final String BOT_B = "tbot-navB";
    /** 用例内起点（goal 在任务结束时会被清空，位移必须相对起点计算）。 */
    private BlockPos startPos;
    /** 等待 tick 计数（await 条件用）。 */
    private int waitTicks;
    /** 销毁清理用例：待销毁的 baritone 实例 + 销毁前实例总数。 */
    private IBaritone doomedBaritone;
    private int baritoneCountBefore;

    public PathfindingSuite() {
        super("pathfinding");
        test("goTo 端到端", this::goToEndToEnd);
        test("stop 接线", this::stopWiring);
        test("per-bot 配置独立", this::perBotConfigIsolation);
        test("elytra API 接线", this::elytraApiWiring);
        test("销毁清理", this::destroyCleanup);
        test("API 全方法接线", this::apiAllMethodsWiring);
        test("命令层 mode/elytra/mine/follow", this::commandLayerWiring);
    }

    /** 测试 1：goTo 端到端——位移 > 2 格 + 最终水平距离 < 3 格。 */
    private void goToEndToEnd(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            BlockPos start = ctx.bot().getLocalPlayer().blockPosition();
            this.startPos = start;
            ctx.checkNow("navigate not active initially", !ctx.bot().navigate().isActive());
            ctx.bot().navigate().goTo(start.offset(10, 0, 0));
            ctx.checkNow("navigate active after goTo", ctx.bot().navigate().isActive());
            ctx.checkNow("currentGoal matches target",
                    ctx.bot().navigate().currentGoal().isPresent()
                            && ctx.bot().navigate().currentGoal().get().equals(start.offset(10, 0, 0)));
        });
        ctx.await("bot moved > 2 blocks or task finished",
                () -> ctx.bot() != null && ctx.bot().getLocalPlayer() != null
                        && (distanceFromStart(ctx) > 2.0 || !ctx.bot().navigate().isActive()), 600);
        ctx.check("moved at least 2 blocks", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && distanceFromStart(ctx) > 2.0);
        // 目标 = start + (10,0,0)：从起点走 > 7 格 = 离目标水平 < 3 格
        ctx.await("arrived near goal (distance < 3)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && (distanceFromStart(ctx) > 7.0 || !ctx.bot().navigate().isActive()), 600);
        ctx.check("final distance < 3 blocks", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && distanceFromStart(ctx) > 7.0);
        ctx.run(() -> ctx.bot().navigate().stop());
    }

    /** 测试 2：stop 接线——isActive=false + 20 tick 位移 < 0.5 格。 */
    private void stopWiring(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            BlockPos start = ctx.bot().getLocalPlayer().blockPosition();
            this.startPos = start;
            ctx.bot().navigate().goTo(start.offset(50, 0, 0));
        });
        ctx.await("task active", () -> ctx.bot() != null
                && ctx.bot().navigate().isActive(), 200);
        ctx.run(() -> {
            ctx.bot().navigate().stop();
            ctx.checkNow("stop clears active", !ctx.bot().navigate().isActive());
            ctx.checkNow("stop clears goal", ctx.bot().navigate().currentGoal().isEmpty());
            // 清惯性：stop 验证的是「不再被驱动」（残留输入会让假人重新加速），
            // 不是摩擦减速曲线
            ctx.bot().getLocalPlayer().setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        });
        ctx.run(() -> this.waitTicks = 0);
        ctx.await("20 ticks elapsed", () -> ++this.waitTicks >= 20, 60);
        // 服务端惯性约 1 秒（假人 stop 后服务端玩家速度残留），位移 ~0.5 格；
        // 输入残留（bug）会让假人重新加速走出 3-5 格，1.0 阈值仍能捕获
        ctx.check("no movement after stop (< 1.0 block)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && distanceFromStart(ctx) < 1.0, () -> "moved=" + distanceFromStart(ctx)
                + " input=" + ctx.bot().getLocalPlayer().input.getClass().getSimpleName());
    }

    /** 测试 3：per-bot 配置独立——Settings 对象不同且 allowSprint 值独立。 */
    private void perBotConfigIsolation(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("A PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_B));
        ctx.await("B PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING
                && BOT_B.equals(ctx.bot().getName()), 300);
        ctx.run(() -> {
            ctx.platform().executeClientCommand(
                    "control " + BOT_A + " config set allowSprint false");
            Bot a = MockplayerApi.bots().getBot(BOT_A).orElse(null);
            Bot b = MockplayerApi.bots().getBot(BOT_B).orElse(null);
            ctx.checkNow("both bots present", a != null && b != null);
            if (a instanceof BotImpl aImpl && b instanceof BotImpl bImpl) {
                IBaritone aBar = aImpl.session().getBaritone();
                IBaritone bBar = bImpl.session().getBaritone();
                ctx.checkNow("baritone instances exist", aBar != null && bBar != null);
                ctx.checkNow("per-bot settings objects differ",
                        aBar != null && bBar != null && aBar.settings() != bBar.settings());
                ctx.checkNow("A allowSprint false (per-bot override)",
                        aBar != null && !aBar.settings().allowSprint.value);
                ctx.checkNow("B allowSprint true (global default)",
                        bBar != null && bBar.settings().allowSprint.value);
            }
        });
        ctx.run(() -> {
            ctx.platform().executeClientCommand(
                    "control " + BOT_A + " config reset allowSprint");
            Bot a = MockplayerApi.bots().getBot(BOT_A).orElse(null);
            if (a instanceof BotImpl aImpl && aImpl.session().getBaritone() != null) {
                ctx.checkNow("A allowSprint back to true after reset",
                        aImpl.session().getBaritone().settings().allowSprint.value);
            }
        });
    }

    /** 测试 4：elytra API 接线——调用不抛异常 + 任务状态正确（不真飞）。 */
    private void elytraApiWiring(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            BlockPos target = ctx.bot().getLocalPlayer().blockPosition().offset(5, 0, 5);
            boolean threw = false;
            try {
                ctx.bot().navigate().elytra(target);
            } catch (Exception e) {
                threw = true;
            }
            ctx.checkNow("elytra call does not throw", !threw);
            ctx.checkNow("elytra task registered",
                    ctx.bot().navigate().currentTask()
                            == com.mockplayer.api.navigate.NavigatorTask.ELYTRA);
            ctx.bot().navigate().stop();
            ctx.checkNow("elytra stopped", !ctx.bot().navigate().isActive());
        });
    }

    /** 测试 5：销毁清理——delplayer 后 baritone 实例被 destroyBaritone 移除。 */
    private void destroyCleanup(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            Bot bot = ctx.bot();
            IBaritone bar = bot instanceof BotImpl impl ? impl.session().getBaritone() : null;
            this.doomedBaritone = bar;
            this.baritoneCountBefore = BaritoneAPI.getProvider().getAllBaritones().size();
            ctx.checkNow("baritone instance created for bot", bar != null);
            ctx.checkNow("provider knows the bot instance", bar != null
                    && BaritoneAPI.getProvider().getBaritoneForPlayer(bot.getLocalPlayer()) == bar);
        });
        ctx.run(() -> {
            ctx.platform().executeClientCommand("delplayer " + BOT_A);
        });
        ctx.await("bot removed", () -> MockplayerApi.bots()
                .getBot(BOT_A).isEmpty(), 200);
        ctx.run(() -> {
            // 假人销毁 → 该实例被 destroyBaritone 移除：总数 -1 且引用不再在列表中
            ctx.checkNow("baritone instance destroyed",
                    this.doomedBaritone != null
                            && BaritoneAPI.getProvider().getAllBaritones().size()
                            == this.baritoneCountBefore - 1
                            && !BaritoneAPI.getProvider().getAllBaritones()
                            .contains(this.doomedBaritone));
        });
    }

    /** 测试 6：BotNavigator 全方法接线——每个方法调用不抛且任务状态正确（不真走完）。 */
    private void apiAllMethodsWiring(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            Bot bot = ctx.bot();
            var nav = bot.navigate();
            BlockPos base = bot.getLocalPlayer().blockPosition();

            // goNear（AreaGoal）：任务注册 + 目标正确 + stop 复位
            nav.goNear(base.offset(5, 0, 0), 3);
            ctx.checkNow("goNear active", nav.isActive());
            ctx.checkNow("goNear task", nav.currentTask() == NavigatorTask.GO_NEAR);
            ctx.checkNow("goNear goal", nav.currentGoal().isPresent()
                    && nav.currentGoal().get().equals(base.offset(5, 0, 0)));
            nav.stop();
            ctx.checkNow("goNear stopped", !nav.isActive());

            // follow（EntityGoal）：目标存在时任务注册
            net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                    .filter(e -> !(e instanceof net.minecraft.client.player.LocalPlayer))
                    .findFirst().orElse(null);
            if (target != null) {
                nav.follow(target);
                ctx.checkNow("follow active", nav.isActive());
                ctx.checkNow("follow task", nav.currentTask() == NavigatorTask.FOLLOW);
                ctx.checkNow("follow goal matches entity", nav.currentGoal().isPresent()
                        && nav.currentGoal().get().equals(target.blockPosition()));
                nav.stop();
            }

            // mode 切换：调用不抛 + 状态保留
            boolean modeThrew = false;
            try {
                nav.mode(NavigationMode.ELYTRA);
                nav.mode(NavigationMode.WALK);
            } catch (Exception e) {
                modeThrew = true;
            }
            ctx.checkNow("mode switch no throw", !modeThrew);

            // mine（BlockPos）：任务注册 + stop 复位
            BlockPos below = base.below();
            boolean mineThrew = false;
            try {
                nav.mine(below);
            } catch (Exception e) {
                mineThrew = true;
            }
            ctx.checkNow("mine no throw", !mineThrew);
            ctx.checkNow("mine task", nav.currentTask() == NavigatorTask.MINE);
            ctx.checkNow("mine goal", nav.currentGoal().isPresent()
                    && nav.currentGoal().get().equals(below));
            nav.stop();
            ctx.checkNow("mine stopped", !nav.isActive());

            // composite（CompositeGoal）：混合目标注册
            nav.navigate(new NavigationGoal.CompositeGoal(java.util.List.of(
                    new NavigationGoal.BlockGoal(base.offset(6, 0, 0)),
                    new NavigationGoal.AreaGoal(base.offset(8, 0, 8), 2))));
            ctx.checkNow("composite active", nav.isActive());
            nav.stop();
            ctx.checkNow("composite stopped", !nav.isActive());
            ctx.checkNow("all cleared after stops", nav.currentTask() == NavigatorTask.NONE
                    && nav.currentGoal().isEmpty());
        });
    }

    /** 测试 7：命令层接线——/control mode/elytra/mine/follow 执行后任务状态正确。 */
    private void commandLayerWiring(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        // 服务端放一个 NoAI 村民供 follow 命令使用（经包同步到假人客户端 level）
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT_A);
            if (sp != null) {
                ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:villager %.2f %.2f %.2f {NoAI:1b}",
                                sp.getX() + 2.0, sp.getY(), sp.getZ()));
            }
        }));
        ctx.await("villager near", () -> ctx.bot() != null
                && ctx.bot().getEntitiesNear(16).stream().anyMatch(e -> e instanceof Villager), 200);
        ctx.run(() -> {
            BlockPos base = ctx.bot().getLocalPlayer().blockPosition();
            // mode：walk/elytra 切换（不启动任务，仅验证命令可执行）
            ctx.checkNow("mode elytra ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mode elytra"));
            ctx.checkNow("mode walk ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mode walk"));
            // mode elytra 后 goto：走鞘翅进程（模式生效验证）
            ctx.checkNow("mode elytra again", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mode elytra"));
            BlockPos far = base.offset(30, 0, 30);
            ctx.checkNow("goto after mode elytra ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " goto " + far.getX() + " " + far.getY() + " " + far.getZ()));
            ctx.checkNow("goto uses elytra process", ctx.bot() instanceof BotImpl impl
                    && impl.session().getBaritone() != null
                    && impl.session().getBaritone().getElytraProcess().currentDestination() != null);
            ctx.bot().navigate().stop();
            // mode 非法值：fail 反馈不抛异常，不打断现有任务
            ctx.checkNow("mode invalid no throw", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mode fly"));
            ctx.checkNow("mode invalid keeps task state",
                    ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
            // elytra：任务注册 + stop 复位（不真飞）
            BlockPos target = base.offset(5, 0, 5);
            ctx.checkNow("elytra command ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " elytra " + target.getX() + " " + target.getY() + " " + target.getZ()));
            ctx.checkNow("elytra task via command",
                    ctx.bot().navigate().currentTask() == NavigatorTask.ELYTRA);
            ctx.bot().navigate().stop();
            ctx.checkNow("elytra stopped", !ctx.bot().navigate().isActive());
            // mine：任务注册 + stop 复位（不真挖完）
            BlockPos below = base.below();
            ctx.checkNow("mine command ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mine " + below.getX() + " " + below.getY() + " " + below.getZ()));
            ctx.checkNow("mine task via command",
                    ctx.bot().navigate().currentTask() == NavigatorTask.MINE);
            ctx.bot().navigate().stop();
            ctx.checkNow("mine stopped", !ctx.bot().navigate().isActive());
            // follow：跟随附近村民；未知类型走失败反馈
            ctx.checkNow("follow command ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " follow villager"));
            ctx.checkNow("follow task via command",
                    ctx.bot().navigate().currentTask() == NavigatorTask.FOLLOW);
            ctx.bot().navigate().stop();
            ctx.checkNow("follow stopped", !ctx.bot().navigate().isActive());
            // follow 未知类型：fail 反馈不打断现有任务
            ctx.checkNow("follow unknown no throw", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " follow nonexistent"));
            ctx.checkNow("follow unknown keeps task state",
                    ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
        });
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "command"));
    }

    /** 假人相对起点（startPos）的水平位移。 */
    private double distanceFromStart(TestContext ctx) {
        double dx = ctx.bot().getLocalPlayer().getX() - (this.startPos.getX() + 0.5);
        double dz = ctx.bot().getLocalPlayer().getZ() - (this.startPos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
