package com.mockplayer.test.framework;

import com.mockplayer.api.Bot;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.test.suites.BatchSuite;
import com.mockplayer.test.suites.BotGuiSuite;
import com.mockplayer.test.suites.CombatStabSuite;
import com.mockplayer.test.suites.CombatSprintSuite;
import com.mockplayer.test.suites.ContainerSuite;
import com.mockplayer.test.suites.ContainersAllSuite;
import com.mockplayer.test.suites.ConfigSuite;
import com.mockplayer.test.suites.ControlCommandsSuite;
import com.mockplayer.test.suites.CraftingSuite;
import com.mockplayer.test.suites.DebugNameTagSuite;
import com.mockplayer.test.suites.EnchantingSuite;
import com.mockplayer.test.suites.FurnaceSuite;
import com.mockplayer.test.suites.GuiActionsSuite;
import com.mockplayer.test.suites.ListenerEventsSuite;
import com.mockplayer.test.suites.MerchantSuite;
import com.mockplayer.test.suites.UseItemsSuite;
import com.mockplayer.test.suites.ApiSmokeSuite;
import com.mockplayer.test.suites.MemoryAccountingSuite;
import com.mockplayer.test.suites.ApiFullSuite;
import com.mockplayer.test.suites.PayloadInterceptionSuite;
import com.mockplayer.test.suites.PathfindingSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.MinecraftServer.MultiplayerScope;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 新框架执行器：主菜单 → 清旧档+建超平坦世界 → 单机就绪+开局域网+锁 gamerules →
 * 套件循环（sanitize → before → 用例逐个 tick 驱动 → after）→ 结果 JSON → 自动退出。
 *
 * 隔离策略（不重建世界）：套件前删全部假人 + kill 非玩家实体 + 配置复位 + 40 tick 冷却；
 * 用例间不共享任何字段（每个用例独立 TestContext）。
 */
public final class SuiteRunner {

    private enum Phase { WAIT_TITLE, WAIT_WORLD, RUN, DONE }

    // 300s：P15 真飞用例（elytra 300 格平台）需要长超时（起飞/爬升/飞行/降落）
    private static final long PHASE_TIMEOUT_MS = 300_000;
    /** 全流程总预算（双上限：单阶段 180s + 全流程 20min，防止 all 模式无限拖）。 */
    private static final long TOTAL_TIMEOUT_MS = 20 * 60_000L;

    /** 已迁移套件注册表：迁移一个套件就加进这里，同时从旧 TestRunner 删除对应 case。 */
    private static final List<TestSuite> SUITES = List.of(
            new ApiSmokeSuite(),
            new ConfigSuite(),
            new BatchSuite(),
            new DebugNameTagSuite(),
            new CraftingSuite(),
            new FurnaceSuite(),
            new EnchantingSuite(),
            new MerchantSuite(),
            new CombatStabSuite(),
            new CombatSprintSuite(),
            new ContainerSuite(),
            new ContainersAllSuite(),
            new ApiFullSuite(),
            new UseItemsSuite(),
            new GuiActionsSuite(),
            new ListenerEventsSuite(),
            new ControlCommandsSuite(),
            new BotGuiSuite(),
            new PayloadInterceptionSuite(),
            new PathfindingSuite());

    /**
     * 特殊套件：不随 all 运行（太特殊且校准太卡，如内存校准），
     * 只允许显式 -Psuite=memory-accounting 单独运行。
     */
    private static final List<TestSuite> SPECIAL_SUITES = List.of(
            new MemoryAccountingSuite());

    private static Phase phase = Phase.WAIT_TITLE;
    private static volatile long phaseStart;
    private static boolean worldCreationStarted;
    private static boolean gamerulesApplied;
    private static int testLanPort = -1;
    private static TestPlatform platform;
    private static List<TestSuite> queue;
    private static int suiteIndex;
    private static TestSuite suite;
    private static int caseIndex;
    private static TestContext ctx;
    private static final List<TestContext.Record> records = new ArrayList<>();
    private static int suiteCooldown;
    private static long suiteStart;
    private static volatile boolean finished;
    private static boolean sanitized;
    private static long totalStart;

    private SuiteRunner() {
    }

    /** 该套件是否已迁移到新框架（双端入口路由用：迁移的走新框架，未迁移的走旧 TestRunner）。 */
    public static boolean isMigrated(String suiteName) {
        return Stream.concat(SUITES.stream(), SPECIAL_SUITES.stream())
                .anyMatch(s -> s.name().equals(suiteName));
    }

    /** 客户端 tick 入口：platform 由双端 testmod 传入，suiteName 与旧入口一致（all / 套件名）。 */
    public static void tick(Minecraft mc, String suiteName, TestPlatform p) {
        if (phase == Phase.DONE) {
            return;
        }
        if (platform == null) {
            startHardWatchdog();
            platform = p;
            queue = "all".equals(suiteName) ? new ArrayList<>(SUITES)
                    : List.of(Stream.concat(SUITES.stream(), SPECIAL_SUITES.stream())
                    .filter(s -> s.name().equals(suiteName)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown suite: " + suiteName)));
            suiteIndex = 0;
            suite = queue.get(0);
            suiteStart = System.currentTimeMillis();
            phaseStart = suiteStart;
            totalStart = suiteStart;
        }
        long now = System.currentTimeMillis();
        advance(mc);
        if (phase != Phase.DONE && now - phaseStart > PHASE_TIMEOUT_MS) {
            records.add(new TestContext.Record("timeout @" + phase, false, "global timeout"));
            finishAll();
        } else if (phase != Phase.DONE && now - totalStart > TOTAL_TIMEOUT_MS) {
            records.add(new TestContext.Record("timeout @total", false, "total timeout"));
            finishAll();
        }
    }

    /**
     * 硬超时 watchdog：即使客户端 tick 被某个步骤阻塞（同步 run 卡死），
     * 超过全局超时也强制终止进程并报错，保证 CI 永不挂死。
     */
    private static void startHardWatchdog() {
        Thread t = new Thread(() -> {
            while (!finished) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - phaseStart > PHASE_TIMEOUT_MS) {
                    System.err.println("[mocktest] FATAL: global timeout exceeded, forcing exit");
                    Runtime.getRuntime().halt(1);
                }
            }
        }, "mocktest-hard-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 关客户端；stop 卡住时 15 秒后强制退出兜底。 */
    private static void exitGame() {
        if (finished) {
            return;
        }
        finished = true;
        Minecraft.getInstance().stop();
        new Thread(() -> {
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "mocktest-exit-watchdog").start();
    }

    /** 全部完成（全局超时路径）：写结果、打印、关客户端。 */
    private static void finishAll() {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        writeResultJson(System.currentTimeMillis() - suiteStart);
        boolean passed = records.stream().allMatch(TestContext.Record::passed);
        System.out.println("[mocktest] suite " + suite.name() + " " + (passed ? "PASSED" : "FAILED")
                + " (" + records.size() + " checks)");
        exitGame();
    }

    private static void advance(Minecraft mc) {
        switch (phase) {
            case WAIT_TITLE -> {
                if (mc.level != null) {
                    phase = Phase.WAIT_WORLD;
                    phaseStart = System.currentTimeMillis();
                } else if (!worldCreationStarted) {
                    worldCreationStarted = true;
                    phaseStart = System.currentTimeMillis();
                    deleteOldWorld(mc);
                    createWorld(mc);
                }
            }
            case WAIT_WORLD -> {
                if (mc.getSingleplayerServer() != null && mc.level != null && mc.player != null) {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (!gamerulesApplied) {
                        gamerulesApplied = true;
                        applyTestGameRules(server);
                    }
                    if (!server.isPublished()) {
                        if (testLanPort < 0) {
                            testLanPort = findFreeTestPort();
                        }
                        server.publishServer(MultiplayerScope.LAN, GameType.SURVIVAL, false, testLanPort);
                    }
                    if (server.isPublished() && server.getPort() > 0) {
                        phase = Phase.RUN;
                        phaseStart = System.currentTimeMillis();
                    }
                }
            }
            case RUN -> run(mc);
        }
    }

    private static void run(Minecraft mc) {
        if (suiteCooldown > 0) {
            suiteCooldown--;
            return;
        }
        if (caseIndex == 0 && !sanitized) {
            // 套件开始：sanitize（删全部假人 + kill 非玩家实体 + 配置复位）
            sanitize(mc.getSingleplayerServer());
            records.clear();
            suite.before();
            sanitized = true;
        }
        if (ctx == null) {
            var cs = suite.cases();
            if (caseIndex >= cs.size()) {
                finishSuite();
                return;
            }
            var tc = cs.get(caseIndex);
            ctx = new TestContext();
            ctx.setPlatform(platform);
            System.out.println("[mocktest] case " + suite.name() + "/" + tc.name());
            tc.body().accept(ctx);
        }
        ctx.tick();
        if (ctx.failed()) {
            // 任一断言失败/等待超时：立即停止游戏，不再继续后续用例与套件
            records.addAll(ctx.records());
            finishAll();
            return;
        }
        if (ctx.isDone()) {
            records.addAll(ctx.records());
            ctx = null;
            caseIndex++;
        }
    }

    private static void sanitize(MinecraftServer server) {
        for (Bot b : MockplayerApi.bots().getBots()) {
            MockplayerApi.bots().removeBot(b.getName(), "command");
        }
        MockplayerConfig.save(new ModConfig());
        // kill 必须等执行完再进套件 before（否则首用例可能先跑，清理竞态）
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack(), "kill @e[type=!minecraft:player]");
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                System.out.println("[mocktest] sanitize kill timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void finishSuite() {
        suite.after();
        long elapsed = System.currentTimeMillis() - suiteStart;
        writeResultJson(elapsed);
        boolean passed = records.stream().allMatch(TestContext.Record::passed);
        System.out.println("[mocktest] suite " + suite.name() + " " + (passed ? "PASSED" : "FAILED")
                + " (" + records.size() + " checks) in " + elapsed + "ms");
        suiteIndex++;
        if (suiteIndex < queue.size()) {
            suite = queue.get(suiteIndex);
            caseIndex = 0;
            ctx = null;
            sanitized = false;
            records.clear();
            suiteCooldown = 40;
            suiteStart = System.currentTimeMillis();
            phaseStart = suiteStart;
        } else {
            phase = Phase.DONE;
            exitGame();
        }
    }

    /** 写当前套件结果 JSON（test-results/<suite>.json，格式兼容旧消费方）。 */
    private static void writeResultJson(long durationMs) {
        boolean passed = records.stream().allMatch(TestContext.Record::passed);
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"suite\": \"").append(suite.name()).append("\",\n");
        json.append("  \"passed\": ").append(passed).append(",\n");
        json.append("  \"duration_ms\": ").append(durationMs).append(",\n");
        json.append("  \"results\": [\n");
        for (int i = 0; i < records.size(); i++) {
            TestContext.Record r = records.get(i);
            json.append("    {\"name\": \"").append(jsonEscape(r.name()))
                    .append("\", \"status\": \"").append(r.passed() ? "PASS" : "FAIL")
                    .append("\", \"detail\": \"").append(jsonEscape(r.detail()))
                    .append("\"}");
            if (i < records.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n}\n");
        try {
            File dir = new File("test-results");
            if (!dir.exists() && !dir.mkdirs()) {
                System.out.println("[mocktest] cannot create test-results dir");
            }
            File out = new File(dir, suite.name() + ".json");
            try (FileWriter w = new FileWriter(out, StandardCharsets.UTF_8)) {
                w.write(json.toString());
            }
            System.out.println("[mocktest] wrote " + out.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[mocktest] failed to write result: " + e);
        }
    }

    /** JSON 字符串转义（引号/反斜杠/换行/控制字符，保证结果文件永远是合法 JSON）。 */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static int findFreeTestPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return 25566;
        }
    }

    private static void deleteOldWorld(Minecraft mc) {
        try {
            var source = mc.getLevelSource();
            if (source.levelExists("mocktest")) {
                Path levelPath = source.getLevelPath("mocktest");
                System.out.println("[mocktest] deleting old world: " + levelPath);
                try (var walk = Files.walk(levelPath)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (java.io.IOException ignored) {
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.out.println("[mocktest] failed to delete old world: " + e);
        }
    }

    private static void applyTestGameRules(MinecraftServer server) {
        var rules = server.getGameRules();
        rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
        rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
        rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
        System.out.println("[mocktest] test gamerules applied (advance_time/advance_weather/spawn_mobs = false)");
    }

    private static void createWorld(Minecraft mc) {
        System.out.println("[mocktest] creating singleplayer world 'mocktest' (flat)");
        mc.createWorldOpenFlows().createFreshLevel(
                "mocktest",
                new LevelSettings("mocktest", GameType.SURVIVAL,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                new WorldOptions(0L, false, false),
                WorldPresets::createTestWorldDimensions,
                null);
    }
}
