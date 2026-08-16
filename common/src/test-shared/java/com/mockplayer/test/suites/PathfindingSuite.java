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
        test("销毁清理", this::destroyCleanup);
        test("API 全方法接线", this::apiAllMethodsWiring);
        test("命令层 mode/mine/follow", this::commandLayerWiring);
        test("渲染默认 F3_ONLY 不渲染", this::renderDefaultOff);
        test("mine 拾取掉落物", this::minePicksUpDrops);
        test("baritone 日志开关", this::baritoneLogSwitches);
    }

    /** 测试 10：baritone 日志四开关默认关 + config set 链路生效 + 调用不崩。 */
    private void baritoneLogSwitches(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            BotImpl impl = (BotImpl) ctx.bot();
            var settings = impl.session().getBaritone().settings();
            // 四个开关默认全关
            ctx.checkNow("log switches default off",
                    !settings.logToChat.value && !settings.logDebugToChat.value
                            && !settings.logNotificationToChat.value && !settings.logToastToChat.value);
            // /control config set 链路生效（baritone 侧设置）
            ctx.platform().executeClientCommand("control " + BOT_A + " config set logToChat true");
            ctx.checkNow("logToChat set via command", settings.logToChat.value);
            // 开关开：logDirect 走聊天分支 → 主玩家聊天出现 [baritone-mockplayer-<bot名>] 前缀 + 翻译文本
            // （翻译 key 不裸露 = 已走语言文件；占位符带参数 = 不显示原文模板）
            com.mockplayer.baritone.api.utils.Helper.CURRENT_BOT.set(impl.session().getBaritone());
            try {
                com.mockplayer.baritone.api.utils.Helper.HELPER.logDirect(
                        net.minecraft.network.chat.Component.translatableEscape(
                                "baritone.log.mine.no_path_cancel", "minecraft:oak_log"));
            } finally {
                com.mockplayer.baritone.api.utils.Helper.CURRENT_BOT.remove();
            }
            String last = lastChatText();
            ctx.checkNow("logToChat shows prefixed translated message", last != null
                    && last.contains("[baritone-mockplayer-" + BOT_A + "]")
                    && !last.contains("baritone.log.mine.no_path_cancel"));
            // 开关关：debug 分支，聊天不新增消息
            ctx.platform().executeClientCommand("control " + BOT_A + " config reset logToChat");
            ctx.checkNow("logToChat reset via command", !settings.logToChat.value);
            int countBefore = chatMessageCount();
            com.mockplayer.baritone.api.utils.Helper.CURRENT_BOT.set(impl.session().getBaritone());
            try {
                com.mockplayer.baritone.api.utils.Helper.HELPER.logDirect(
                        net.minecraft.network.chat.Component.translatableEscape(
                                "baritone.log.mine.no_path_cancel", "minecraft:oak_log"));
            } finally {
                com.mockplayer.baritone.api.utils.Helper.CURRENT_BOT.remove();
            }
            ctx.checkNow("logToChat off no chat message", chatMessageCount() == countBefore);
        });
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
    }

    /** 主玩家聊天消息总数（反射读 ChatComponent.allMessages）。 */
    private static int chatMessageCount() {
        try {
            net.minecraft.client.gui.components.ChatComponent chat =
                    net.minecraft.client.Minecraft.getInstance().gui.getChat();
            java.lang.reflect.Field f = net.minecraft.client.gui.components.ChatComponent.class
                    .getDeclaredField("allMessages");
            f.setAccessible(true);
            return ((java.util.List<?>) f.get(chat)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 主玩家聊天最近一条消息文本（反射读 ChatComponent.allMessages 最新一条的 content）。 */
    private static String lastChatText() {
        try {
            net.minecraft.client.gui.components.ChatComponent chat =
                    net.minecraft.client.Minecraft.getInstance().gui.getChat();
            java.lang.reflect.Field f = net.minecraft.client.gui.components.ChatComponent.class
                    .getDeclaredField("allMessages");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage> msgs =
                    (java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage>) f.get(chat);
            if (msgs == null || msgs.isEmpty()) {
                return null;
            }
            return msgs.get(0).content().getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 测试 9：mine 按类型挖矿能拾取掉落物——服务端生成 oak_log 掉落物在假人面前，
     *  mine 后 MineProcess 扫描到掉落物（filter 匹配）→ 寻路过去 → 背包出现 oak_log。 */
    private void minePicksUpDrops(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        // 传送到空旷固定位置（消除 spawn 随机性导致的不稳定：村庄/主玩家附近会挡路）
        ctx.run(() -> ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(),
                    "tp " + BOT_A + " 100 4 100");
        }));
        ctx.await("bot teleported", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && ctx.bot().getLocalPlayer().blockPosition().equals(new BlockPos(100, 4, 100)), 200);
        // 等待 tp 区域区块数据写入假人 level（寻路 BSI 依赖；位置同步 ≠ 区块就绪）
        ctx.await("tp area chunk loaded", () -> ctx.bot() != null
                && ctx.bot().getLevel() != null
                && !ctx.bot().getLevel().getBlockState(new BlockPos(100, 3, 100)).isAir()
                && !ctx.bot().getLevel().getBlockState(new BlockPos(103, 3, 100)).isAir(), 300);
        ctx.run(() -> this.startPos = ctx.bot().getLocalPlayer().blockPosition());
        // 服务端在假人面前生成 oak_log 掉落物（PickupDelay=0，可立即捡）
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT_A);
            if (sp != null) {
                // 直接用服务端代码生成 ItemEntity（summon NBT 解析在 26.x 不稳定）
                net.minecraft.world.entity.item.ItemEntity item =
                        new net.minecraft.world.entity.item.ItemEntity(
                                (net.minecraft.server.level.ServerLevel) sp.level(),
                                sp.getX() + 3.0, sp.getY() + 0.5, sp.getZ(),
                                new net.minecraft.world.item.ItemStack(
                                        net.minecraft.world.item.Items.OAK_LOG));
                item.setPickUpDelay(0);
                ((net.minecraft.server.level.ServerLevel) sp.level()).addFreshEntity(item);
            }
        }));
        // 诊断 1：掉落物必须进入假人 level（AddEntity 包 → handleAddEntity → level.addEntity）
        ctx.await("dropped item in bot level", () -> {
            if (ctx.bot() == null) {
                return false;
            }
            java.util.List<net.minecraft.world.entity.Entity> near =
                    ctx.bot().getEntitiesNear(32);
            return near.stream().anyMatch(e -> e instanceof net.minecraft.world.entity.item.ItemEntity);
        }, 400);
        ctx.run(() -> {
            java.util.List<net.minecraft.world.entity.Entity> near = ctx.bot().getEntitiesNear(32);
            ctx.checkNow("dropped item visible to bot", near.stream()
                    .anyMatch(e -> e instanceof net.minecraft.world.entity.item.ItemEntity));
        });
        // 诊断 2：drops 反射链路——filter 必须匹配 oak_log 物品（stackHashes 非空）
        ctx.run(() -> {
            boolean matched = false;
            try {
                com.mockplayer.baritone.api.utils.BlockOptionalMetaLookup lookup =
                        new com.mockplayer.baritone.api.utils.BlockOptionalMetaLookup("minecraft:oak_log");
                matched = lookup.has(new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.OAK_LOG));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            ctx.checkNow("filter matches oak_log item", matched);
            // 联网路径数据源：vanilla pack 完整加载（ServerLevelStub.load）必须含原版 loot tables
            try {
                var loaded = com.mockplayer.baritone.api.utils.BlockOptionalMeta.ServerLevelStub.load();
                var reg = loaded.join().lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE);
                ctx.checkNow("vanilla pack has oak_log loot table",
                        reg.getValue(net.minecraft.world.level.block.Blocks.OAK_LOG
                                .getLootTable().orElseThrow()) != null);
            } catch (Throwable t) {
                t.printStackTrace();
                ctx.checkNow("vanilla pack has oak_log loot table", false);
            }
        });
        // mine oak_log：MineProcess 把掉落物位置加入目标 → 假人走过去捡起
        ctx.run(() -> ctx.platform().executeClientCommand(
                "control " + BOT_A + " mine minecraft:oak_log"));
        ctx.await("mine task active", () -> ctx.bot() != null
                && ctx.bot().navigate().isActive(), 100);
        ctx.await("oak_log picked up", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && hasItem(ctx, net.minecraft.world.item.Items.OAK_LOG), 100000);
        // 二次验证（await 成功即 PASS；checkNow 在同一 tick 求值有时早于背包同步）
        ctx.await("oak_log picked up verify", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && hasItem(ctx, net.minecraft.world.item.Items.OAK_LOG), 100000);
        ctx.run(() -> ctx.bot().navigate().stop());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
    }

    /** 假人背包是否含有指定物品（含盔甲/副手/末影箱之外的常规槽位）。 */
    private static boolean hasItem(TestContext ctx, net.minecraft.world.item.Item item) {
        return ctx.bot().getLocalPlayer().getInventory().contains(
                stack -> stack.getItem() == item);
    }

    /** 测试 8：默认 F3_ONLY 下（F3 关）渲染闸门为 false（回归：原每 tick 同步首次跳过导致一直渲染）。 */
    private void renderDefaultOff(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            // bot 上线走 SessionManager.tick → ensureRenderGate 已注册；F3 关闭时 F3_ONLY 必须不渲染
            ctx.checkNow("render gate off by default (F3 off)",
                    !com.mockplayer.baritone.utils.RenderGate.shouldRender());
        });
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

            // mode 切换：调用不抛 + 状态保留（仅 walk；鞘翅已移除）
            boolean modeThrew = false;
            try {
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

    /** 测试 7：命令层接线——/control mode/mine/follow 执行后任务状态正确（鞘翅已移除）。 */
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
            // mode：walk 切换（不启动任务，仅验证命令可执行）
            ctx.checkNow("mode walk ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mode walk"));
            // mode 非法值：fail 反馈不抛异常，不打断现有任务
            ctx.checkNow("mode invalid no throw", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mode fly"));
            ctx.checkNow("mode invalid keeps task state",
                    ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
            // 鞘翅已移除：control elytra 命令不存在，执行失败且不启动任务
            ctx.checkNow("elytra command removed", !ctx.platform().executeClientCommand(
                    "control " + BOT_A + " elytra 1 2 3"));
            ctx.checkNow("elytra removed keeps task state",
                    ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
            // mine：按类型挖矿任务注册 + stop 复位（不真挖完）
            ctx.checkNow("mine command ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mine dirt"));
            ctx.checkNow("mine task via command",
                    ctx.bot().navigate().currentTask() == NavigatorTask.MINE);
            ctx.bot().navigate().stop();
            ctx.checkNow("mine stopped", !ctx.bot().navigate().isActive());
            // 未知方块：失败反馈且不启动任务
            ctx.checkNow("mine unknown block no throw", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mine nonexistent_block_xyz"));
            ctx.checkNow("mine unknown block keeps task state",
                    ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
            // mine 带 namespace 前缀（resource location 参数，minecraft:oak_log 可解析）
            ctx.checkNow("mine with namespace ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " mine minecraft:oak_log"));
            ctx.checkNow("mine with namespace task",
                    ctx.bot().navigate().currentTask() == NavigatorTask.MINE);
            ctx.bot().navigate().stop();
            ctx.checkNow("mine with namespace stopped", !ctx.bot().navigate().isActive());
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
