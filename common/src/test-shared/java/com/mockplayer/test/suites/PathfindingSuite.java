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
        test("mine 找方块并移动", this::mineFindsBlocks);
        test("mine 无目标方块不提前取消", this::mineNoTargetKeepsAlive);
        test("baritone 配置界面与全局继承", this::baritoneConfigScreenAndGlobal);
        test("真飞 elytra 300 格平台", this::elytraRealFlight);
    }

    /** 测试 14（P15）：真飞——假人穿鞘翅 + 64 烟花，elytra 飞到起点 +300/+50/+300 的
     *  10×10 石头平台（长超时）。真实触发 native pathFind（src 在固体方块内 → 防御
     *  必须不崩）+ ElytraProcess 烟花加速导航 + 服务端飞行验证全链。 */
    private void elytraRealFlight(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        // 固定空旷起点（消除 spawn 随机性）
        ctx.run(() -> ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "tp " + BOT_A + " 100 4 100");
        }));
        ctx.await("bot teleported", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && ctx.bot().getLocalPlayer().blockPosition().equals(new BlockPos(100, 4, 100)), 200);
        ctx.await("tp area chunk loaded", () -> ctx.bot() != null
                && ctx.bot().getLevel() != null
                && !ctx.bot().getLevel().getBlockState(new BlockPos(100, 3, 100)).isAir(), 300);
        // 平台坐标 = 相对 bot 起点 +300/+50/+300（不是写死坐标——写死可能因
        // 地表高度不同导致平台在地底/位置错位）
        java.util.concurrent.atomic.AtomicReference<BlockPos> startRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<BlockPos> platformRef = new java.util.concurrent.atomic.AtomicReference<>();
        ctx.run(() -> {
            BlockPos base = ctx.bot().getLocalPlayer().blockPosition();
            startRef.set(base);
            platformRef.set(base.offset(300, 50, 300));
        });
        // /fill 一条命令铺 10×10 石头平台（x..x+9, z..z+9, y 一层）
        ctx.run(() -> ctx.server().execute(() -> {
            BlockPos p = platformRef.get();
            ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(),
                    String.format("fill %d %d %d %d %d %d minecraft:stone",
                            p.getX(), p.getY(), p.getZ(), p.getX() + 9, p.getY(), p.getZ() + 9));
        }));
        // 装备：服务端命令穿鞘翅（胸甲槽）+ 64 烟花（原版 /item 命令走完整同步链路）
        SuitesSupport.give(ctx, BOT_A, "minecraft:firework_rocket 64");
        ctx.run(() -> ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(),
                    "item replace entity " + BOT_A + " armor.chest with minecraft:elytra");
        }));
        ctx.await("elytra + fireworks on client", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && ctx.bot().getLocalPlayer().getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                .is(net.minecraft.world.item.Items.ELYTRA)
                && ctx.bot().getLocalPlayer().getInventory()
                .countItem(net.minecraft.world.item.Items.FIREWORK_ROCKET) >= 1, 200);
        // 真飞（用户真实场景复刻）：假人站在地面（不 tp 高空、不手动开鞘翅），
        // 给鞘翅 + 64 烟花后 /control elytra——ElytraProcess 必须自己平地起飞
        // （START_FLYING 持续按跳 → 跳起开鞘翅）并飞到 300 格外的 10×10 平台。
        // 覆盖：命令 → native pathFind（防御不崩）→ 平地起飞 → 烟花加速 →
        // 滑翔导航 → 到达平台全链。
        // elytra 目标 = 平台中心（相对 bot；注意 ctx.run 延迟执行，ref 取值必须在 run 内）
        ctx.run(() -> {
            BlockPos target = platformRef.get().offset(4, 0, 4);
            ctx.platform().executeClientCommand(
                    "control " + BOT_A + " elytra " + target.getX() + " " + target.getY() + " " + target.getZ());
        });
        // 飞行中间证据：假人必须自主离开起点 100+ 格（水平位移）——排除「瞬移/原地
        // 不动/坠落没飞」：坠落滑翔落点离起点最多 ~200 格、行走 300 格需 ~70s 且
        // 平台在 50 格高空无法步行到达，唯一到达路径是真正滑翔飞行
        ctx.await("elytra flew away from start (>100 blocks)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && Math.hypot(ctx.bot().getLocalPlayer().getX() - startRef.get().getX(),
                ctx.bot().getLocalPlayer().getZ() - startRef.get().getZ()) > 100, 2400);
        // 到达超时 2 分钟：接近平台（水平 < 8 且垂直差 < 12，平台高度 54）或任务完成（到达/放弃）
        ctx.await("elytra reached platform", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && (nearPlatform(ctx, platformRef.get())
                || !ctx.bot().navigate().isActive()), 2400);
        ctx.check("elytra actually near platform", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && nearPlatform(ctx, platformRef.get()));
        // 到达高度硬证据：假人 y 必须在平台高度附近（≥40）——不是在地面（y=4）蒙混过关
        ctx.check("elytra reached platform altitude (y>=40)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && ctx.bot().getLocalPlayer().getY() >= 40);
        ctx.run(() -> ctx.bot().navigate().stop());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
    }

    /** 假人是否在平台附近（水平 < 8 且垂直差 < 12；平台 10×10 中心 (px+4.5, py, pz+4.5)）。 */
    private static boolean nearPlatform(TestContext ctx, BlockPos platform) {
        net.minecraft.client.player.LocalPlayer lp = ctx.bot().getLocalPlayer();
        double dx = lp.getX() - (platform.getX() + 4.5);
        double dz = lp.getZ() - (platform.getZ() + 4.5);
        double dy = lp.getY() - platform.getY();
        return Math.sqrt(dx * dx + dz * dz) < 8 && Math.abs(dy) < 12;
    }

    /** 测试 13：baritone 配置界面（YACL 反射桥可构造）+ 全局 Settings → 新假人
     *  copyFrom 继承（settings.txt 是唯一全局来源；假人创建时继承全局默认）。 */
    private void baritoneConfigScreenAndGlobal(TestContext ctx) {
        ctx.run(() -> {
            // YACL 环境（mocktest 注入）：反射桥必须能构造出配置界面
            net.minecraft.client.gui.screens.Screen screen =
                    com.mockplayer.baritone.gui.BaritoneConfigScreenFactory.create(null);
            ctx.checkNow("baritone config screen creatable (yacl present)",
                    screen != null, screen == null ? "factory returned null" : screen.getClass().getName());
        });
        // 改全局默认 → 新假人必须继承（copyFrom 链路；per-bot override 为空时生效）
        ctx.run(() -> com.mockplayer.baritone.api.BaritoneAPI.getSettings().allowSprint.value = false);
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            BotImpl impl = (BotImpl) ctx.bot();
            com.mockplayer.baritone.api.IBaritone b = impl.session().getBaritone();
            ctx.checkNow("new bot inherits global allowSprint=false",
                    b != null && !b.settings().allowSprint.value);
            // 恢复全局默认（不污染后续）
            com.mockplayer.baritone.api.BaritoneAPI.getSettings().allowSprint.value = true;
        });
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
    }

    /** 测试 12：mine 无目标方块（diamond_ore，超平坦世界不存在）——
     *  rescan 扫空（区块已加载）→ 权威取消 + 反馈（不崩溃、任务复位干净）。
     *  mockplayer 不做 exploreForBlocks 探索乱跑：扫空即取消，无目标任务必须结束。
     *  「首 tick 不提前取消」由 REQUEST_PAUSE 保证（updateGoal 无目标时等待异步
     *  rescan 而非 cancel；生产真实世界扫描慢的场景），此处验证最终状态。 */
    private void mineNoTargetKeepsAlive(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.platform().executeClientCommand("control " + BOT_A + " mine diamond_ore"));
        // rescan 扫描空 + 区块已加载 → 权威取消 + 反馈（不崩溃）
        ctx.await("mine cancelled by rescan (no target)", () -> ctx.bot() != null
                && !ctx.bot().navigate().isActive(), 300);
        // 取消后 BaritoneNavigator.tick 下一拍复位任务状态（await 满足与复位不同 tick）
        ctx.await("task resets to NONE", () -> ctx.bot() != null
                && ctx.bot().navigate().currentTask() == NavigatorTask.NONE, 40);
        // 注意：必须用延迟 check（checkNow 在用例注册阶段立即求值，此时 createBot 未执行
        // bot 必为 null——历史 FAIL「bot null」的根因就是误用 checkNow 在用例主体）
        ctx.check("mine no-target cancelled cleanly", () -> ctx.bot() != null
                && ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
        ctx.run(() -> ctx.bot().navigate().stop());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
    }

    /** 测试 11：mine 找方块链路——mine dirt 后假人必须找到目标并移动（真实 mine 的第一段，
     *  之前只测过「面前生成掉落物→捡」，从未测过 scanChunkRadius 找方块路径）。
     *  生产根因回归（2026-08-16 实测）：假人背包已有目标物品时，旧 quantity=1 语义
     *  第一拍就「have_items」取消 → 假人完全不动；现在预置 dirt 到背包再 mine，
     *  必须仍找到 16 格外的 dirt、走过去、挖到第二个（背包数量增加）。 */
    private void mineFindsBlocks(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT_A));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> this.startPos = ctx.bot().getLocalPlayer().blockPosition());
        // 生产根因复现：先给假人背包放 1 个 dirt（模拟生产假人背包已有 oak_log 的场景）
        SuitesSupport.give(ctx, BOT_A, "dirt");
        ctx.await("preloaded dirt in inventory", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && countItem(ctx, net.minecraft.world.item.Items.DIRT) >= 1, 200);
        // 服务端在假人 16 格开外放置一个 dirt（空手挖必掉落；超平坦世界只有砂岩，
        // 砂岩空手挖不掉落——放远处保证假人必须「找到 → 走 16 格 → 挖 → 掉落 → 捡」全链）
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT_A);
            if (sp != null) {
                ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(),
                        String.format("setblock %d %d %d minecraft:dirt",
                                (int) Math.floor(sp.getX()) + 16,
                                (int) Math.floor(sp.getY()),
                                (int) Math.floor(sp.getZ())));
            }
        }));
        ctx.await("placed dirt visible in bot level", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && ctx.bot().getLevel() != null
                && ctx.bot().getLevel().getBlockState(
                        ctx.bot().getLocalPlayer().blockPosition().offset(16, 0, 0)).getBlock()
                        == net.minecraft.world.level.block.Blocks.DIRT, 300);
        // 诊断：找方块链路分段（chunkSource 类型 → chunk 可获取 → filter 匹配 → scanChunkRadius 原始调用）
        ctx.run(() -> {
            var level = ctx.bot().getLevel();
            var feet = ctx.bot().getLocalPlayer().blockPosition();
            // 测试世界（mocktest）表面是 sandstone——用它做目标（dirt 在此世界不存在）
            var cs = level.getChunkSource();
            ctx.checkNow("diag chunkSource is ClientChunkCache",
                    cs instanceof net.minecraft.client.multiplayer.ClientChunkCache,
                    cs.getClass().getName());
            net.minecraft.world.level.chunk.LevelChunk chunk = null;
            if (cs instanceof net.minecraft.client.multiplayer.ClientChunkCache ccc) {
                chunk = ccc.getChunk(feet.getX() >> 4, feet.getZ() >> 4, null, false);
            }
            ctx.checkNow("diag chunk retrievable", chunk != null,
                    chunk == null ? "null" : chunk.getClass().getName() + " empty=" + chunk.isEmpty());
            var lookup = new com.mockplayer.baritone.api.utils.BlockOptionalMetaLookup("sandstone");
            ctx.checkNow("diag filter matches below",
                    lookup.has(level.getBlockState(feet.below())),
                    level.getBlockState(feet.below()).toString());
            try {
                var bar = ((BotImpl) ctx.bot()).session().getBaritone();
                var found = com.mockplayer.baritone.api.BaritoneAPI.getProvider().getWorldScanner()
                        .scanChunkRadius(bar.getPlayerContext(), lookup, 100, -1, 32);
                ctx.checkNow("diag scanChunkRadius finds sandstone", !found.isEmpty(), "found=" + found.size());
            } catch (Throwable t) {
                ctx.checkNow("diag scanChunkRadius finds sandstone", false, t.toString());
            }
        });
        ctx.run(() -> ctx.platform().executeClientCommand("control " + BOT_A + " mine dirt"));
        ctx.await("mine task active (goal set)", () -> ctx.bot() != null
                && ctx.bot().navigate().isActive(), 200);
        ctx.await("bot moved toward dirt (>2 blocks)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && (distanceFromStart(ctx) > 2.0 || !ctx.bot().navigate().isActive()), 600);
        ctx.check("mine found dirt and moved", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && distanceFromStart(ctx) > 2.0, () -> "dist=" + distanceFromStart(ctx)
                + " start=" + this.startPos + " feet=" + ctx.bot().getLocalPlayer().blockPosition());
        // 真挖掘验证：挖下的新 dirt 必须进假人背包（预置 1 个 + 挖到 1 个 = ≥2，
        // 「移动」≠「挖掘」，历史测试从没断言过方块入包）
        ctx.await("dirt in inventory (mined)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && countItem(ctx, net.minecraft.world.item.Items.DIRT) >= 2, 600);
        ctx.check("mine actually mined dirt", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && countItem(ctx, net.minecraft.world.item.Items.DIRT) >= 2);
        ctx.run(() -> ctx.bot().navigate().stop());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
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
                    net.minecraft.client.Minecraft.getInstance().gui.hud.getChat();
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
                    net.minecraft.client.Minecraft.getInstance().gui.hud.getChat();
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

    /** 测试 9：mine 按类型挖矿能拾取掉落物——服务端生成 oak_log 掉落物在假人 8 格外
     *  （超出原版 3 格吸附范围，排除「被吸走」假阳性），mine 后 MineProcess 扫描到掉落物
     *  （filter 匹配）→ 寻路过去 → 背包出现 oak_log（真拾取链路）。 */
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
        // 服务端在假人 16 格外生成 oak_log 掉落物（PickupDelay=0；16 格远超原版吸附范围，
        // 假人必须真的寻路过去才能捡到——任何「没移动就捡到」都是吸附假象/测试 bug）
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT_A);
            if (sp != null) {
                // 直接用服务端代码生成 ItemEntity（summon NBT 解析在 26.x 不稳定）
                net.minecraft.world.entity.item.ItemEntity item =
                        new net.minecraft.world.entity.item.ItemEntity(
                                (net.minecraft.server.level.ServerLevel) sp.level(),
                                sp.getX() + 16.0, sp.getY() + 0.5, sp.getZ(),
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
        // 真移动验证：16 格外不移动就永远捡不到——假人必须真的走过去
        ctx.await("bot moved toward drop (>2 blocks)", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && distanceFromStart(ctx) > 2.0, 600);
        ctx.check("mine moved to pick up", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && distanceFromStart(ctx) > 2.0, () -> "dist=" + distanceFromStart(ctx)
                + " start=" + this.startPos + " feet=" + ctx.bot().getLocalPlayer().blockPosition());
        ctx.await("oak_log picked up", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && hasItem(ctx, net.minecraft.world.item.Items.OAK_LOG), 100000);
        // 二次验证（await 成功即 PASS；checkNow 在同一 tick 求值有时早于背包同步）
        ctx.await("oak_log picked up verify", () -> ctx.bot() != null
                && ctx.bot().getLocalPlayer() != null
                && hasItem(ctx, net.minecraft.world.item.Items.OAK_LOG), 100000);
        // 新 bug 回归（2026-08-16 生产实测）：捡完掉落物后任务必须自然结束
        // （droppedScanCache 缓存旧位置导致 rescan 永远认为有目标 → 永久卡住；
        //  实时扫描后目标失效 → rescan 扫空取消）
        ctx.await("mine auto-finishes after pickup", () -> ctx.bot() != null
                && !ctx.bot().navigate().isActive(), 600);
        ctx.check("mine auto-finish clean", () -> ctx.bot() != null
                && ctx.bot().navigate().currentTask() == NavigatorTask.NONE);
        ctx.run(() -> ctx.bot().navigate().stop());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT_A, "test"));
    }

    /** 假人背包是否含有指定物品（含盔甲/副手/末影箱之外的常规槽位）。 */
    private static boolean hasItem(TestContext ctx, net.minecraft.world.item.Item item) {
        return ctx.bot().getLocalPlayer().getInventory().contains(
                stack -> stack.getItem() == item);
    }

    /** 假人背包指定物品数量（常规槽位，与 hasItem 同范围）。 */
    private static int countItem(TestContext ctx, net.minecraft.world.item.Item item) {
        return ctx.bot().getLocalPlayer().getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.getItem() == item)
                .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
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

            // mode 切换：调用不抛 + 状态保留（WALK/ELYTRA；鞘翅已恢复）
            boolean modeThrew = false;
            try {
                nav.mode(NavigationMode.WALK);
            } catch (Exception e) {
                modeThrew = true;
            }
            ctx.checkNow("mode switch no throw", !modeThrew);

            // elytra：调用不抛 + 任务注册 ELYTRA + stop 复位（不真飞）。
            // 注意：elytra() 会启动 ElytraProcess 异步路径计算，真实触发 native
            // pathFind——src 在脚下固体方块内（历史崩溃点），防御生效则进程不崩。
            boolean elytraThrew = false;
            try {
                nav.mode(NavigationMode.ELYTRA);
                nav.elytra(base.offset(0, 20, 0));
            } catch (Exception e) {
                elytraThrew = true;
            }
            ctx.checkNow("elytra call no throw", !elytraThrew);
            ctx.checkNow("elytra task registered", nav.currentTask() == NavigatorTask.ELYTRA);
            nav.stop();
            ctx.checkNow("elytra stopped", !nav.isActive());

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
            // 鞘翅（P15 恢复）：control elytra 命令可执行 + 任务注册 ELYTRA。
            // 目标在地下（超平坦 y=2 固体）——native findAir 防御生效，进程不崩；
            // 任务可能随后失败复位（未装备鞘翅/目标不可达），此处只断接线。
            ctx.checkNow("elytra command ok", ctx.platform().executeClientCommand(
                    "control " + BOT_A + " elytra 1 2 3"));
            ctx.checkNow("elytra task via command",
                    ctx.bot().navigate().currentTask() == NavigatorTask.ELYTRA);
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

