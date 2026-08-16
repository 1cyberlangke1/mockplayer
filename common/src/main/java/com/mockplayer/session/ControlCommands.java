package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.container.BotContainer;
import com.mockplayer.api.navigate.NavigationMode;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * /control 命令集的执行逻辑与 Tab 补全（平台无关）——只包含「动作」命令。
 *
 * 动作命令全部走 {@link com.mockplayer.api.action.BotActions}（真实网络包路径）；
 * 查询命令已迁移到 {@link QueryCommands}（/query 顶层命令，语义分离）。
 * 所有反馈消息走语言文件（en_us / zh_cn），颜色在代码端用 ChatFormatting 设置。
 *
 * 设计约束（主人拍板 2026-08-08）：
 * - 不做客户端全局身份切换（不碰 Minecraft.player/gameMode/level）
 * - 全部参数可 Tab 补全
 */
public class ControlCommands {

    // ===== 命令树数据化（P4-1）：SPECS 是唯一真相，ACTIONS/命令树都由它生成 =====

    /** 参数描述：名字 + Brigadier 类型 + 可选 Tab 补全。 */
    private record ArgSpec(String name, com.mojang.brigadier.arguments.ArgumentType<?> type,
                           com.mojang.brigadier.suggestion.SuggestionProvider<?> suggests) {
    }

    /** 命令描述：名字 + 参数链变体（每个变体 = 一种参数组合）+ 执行器（返回反馈组件）。 */
    private record CommandSpec(String name, List<List<ArgSpec>> variants,
                               java.util.function.Function<CommandContext<?>, Component> executor) {
    }

    private static CommandSpec spec(String name, List<List<ArgSpec>> variants,
                                    java.util.function.Function<CommandContext<?>, Component> executor) {
        return new CommandSpec(name, variants, executor);
    }

    private static ArgSpec word(String name) {
        return new ArgSpec(name, StringArgumentType.word(), null);
    }

    private static ArgSpec word(String name, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, StringArgumentType.word(), suggests);
    }

    private static ArgSpec greedy(String name) {
        return new ArgSpec(name, StringArgumentType.greedyString(), null);
    }

    private static ArgSpec greedy(String name, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, StringArgumentType.greedyString(), suggests);
    }

    private static ArgSpec integer(String name) {
        return new ArgSpec(name, IntegerArgumentType.integer(), null);
    }

    private static ArgSpec integer(String name, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, IntegerArgumentType.integer(), suggests);
    }

    private static ArgSpec integer(String name, int min) {
        return new ArgSpec(name, IntegerArgumentType.integer(min), null);
    }

    private static ArgSpec integer(String name, int min, int max) {
        return new ArgSpec(name, IntegerArgumentType.integer(min, max), null);
    }

    private static ArgSpec integer(String name, int min, int max, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, IntegerArgumentType.integer(min, max), suggests);
    }

    private static ArgSpec floatArg(String name) {
        return new ArgSpec(name, FloatArgumentType.floatArg(), null);
    }

    private static ArgSpec floatArg(String name, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, FloatArgumentType.floatArg(), suggests);
    }

    private static ArgSpec floatArg(String name, float min, float max) {
        return new ArgSpec(name, FloatArgumentType.floatArg(min, max), null);
    }

    private static ArgSpec floatArg(String name, float min, float max, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, FloatArgumentType.floatArg(min, max), suggests);
    }

    private static ArgSpec doubleArg(String name, SuggestionProvider<?> suggests) {
        return new ArgSpec(name, DoubleArgumentType.doubleArg(), suggests);
    }

    /** 参数链变体集合（一个命令可有多种参数组合，如 drop/attack）。 */
    @SafeVarargs
    private static List<List<ArgSpec>> variants(List<ArgSpec>... vs) {
        return List.of(vs);
    }

    /** 一组参数（空 = 无参数变体）。 */
    private static List<ArgSpec> v(ArgSpec... args) {
        return List.of(args);
    }

    // ===== 参数读取辅助（执行器内提取 ctx 参数；可选参数缺失返回 null/默认） =====

    private static String name(CommandContext<?> ctx) {
        return StringArgumentType.getString(ctx, "player");
    }

    private static String str(CommandContext<?> ctx, String key) {
        return StringArgumentType.getString(ctx, key);
    }

    private static String strOrNull(CommandContext<?> ctx, String key) {
        try {
            return StringArgumentType.getString(ctx, key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int i(CommandContext<?> ctx, String key) {
        return IntegerArgumentType.getInteger(ctx, key);
    }

    private static Integer iOrNull(CommandContext<?> ctx, String key) {
        try {
            return IntegerArgumentType.getInteger(ctx, key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static float f(CommandContext<?> ctx, String key) {
        return FloatArgumentType.getFloat(ctx, key);
    }

    private static double d(CommandContext<?> ctx, String key) {
        return DoubleArgumentType.getDouble(ctx, key);
    }

    /** 全部动作命令声明（顺序 = help 展示顺序，也决定 ACTIONS 与命令树结构）。 */
    private static final List<CommandSpec> SPECS = List.of(
            spec("move", variants(v(word("dir", directions()))),
                    ctx -> move(name(ctx), str(ctx, "dir"))),
            spec("stop", variants(v()),
                    ctx -> stop(name(ctx))),
            spec("sneak", variants(v()),
                    ctx -> setSneak(name(ctx), true)),
            spec("unsneak", variants(v()),
                    ctx -> setSneak(name(ctx), false)),
            spec("sprint", variants(v()),
                    ctx -> setSprint(name(ctx), true)),
            spec("unsprint", variants(v()),
                    ctx -> setSprint(name(ctx), false)),
            spec("jump", variants(v()),
                    ctx -> jump(name(ctx))),
            spec("look", variants(v(
                            floatArg("yaw", yawNow()),
                            floatArg("pitch", -90.0F, 90.0F, pitchNow()))),
                    ctx -> look(name(ctx), f(ctx, "yaw"), f(ctx, "pitch"))),
            spec("lookAt", variants(v(
                            doubleArg("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            doubleArg("y", CommandSupport.coordY("commands.mockplayer.control.suggest.y")),
                            doubleArg("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")))),
                    ctx -> lookAt(name(ctx), d(ctx, "x"), d(ctx, "y"), d(ctx, "z"))),
            spec("turn", variants(v(
                            floatArg("yaw"),
                            floatArg("pitch", -90.0F, 90.0F))),
                    ctx -> turn(name(ctx), f(ctx, "yaw"), f(ctx, "pitch"))),
            spec("attack", variants(
                            v(),
                            v(word("target", entityTypes()))),
                    ctx -> attack(name(ctx), strOrNull(ctx, "target"))),
            spec("stab", variants(v()),
                    ctx -> stab(name(ctx))),
            spec("sustainedAttack", variants(
                            v(),
                            v(word("target", entityTypes()))),
                    ctx -> sustainedAttack(name(ctx), strOrNull(ctx, "target"))),
            spec("sustainedUse", variants(
                            v(),
                            v(word("target", entityTypes()))),
                    ctx -> sustainedUse(name(ctx), strOrNull(ctx, "target"))),
            spec("attackLook", variants(v()),
                    ctx -> attackLook(name(ctx))),
            spec("useLook", variants(v()),
                    ctx -> useLook(name(ctx))),
            spec("sustainedAttackLook", variants(v()),
                    ctx -> sustainedAttackLook(name(ctx))),
            spec("sustainedUseLook", variants(v()),
                    ctx -> sustainedUseLook(name(ctx))),
            spec("stopSustained", variants(v()),
                    ctx -> stopSustained(name(ctx))),
            spec("interact", variants(
                            v(),
                            v(word("target", entityTypes()))),
                    ctx -> interact(name(ctx), strOrNull(ctx, "target"))),
            spec("useItem", variants(
                            v(),
                            v(word("hand", hands()))),
                    ctx -> useItem(name(ctx), strOrNull(ctx, "hand"))),
            spec("releaseUsingItem", variants(v()),
                    ctx -> releaseUsingItem(name(ctx))),
            spec("useItemOn", variants(v(
                            integer("x"),
                            integer("y"),
                            integer("z"),
                            word("side", sides()))),
                    ctx -> useItemOn(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"), str(ctx, "side"))),
            spec("placeBlock", variants(v(
                            integer("x"),
                            integer("y"),
                            integer("z"),
                            word("side", sides()))),
                    ctx -> placeBlock(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"), str(ctx, "side"))),
            spec("mineBlock", variants(v(
                            integer("x"),
                            integer("y"),
                            integer("z"))),
                    ctx -> mineBlock(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"))),
            spec("attackBlock", variants(v(
                            integer("x"),
                            integer("y"),
                            integer("z"))),
                    ctx -> attackBlock(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"))),
            spec("hotbar", variants(v(
                            integer("slot", 1, 9,
                                    CommandSupport.fixed("1", "2", "3", "4", "5", "6", "7", "8", "9")))),
                    ctx -> hotbar(name(ctx), i(ctx, "slot"))),
            spec("chunkRadius", variants(v(
                            integer("radius",
                                    com.mockplayer.config.ModConfig.MIN_FAKE_PLAYER_CHUNK_RADIUS,
                                    com.mockplayer.config.ModConfig.MAX_FAKE_PLAYER_CHUNK_RADIUS))),
                    ctx -> chunkRadius(name(ctx), i(ctx, "radius"))),
            spec("drop", variants(
                            v(),
                            v(integer("slot", 0, 8,
                                    CommandSupport.fixed("0", "1", "2", "3", "4", "5", "6", "7", "8"))),
                            v(integer("slot", 0, 8,
                                    CommandSupport.fixed("0", "1", "2", "3", "4", "5", "6", "7", "8")),
                                    word("mode", oneAll()))),
                    ctx -> drop(name(ctx), iOrNull(ctx, "slot"), "all".equals(strOrNull(ctx, "mode")))),
            spec("swapHands", variants(v()),
                    ctx -> swapHands(name(ctx))),
            spec("mount", variants(
                            v(),
                            v(word("target", mountTargets()))),
                    ctx -> {
                        String target = strOrNull(ctx, "target");
                        if (target == null) {
                            return mount(name(ctx), null, null);
                        }
                        if ("rideables".equalsIgnoreCase(target) || "anything".equalsIgnoreCase(target)) {
                            return mount(name(ctx), null, "rideables".equalsIgnoreCase(target));
                        }
                        return mount(name(ctx), target, null);
                    }),
            spec("dismount", variants(v()),
                    ctx -> dismount(name(ctx))),
            spec("close", variants(v()),
                    ctx -> close(name(ctx))),
            spec("click", variants(v(
                            integer("slot", 0, Integer.MAX_VALUE, containerSlots()),
                            integer("button", 0, 2),
                            word("mode", clickModes()))),
                    ctx -> click(name(ctx), i(ctx, "slot"), i(ctx, "button"), str(ctx, "mode"))),
            spec("button", variants(v(
                            integer("id", 0, 3, CommandSupport.fixed("0", "1", "2", "3")))),
                    ctx -> button(name(ctx), i(ctx, "id"))),
            spec("trade", variants(v(
                            integer("index", 0, Integer.MAX_VALUE,
                                    CommandSupport.fixed("0", "1", "2", "3", "4", "5")))),
                    ctx -> trade(name(ctx), i(ctx, "index"))),
            spec("setSlot", variants(v(
                            integer("slot", 0, Integer.MAX_VALUE, containerSlots()))),
                    ctx -> setSlot(name(ctx), i(ctx, "slot"))),
            spec("chat", variants(v(greedy("message"))),
                    ctx -> chat(name(ctx), str(ctx, "message"))),
            spec("command", variants(v(greedy("command", nestedCommands()))),
                    ctx -> command(name(ctx), str(ctx, "command"))),
            spec("wakeUp", variants(v()),
                    ctx -> wakeUp(name(ctx))),
            spec("respawn", variants(v()),
                    ctx -> respawn(name(ctx))),
            spec("editBook", variants(
                            v(integer("slot", 1, 9,
                                            CommandSupport.fixed("1", "2", "3", "4", "5", "6", "7", "8", "9")),
                                    word("page")),
                            v(integer("slot", 1, 9,
                                            CommandSupport.fixed("1", "2", "3", "4", "5", "6", "7", "8", "9")),
                                    word("page"),
                                    greedy("title"))),
                    ctx -> editBook(name(ctx), i(ctx, "slot"), str(ctx, "page"), strOrNull(ctx, "title"))),
            spec("editSign", variants(v(
                            integer("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            integer("y", CommandSupport.coordY("commands.mockplayer.control.suggest.y")),
                            integer("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")),
                            word("side", CommandSupport.fixed("front", "back")),
                            word("line1"),
                            word("line2"),
                            word("line3"),
                            word("line4"))),
                    ctx -> editSign(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"),
                            "front".equals(str(ctx, "side")),
                            new String[]{str(ctx, "line1"), str(ctx, "line2"),
                                    str(ctx, "line3"), str(ctx, "line4")})),
            spec("setBeacon", variants(
                            v(word("primary", effectIds())),
                            v(word("primary", effectIds()), word("secondary", effectIds()))),
                    ctx -> setBeacon(name(ctx), str(ctx, "primary"), strOrNull(ctx, "secondary"))),
            spec("renameItem", variants(v(greedy("name"))),
                    ctx -> renameItem(name(ctx), str(ctx, "name"))),
            spec("pickItemFromBlock", variants(v(
                            integer("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            integer("y", CommandSupport.coordY("commands.mockplayer.control.suggest.y")),
                            integer("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")),
                            word("includeData", CommandSupport.fixed("true", "false")))),
                    ctx -> pickItemFromBlock(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"),
                            "true".equals(str(ctx, "includeData")))),
            spec("goto", variants(v(
                            integer("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            integer("y", CommandSupport.coordY("commands.mockplayer.control.suggest.y")),
                            integer("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")))),
                    ctx -> gotoPos(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"))),
            spec("goNear", variants(v(
                            integer("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            integer("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")),
                            integer("radius", 0, 64))),
                    ctx -> goNear(name(ctx), i(ctx, "x"), i(ctx, "z"), i(ctx, "radius"))),
            spec("pathstop", variants(v()),
                    ctx -> pathStop(name(ctx))),
            spec("mode", variants(v(word("mode", CommandSupport.fixed("walk", "elytra")))),
                    ctx -> modeCmd(name(ctx), str(ctx, "mode"))),
            spec("elytra", variants(v(
                            integer("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            integer("y", CommandSupport.coordY("commands.mockplayer.control.suggest.y")),
                            integer("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")))),
                    ctx -> elytraCmd(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"))),
            spec("mine", variants(v(
                            integer("x", CommandSupport.coordX("commands.mockplayer.control.suggest.x")),
                            integer("y", CommandSupport.coordY("commands.mockplayer.control.suggest.y")),
                            integer("z", CommandSupport.coordZ("commands.mockplayer.control.suggest.z")))),
                    ctx -> mineCmd(name(ctx), i(ctx, "x"), i(ctx, "y"), i(ctx, "z"))),
            spec("follow", variants(v(word("target", entityTypes()))),
                    ctx -> followCmd(name(ctx), str(ctx, "target"))),
            spec("config", variants(
                            v(word("mode", CommandSupport.fixed("list", "set", "reset"))),
                            v(word("mode", CommandSupport.fixed("set")),
                                    word("key", navigateKeys()),
                                    greedy("value")),
                            v(word("mode", CommandSupport.fixed("reset")),
                                    word("key", navigateKeys()))),
                    ctx -> configCmd(name(ctx), str(ctx, "mode"),
                            strOrNull(ctx, "key"), strOrNull(ctx, "value"))),
            spec("help", variants(v()),
                    ctx -> help(name(ctx))));

    /** 全部动作命令（与命令树同一来源；/control help 与测试共用，防漂移）。 */
    public static final List<String> ACTIONS = SPECS.stream().map(CommandSpec::name).toList();

    private ControlCommands() {
    }

    /** /control help：列出全部动作命令（i18n；ACTIONS 与树同步，测试防漂移）。 */
    public static Component help(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        MutableComponent out = info("commands.mockplayer.control.help.header", playerName(name));
        for (String action : ACTIONS) {
            out.append(Component.literal("\n")).append(info(
                    "commands.mockplayer.control.help.entry",
                    Component.translatable("commands.mockplayer.control.action." + action)));
        }
        return out;
    }

    // ===== 寻路动作（2026-08-16：BotNavigator 接线，命令层先查 navigate.enabled）=====

    /** /control goto：走到坐标（WALK 模式；模式切换走 config/API）。 */
    private static Component gotoPos(String name, int x, int y, int z) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Component disabled = navigateDisabled(bot);
        if (disabled != null) {
            return disabled;
        }
        bot.navigate().goTo(new BlockPos(x, y, z));
        return success("goto", name);
    }

    /** /control goNear：靠近坐标水平半径内（y 取假人当前高度）。 */
    private static Component goNear(String name, int x, int z, int radius) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Component disabled = navigateDisabled(bot);
        if (disabled != null) {
            return disabled;
        }
        int y = bot.getLocalPlayer().blockPosition().getY();
        bot.navigate().goNear(new BlockPos(x, y, z), radius);
        return success("gonear", name);
    }

    /** /control pathstop：取消寻路（路径 + 输入接管复位）。 */
    private static Component pathStop(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).navigate().stop();
        return success("pathstop", name);
    }

    /** /control mode：切换移动方式（WALK/ELYTRA；后续 goto 按模式分流）。 */
    private static Component modeCmd(String name, String mode) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Component disabled = navigateDisabled(bot);
        if (disabled != null) {
            return disabled;
        }
        NavigationMode m;
        if ("elytra".equalsIgnoreCase(mode)) {
            m = NavigationMode.ELYTRA;
        } else if ("walk".equalsIgnoreCase(mode)) {
            m = NavigationMode.WALK;
        } else {
            return fail("commands.mockplayer.control.mode.invalid", mode);
        }
        bot.navigate().mode(m);
        return success("mode", name);
    }

    /** /control elytra：鞘翅飞往坐标（需要假人装备鞘翅）。 */
    private static Component elytraCmd(String name, int x, int y, int z) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Component disabled = navigateDisabled(bot);
        if (disabled != null) {
            return disabled;
        }
        bot.navigate().elytra(new BlockPos(x, y, z));
        return success("elytra", name);
    }

    /** /control mine：挖指定方块（Baritone MineProcess：寻路+选工具+挖掘+拾取一条龙）。 */
    private static Component mineCmd(String name, int x, int y, int z) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Component disabled = navigateDisabled(bot);
        if (disabled != null) {
            return disabled;
        }
        bot.navigate().mine(new BlockPos(x, y, z));
        return success("mine", name);
    }

    /** /control follow：跟随附近指定类型实体（取最近的匹配实体）。 */
    private static Component followCmd(String name, String target) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Component disabled = navigateDisabled(bot);
        if (disabled != null) {
            return disabled;
        }
        Entity entity = bot.getEntitiesNear(16.0).stream()
                .filter(e -> entityTypeKey(e).equalsIgnoreCase(target))
                .findFirst().orElse(null);
        if (entity == null) {
            return fail("commands.mockplayer.control.follow.not_found", target, playerName(name));
        }
        bot.navigate().follow(entity);
        return success("follow", name);
    }

    /** /control config list|set|reset：per-bot 寻路行为配置（渲染三态全局，不走命令）。 */
    private static Component configCmd(String name, String mode, String key, String value) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail("commands.mockplayer.control.not_found", playerName(name));
        }
        if (bot.getLocalPlayer() == null) {
            return fail("commands.mockplayer.control.not_playing", playerName(name));
        }
        FakeSession session = bot instanceof BotImpl impl ? impl.session() : null;
        switch (mode == null ? "" : mode) {
            case "list" -> {
                return navigateConfigList(name);
            }
            case "set" -> {
                if (key == null || value == null) {
                    return fail("commands.mockplayer.control.config.usage", mode);
                }
                if (!com.mockplayer.session.NavigateSupport.CONFIG_KEYS.contains(key)) {
                    return fail("commands.mockplayer.control.config.unknown_key", key,
                            String.join(", ", com.mockplayer.session.NavigateSupport.CONFIG_KEYS));
                }
                Object parsed = com.mockplayer.session.NavigateSupport.parseValue(key, value);
                if (parsed == null) {
                    return fail("commands.mockplayer.control.config.invalid_value", key, value);
                }
                if (session != null) {
                    session.setNavigateOverride(key, parsed);
                    com.mockplayer.session.NavigateSupport.applyToSession(session);
                }
                return info("commands.mockplayer.control.config.set", playerName(name), key, parsed);
            }
            case "reset" -> {
                if (key == null) {
                    return fail("commands.mockplayer.control.config.usage", mode);
                }
                if (session != null) {
                    session.setNavigateOverride(key, null);
                    com.mockplayer.session.NavigateSupport.applyToSession(session);
                }
                return info("commands.mockplayer.control.config.reset", playerName(name), key);
            }
            default -> {
                return fail("commands.mockplayer.control.config.usage", mode);
            }
        }
    }

    /** /control config list 与 /query config 共用：列出该假人生效寻路配置（* = per-bot 覆盖）。 */
    public static Component navigateConfigList(String name) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail("commands.mockplayer.control.not_found", playerName(name));
        }
        FakeSession session = bot instanceof BotImpl impl ? impl.session() : null;
        MutableComponent out = info("commands.mockplayer.control.config.header", playerName(name));
        for (String key : com.mockplayer.session.NavigateSupport.CONFIG_KEYS) {
            Object value = com.mockplayer.session.NavigateSupport.effectiveValue(session, key);
            boolean overridden = session != null && session.getNavigateOverride(key) != null;
            out.append(Component.literal("\n")).append(info(
                    "commands.mockplayer.control.config.entry", key, value,
                    overridden ? Component.literal(" *") : Component.literal("")));
        }
        return out;
    }

    /** 假人寻路被禁用时的反馈（navigate.enabled=false，per-bot 或全局）。 */
    private static Component navigateDisabled(Bot bot) {
        if (bot instanceof BotImpl impl) {
            if (!Boolean.TRUE.equals(com.mockplayer.session.NavigateSupport
                    .effectiveValue(impl.session(), "enabled"))) {
                return fail("commands.mockplayer.control.navigate.disabled", playerName(bot.getName()));
            }
        }
        return null;
    }

    /** config set/reset 的 key Tab 补全（白名单）。 */
    private static SuggestionProvider<?> navigateKeys() {
        return CommandSupport.fixed(
                com.mockplayer.session.NavigateSupport.CONFIG_KEYS.toArray(new String[0]));
    }

    // ===== 通用辅助 =====

    private static MutableComponent playerName(String name) {
        return CommandSupport.playerName(name);
    }

    private static Component fail(String key, Object... args) {
        return CommandSupport.fail(key, args);
    }

    private static MutableComponent info(String key, Object... args) {
        return CommandSupport.info(key, args);
    }

    /** 动作成功反馈：动作名翻译 key（commands.mockplayer.control.action.<x>）+ 假人名字。 */
    private static Component success(String actionKey, String name, Object... extra) {
        Object[] args = new Object[2 + extra.length];
        args[0] = playerName(name);
        args[1] = Component.translatable("commands.mockplayer.control.action." + actionKey);
        System.arraycopy(extra, 0, args, 2, extra.length);
        return Component.translatable("commands.mockplayer.control.success", args)
                .withStyle(CommandSupport.SUCCESS_COLOR);
    }

    /** 带内容的成功反馈（chat/command/renameItem）：模板含 %3$s 显示用户输入原文。 */
    private static Component contentSuccess(String actionKey, String name, Object content) {
        return Component.translatable("commands.mockplayer.control.success.content",
                playerName(name),
                Component.translatable("commands.mockplayer.control.action." + actionKey),
                content).withStyle(CommandSupport.SUCCESS_COLOR);
    }

    /**
     * 通用动作样板（requirePlaying → 执行 → success）：与原逐方法样板逐字节等价。
     * 只用于「先查 PLAYING 再执行」的动作。
     */
    private static Component run(String actionKey, String name,
                                 java.util.function.Consumer<Bot> action, Object... extra) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        action.accept(findBot(name));
        return success(actionKey, name, extra);
    }

    /**
     * 通用动作样板（只查 not_found，不查 not_playing）：setSneak/setSprint 原语义。
     */
    private static Component runAny(String actionKey, String name,
                                    java.util.function.Consumer<Bot> action) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail("commands.mockplayer.control.not_found", playerName(name));
        }
        action.accept(bot);
        return success(actionKey, name);
    }

    /** 带用户输入原文的通用动作样板（chat/command/renameItem 原语义）。 */
    private static Component runContent(String actionKey, String name,
                                        java.util.function.Consumer<Bot> action, String content) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        action.accept(findBot(name));
        return contentSuccess(actionKey, name, content);
    }

    /** 实体目标动作样板（requirePlaying → 解析实体 → 执行 → success，原语义）。 */
    private static Component runEntity(String actionKey, String name, String target,
                                       java.util.function.BiConsumer<Bot, Entity> action) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found",
                    playerName(name), target == null ? "?" : target);
        }
        action.accept(bot, entity);
        return success(actionKey, name, entity.getName());
    }

    /** 容器动作样板（requirePlaying → 取容器 → 执行，原语义；无容器返回提示）。 */
    private static Component withContainer(String name,
                                           java.util.function.Function<BotContainer, Component> fn) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        return fn.apply(c.get());
    }

    private static Bot findBot(String name) {
        return CommandSupport.findBot(name);
    }

    /** 取 bot + PLAYING 校验；失败返回反馈组件，null 表示成功拿到 bot。 */
    private static Component requirePlaying(String name) {
        return CommandSupport.requirePlaying(name,
                "commands.mockplayer.control.not_found",
                "commands.mockplayer.control.not_playing");
    }

    /** 实体名解析：null = 最近的非玩家实体；否则按名称匹配（精确，含 display name）。 */
    private static Entity resolveEntity(Bot bot, String target) {
        List<Entity> near = bot.getEntitiesNear(16.0);
        if (target == null || target.isBlank()) {
            return near.stream()
                    .filter(e -> !(e instanceof Player))
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.getLocalPlayer())))
                    .orElse(null);
        }
        return near.stream()
                .filter(e -> target.equals(e.getName().getString())
                        || target.equals(e.getDisplayName().getString())
                        // 类型路径（如 "husk"）：语言无关，测试/脚本输入稳定
                        || target.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).getPath()))
                .findFirst()
                .orElse(null);
    }

    private static Direction resolveSide(String side) {
        return side == null ? null : Direction.byName(side.toLowerCase(java.util.Locale.ROOT));
    }

    private static InteractionHand resolveHand(String hand) {
        return hand == null ? InteractionHand.MAIN_HAND
                : switch (hand.toLowerCase(java.util.Locale.ROOT)) {
                    case "mainhand" -> InteractionHand.MAIN_HAND;
                    case "offhand" -> InteractionHand.OFF_HAND;
                    default -> null;
                };
    }

    private static Optional<Holder<MobEffect>> resolveEffect(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id))
                    .map(r -> (Holder<MobEffect>) r);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ===== 动作命令 =====

    public static Component move(String name, String dir) {
        if (!doMove(name, dir)) {
            Component blocked = requirePlaying(name);
            if (blocked != null) {
                return blocked;
            }
            return fail("commands.mockplayer.control.invalid_dir", dir == null ? "" : dir);
        }
        return switch (dir == null ? "" : dir.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.forward"));
            case "backward" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.backward"));
            case "left" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.left"));
            case "right" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.right"));
            default -> fail("commands.mockplayer.control.invalid_dir", dir == null ? "" : dir);
        };
    }

    /** move 实际执行（返回是否成功，供命令树与测试共用）。 */
    static boolean doMove(String name, String dir) {
        Bot bot = findBot(name);
        if (bot == null || bot.getLifecycle() != BotLifecycle.PLAYING) {
            return false;
        }
        return switch (dir == null ? "" : dir.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> {
                bot.actions().setForward(1.0F);
                yield true;
            }
            case "backward" -> {
                bot.actions().setForward(-1.0F);
                yield true;
            }
            case "left" -> {
                bot.actions().setStrafe(1.0F);
                yield true;
            }
            case "right" -> {
                bot.actions().setStrafe(-1.0F);
                yield true;
            }
            default -> false;
        };
    }

    public static Component stop(String name) {
        return run("stop", name, b -> b.actions().stop());
    }

    public static Component setSneak(String name, boolean on) {
        return runAny(on ? "sneak" : "unsneak", name, b -> b.actions().setSneak(on));
    }

    public static Component setSprint(String name, boolean on) {
        return runAny(on ? "sprint" : "unsprint", name, b -> b.actions().setSprint(on));
    }

    public static Component jump(String name) {
        return run("jump", name, b -> b.actions().jump());
    }

    public static Component look(String name, float yaw, float pitch) {
        return run("look", name, b -> b.actions().look(yaw, pitch), yaw, pitch);
    }

    public static Component lookAt(String name, double x, double y, double z) {
        return run("lookAt", name, b -> b.actions().lookAt(new Vec3(x, y, z)),
                String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", x, y, z));
    }

    public static Component turn(String name, float yaw, float pitch) {
        return run("turn", name, b -> b.actions().turn(yaw, pitch), yaw, pitch);
    }

    public static Component attack(String name, String target) {
        return runEntity("attack", name, target,
                (b, e) -> {
                    b.actions().lookAt(e);
                    b.actions().attack(e);
                });
    }

    public static Component stab(String name) {
        return run("stab", name, b -> b.actions().stab());
    }

    public static Component sustainedAttack(String name, String target) {
        return runEntity("sustainedAttack", name, target,
                (b, e) -> {
                    b.actions().lookAt(e);
                    b.actions().holdAttack(e);
                });
    }

    public static Component sustainedUse(String name, String target) {
        return runEntity("sustainedUse", name, target,
                (b, e) -> {
                    b.actions().lookAt(e);
                    b.actions().holdUse(e);
                });
    }

    public static Component attackLook(String name) {
        return run("attackLook", name, b -> b.actions().attack());
    }

    public static Component useLook(String name) {
        return run("useLook", name, b -> b.actions().use());
    }

    public static Component sustainedAttackLook(String name) {
        return run("sustainedAttackLook", name, b -> b.actions().holdAttack());
    }

    public static Component sustainedUseLook(String name) {
        return run("sustainedUseLook", name, b -> b.actions().holdUse());
    }

    public static Component stopSustained(String name) {
        return run("stopSustained", name, b -> b.actions().stopSustained());
    }

    public static Component interact(String name, String target) {
        return runEntity("interact", name, target,
                (b, e) -> {
                    b.actions().lookAt(e);
                    b.actions().use(e);
                });
    }

    public static Component useItem(String name, String hand) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        InteractionHand h = resolveHand(hand);
        if (h == null) {
            return fail("commands.mockplayer.control.invalid_hand", hand == null ? "" : hand);
        }
        return run("useItem", name, b -> b.actions().use(h),
                h == InteractionHand.MAIN_HAND
                        ? Component.translatable("commands.mockplayer.control.hand.main")
                        : Component.translatable("commands.mockplayer.control.hand.off"));
    }

    public static Component releaseUsingItem(String name) {
        return run("releaseUsingItem", name, b -> b.actions().releaseUse());
    }

    public static Component useItemOn(String name, int x, int y, int z, String side) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Direction dir = resolveSide(side);
        if (dir == null) {
            return fail("commands.mockplayer.control.invalid_side", side == null ? "" : side);
        }
        return run("useItemOn", name,
                b -> {
                    b.actions().lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                    b.actions().use(new BlockPos(x, y, z), dir);
                }, x + " " + y + " " + z + " " + dir.getName());
    }

    public static Component placeBlock(String name, int x, int y, int z, String side) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Direction dir = resolveSide(side);
        if (dir == null) {
            return fail("commands.mockplayer.control.invalid_side", side == null ? "" : side);
        }
        return run("placeBlock", name,
                b -> {
                    b.actions().lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                    b.actions().place(new BlockPos(x, y, z), dir);
                }, x + " " + y + " " + z + " " + dir.getName());
    }

    public static Component mineBlock(String name, int x, int y, int z) {
        return run("mineBlock", name,
                b -> {
                    b.actions().lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                    b.actions().mine(new BlockPos(x, y, z));
                }, x + " " + y + " " + z);
    }

    public static Component attackBlock(String name, int x, int y, int z) {
        return run("attackBlock", name,
                b -> {
                    b.actions().lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                    b.actions().attack(new BlockPos(x, y, z));
                }, x + " " + y + " " + z);
    }

    public static Component hotbar(String name, int slot) {
        int index = Math.max(0, Math.min(8, slot - 1));
        return run("hotbar", name, b -> b.actions().setSelectedSlot(index), index + 1);
    }

    public static Component chunkRadius(String name, int radius) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (radius < com.mockplayer.config.ModConfig.MIN_FAKE_PLAYER_CHUNK_RADIUS
                || radius > com.mockplayer.config.ModConfig.MAX_FAKE_PLAYER_CHUNK_RADIUS) {
            return fail("commands.mockplayer.control.invalid_chunk_radius", radius);
        }
        return run("chunkRadius", name, b -> b.setChunkRadius(radius), radius);
    }

    public static Component drop(String name, Integer slot, Boolean all) {
        return run("drop", name, bot -> {
            if (slot == null || slot < 0) {
                bot.actions().drop();
            } else {
                bot.actions().drop(Math.min(8, slot), all != null && all);
            }
        }, slot == null ? "-" : slot);
    }

    public static Component swapHands(String name) {
        return run("swapHands", name, b -> b.actions().swapHands());
    }

    public static Component mount(String name, String target, Boolean onlyRideables) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        if (target == null || target.isBlank()) {
            // 自动模式：骑最近的可骑乘实体（或任意实体，由 onlyRideables 决定）
            bot.actions().mount(onlyRideables == null || onlyRideables);
            return success("mount", name);
        }
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found", playerName(name), target);
        }
        bot.actions().lookAt(entity);
        bot.actions().mount(entity);
        return success("mount", name, entity.getName());
    }

    public static Component dismount(String name) {
        return run("dismount", name, b -> b.actions().dismount());
    }

    /** 关闭假人当前打开的容器 GUI（原版 LocalPlayer.closeContainer 等价，发关闭包）。 */
    public static Component close(String name) {
        return withContainer(name, c -> {
            c.close();
            return success("close", name);
        });
    }

    /** 点击容器槽位（拿取/放置/换位/拖拽，ContainerInput 枚举小写名）。 */
    public static Component click(String name, int slot, int button, String mode) {
        net.minecraft.world.inventory.ContainerInput input = resolveClickMode(mode);
        return withContainer(name, container -> {
            if (input == null) {
                return fail("commands.mockplayer.control.invalid_click_mode", mode == null ? "" : mode);
            }
            if (slot < -1 || slot >= container.getSize()) {
                // 越界点击会让原版 clicked 抛 IndexOutOfBounds（客户端崩溃），命令层先拦下
                return fail("commands.mockplayer.control.invalid_slot", slot);
            }
            container.click(slot, button, input);
            return success("click", name, slot, button, mode);
        });
    }

    private static net.minecraft.world.inventory.ContainerInput resolveClickMode(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "pickup" -> net.minecraft.world.inventory.ContainerInput.PICKUP;
            case "quickmove", "quick_move" -> net.minecraft.world.inventory.ContainerInput.QUICK_MOVE;
            case "swap" -> net.minecraft.world.inventory.ContainerInput.SWAP;
            case "clone" -> net.minecraft.world.inventory.ContainerInput.CLONE;
            case "throw" -> net.minecraft.world.inventory.ContainerInput.THROW;
            case "quickcraft", "quick_craft" -> net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT;
            case "pickupall", "pickup_all" -> net.minecraft.world.inventory.ContainerInput.PICKUP_ALL;
            default -> null;
        };
    }

    /** 点击菜单按钮（附魔台选附魔等）。 */
    public static Component button(String name, int buttonId) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        c.get().clickButton(buttonId);
        return success("button", name, buttonId);
    }

    /** 选择交易菜单中的一笔报价。 */
    public static Component trade(String name, int index) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        c.get().selectTrade(index);
        return success("trade", name, index);
    }

    /** 把假人主手物品乐观写入容器槽位（服务端回包为准）。 */
    public static Component setSlot(String name, int slot) {
        return withContainer(name, container -> {
            Bot bot = findBot(name);
            if (bot.getLocalPlayer() == null) {
                return fail("commands.mockplayer.control.not_playing", playerName(name));
            }
            if (slot < 0 || slot >= container.getSize()) {
                // 越界写入会让原版 setItem 抛 IndexOutOfBounds（客户端崩溃），命令层先拦下
                return fail("commands.mockplayer.control.invalid_slot", slot);
            }
            container.setSlot(slot, bot.getLocalPlayer().getMainHandItem());
            return success("setSlot", name, slot);
        });
    }

    public static Component chat(String name, String message) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (message == null || message.isBlank()) {
            return fail("commands.mockplayer.control.blank_message");
        }
        return runContent("chat", name, b -> b.actions().chat(message), message);
    }

    public static Component command(String name, String commandLine) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (commandLine == null || commandLine.isBlank()) {
            return fail("commands.mockplayer.control.blank_message");
        }
        return runContent("command", name, b -> b.actions().sendCommand(commandLine), commandLine);
    }

    public static Component wakeUp(String name) {
        return run("wakeUp", name, b -> b.actions().wakeUp());
    }

    public static Component respawn(String name) {
        return run("respawn", name, b -> b.actions().respawn());
    }

    public static Component editBook(String name, int slot, String page, String title) {
        int index = Math.max(0, Math.min(8, slot - 1));
        return run("editBook", name,
                b -> b.actions().editBook(index,
                        List.of(page == null ? "" : page),
                        title == null || title.isBlank() ? Optional.empty() : Optional.of(title)),
                index + 1);
    }

    public static Component editSign(String name, int x, int y, int z, boolean front, String[] lines) {
        String[] four = new String[4];
        for (int i = 0; i < 4; i++) {
            four[i] = lines != null && i < lines.length ? lines[i] : "";
        }
        return run("editSign", name,
                b -> b.actions().editSign(new BlockPos(x, y, z), front, four),
                x + " " + y + " " + z);
    }

    public static Component setBeacon(String name, String primary, String secondary) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Optional<Holder<MobEffect>> p = resolveEffect(primary);
        Optional<Holder<MobEffect>> s = resolveEffect(secondary);
        if (primary != null && !primary.isBlank() && p.isEmpty()) {
            return fail("commands.mockplayer.control.invalid_effect", primary);
        }
        if (secondary != null && !secondary.isBlank() && s.isEmpty()) {
            return fail("commands.mockplayer.control.invalid_effect", secondary);
        }
        findBot(name).actions().setBeacon(p, s);
        return success("setBeacon", name, primary == null ? "-" : primary);
    }

    public static Component renameItem(String name, String newName) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (newName == null || newName.isBlank()) {
            return fail("commands.mockplayer.control.blank_message");
        }
        return runContent("renameItem", name, b -> b.actions().renameItem(newName), newName);
    }

    public static Component pickItemFromBlock(String name, int x, int y, int z, boolean includeData) {
        return run("pickItemFromBlock", name,
                b -> b.actions().pickItemFromBlock(new BlockPos(x, y, z), includeData),
                x + " " + y + " " + z);
    }

    // ===== Tab 补全 =====

    private static Bot botFromContext(CommandContext<?> ctx) {
        try {
            String name = StringArgumentType.getString(ctx, "player");
            return findBot(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 实体目标补全：只补全附近实体的类型路径（villager / minecart 等，语言无关）。
     * 不补全本地化显示名，避免中文环境出现「村民」「矿车」这类随语言变化的字符串。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> entityTypes() {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            if (bot == null) {
                return builder.buildFuture();
            }
            List<String> typeIds = bot.getEntitiesNear(16.0).stream()
                    .map(ControlCommands::entityTypeKey)
                    .distinct()
                    .toList();
            return SharedSuggestionProvider.suggest(typeIds, builder);
        };
    }

    /** 实体类型路径 id（villager / minecart 等，语言无关；与补全共用防漂移）。 */
    public static String entityTypeKey(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** 偏航角补全（只建议当前 yaw，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> yawNow() {
        return CommandSupport.playerNumber("commands.mockplayer.control.suggest.yaw", p -> p.getYRot(), "%.1f");
    }

    /** 俯仰角补全（只建议当前 pitch，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> pitchNow() {
        return CommandSupport.playerNumber("commands.mockplayer.control.suggest.pitch", p -> p.getXRot(), "%.1f");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> directions() {
        return CommandSupport.fixed("forward", "backward", "left", "right");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> sides() {
        return CommandSupport.fixed("north", "south", "east", "west", "up", "down");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> hands() {
        return CommandSupport.fixed("mainhand", "offhand");
    }

    /** mount 目标补全：附近实体类型 id + 自动模式关键字（真实候选）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> mountTargets() {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (bot != null) {
                bot.getEntitiesNear(16.0).stream()
                        .map(e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .getKey(e.getType()).getPath())
                        .distinct()
                        .forEach(all::add);
            }
            all.add("rideables");
            all.add("anything");
            return SharedSuggestionProvider.suggest(new java.util.ArrayList<>(all), builder);
        };
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> oneAll() {
        return CommandSupport.fixed("one", "all");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> effectIds() {
        return (ctx, builder) -> {
            List<String> ids = BuiltInRegistries.MOB_EFFECT.keySet().stream()
                    .map(Identifier::toString)
                    .toList();
            return SharedSuggestionProvider.suggest(ids, builder);
        };
    }

    /** 容器槽位补全：当前打开容器 0..size-1（真实候选，不是示例）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> containerSlots() {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            if (bot == null || bot.getContainer().isEmpty()) {
                return builder.buildFuture();
            }
            List<String> slots = new java.util.ArrayList<>();
            for (int i = 0; i < bot.getContainer().get().getSize(); i++) {
                slots.add(String.valueOf(i));
            }
            return SharedSuggestionProvider.suggest(slots, builder);
        };
    }

    /** 点击模式补全：ContainerInput 枚举小写名。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> clickModes() {
        return CommandSupport.fixed("pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all");
    }

    /**
     * 嵌套命令补全：/control &lt;player&gt; command &lt;cmd&gt; 复用客户端命令树，
     * 与原版 /execute ... run 同款机制（CommandSuggestions 用本连接 dispatcher 解析补全）。
     * 让假人执行命令时也能逐级 Tab 补全（如 time set 1000 / gamemode creative）。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> nestedCommands() {
        return (ctx, builder) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.connection == null) {
                return builder.buildFuture();
            }
            var dispatcher = mc.player.connection.getCommands();
            // 只解析 command 参数剩余部分（如 "time "），避免依赖完整输入里的参数起点；
            // 子命令建议的 range 基于剩余串，最后平移到 builder 的绝对位置。
            String remaining = builder.getRemaining();
            StringReader reader = new StringReader(remaining);
            var parse = dispatcher.parse(reader, mc.player.connection.getSuggestionsProvider());
            // 原版 CommandSuggestions 传的是输入末尾位置（含尾随空格），不是 parse 后的 reader cursor；
            // 否则 findSuggestionContext 找不到已匹配节点，建议会回退成顶层命令列表。
            int cursor = remaining.length();
            int base = builder.getStart();
            return dispatcher.getCompletionSuggestions(parse, cursor).thenApply(inner -> {
                java.util.List<com.mojang.brigadier.suggestion.Suggestion> shifted = inner.getList().stream()
                        .map(s -> new com.mojang.brigadier.suggestion.Suggestion(
                                new com.mojang.brigadier.context.StringRange(
                                        base + s.getRange().getStart(), base + s.getRange().getEnd()),
                                s.getText(), s.getTooltip()))
                        .toList();
                String full = builder.getInput();
                return new com.mojang.brigadier.suggestion.Suggestions(
                        new com.mojang.brigadier.context.StringRange(0, full.length()), shifted);
            });
        };
    }

    // ===== 命令树构建（双端共用，平台只提供 literal/argument/反馈函数） =====

    public static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> buildControlTree(
            CommandSupport.CommandFactory<S> f, String rootName) {
        LiteralArgumentBuilder<S> root = f.literal(rootName);

        RequiredArgumentBuilder<S, ?> player = f.argument("player", FakePlayerNameArgument.fakePlayerName())
                .suggests(CommandSupport.botNames());

        for (CommandSpec spec : SPECS) {
            registerSpec(player, f, spec);
        }

        root.then(player);
        return root;
    }

    /** 按 CommandSpec 注册一个动作命令（无参 executes + 每个参数链变体一棵子树）。 */
    @SuppressWarnings("unchecked")
    private static <S extends SharedSuggestionProvider> void registerSpec(
            RequiredArgumentBuilder<S, ?> player, CommandSupport.CommandFactory<S> f, CommandSpec spec) {
        LiteralArgumentBuilder<S> node = f.literal(spec.name());
        for (List<ArgSpec> variant : spec.variants()) {
            if (variant.isEmpty()) {
                node = node.executes(executor(spec, f));
            } else {
                // 注意：Brigadier 的 then() 会立即 build 子节点快照（arguments 存 CommandNode），
                // 自上而下折叠会丢失深层子节点（z 被 y 引用时还没挂上 side）。
                // 必须自底向上构造：叶子先挂 executes/suggests，再逐层把已完成子树挂到新父节点。
                ArgumentBuilder<S, ?> chain = null;
                for (int i = variant.size() - 1; i >= 0; i--) {
                    ArgSpec arg = variant.get(i);
                    RequiredArgumentBuilder<S, ?> ra = f.argument(arg.name(), arg.type());
                    if (arg.suggests() != null) {
                        ra = ra.suggests((SuggestionProvider<S>) arg.suggests());
                    }
                    if (chain == null) {
                        // 参数链叶子：与原版一致，executes 挂在这里
                        ra = ra.executes(executor(spec, f));
                    } else {
                        ra.then(chain);
                    }
                    chain = ra;
                }
                node = node.then(chain);
            }
        }
        player.then(node);
    }

    /** 统一 executes：执行器返回反馈组件，由平台工厂发送。 */
    private static <S extends SharedSuggestionProvider> com.mojang.brigadier.Command<S> executor(
            CommandSpec spec, CommandSupport.CommandFactory<S> f) {
        return ctx -> {
            f.sendFeedback(ctx.getSource(), spec.executor().apply(ctx));
            return 1;
        };
    }
}
