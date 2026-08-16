package com.mockplayer.config;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.utils.OptionUtils;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * YACL 配置界面（common 一次编写，Fabric ModMenu / NeoForge IConfigScreenFactory 共用）。
 *
 * 输入：MockplayerConfig.get() 当前配置实例
 * 输出：界面选项绑定同一实例，保存按钮把实例写入 config/mockplayer.json
 *
 * 仅当 YACL 在运行时存在时被构造（平台入口已做条件加载），缺失时用 MissingYaclScreen 兜底。
 */
public final class ModConfigScreen extends YACLScreen {

    /** 当前绑定的配置实例（保存时写入同一个实例，测试可直接断言）。 */
    private final ModConfig boundConfig;

    public ModConfigScreen(Screen parent) {
        super(buildYacl(MockplayerConfig.get()), parent);
        this.boundConfig = MockplayerConfig.get();
    }

    /** 测试/查询用：当前界面绑定的配置实例。 */
    public ModConfig config() {
        return this.boundConfig;
    }

    /** 构建 YACL 界面树（title + 通用分类 + 命令分类 + 保存函数）。 */
    private static YetAnotherConfigLib buildYacl(ModConfig cfg) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("config.mockplayer.title"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("config.mockplayer.category.general"))
                        .tooltip(Component.translatable("config.mockplayer.category.general.tooltip"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.logs"))
                                .option(intOption("chatHistoryLimit",
                                        ModConfig.DEFAULT_CHAT_HISTORY_LIMIT,
                                        ModConfig.MIN_CHAT_HISTORY_LIMIT, ModConfig.MAX_CHAT_HISTORY_LIMIT,
                                        cfg::getChatHistoryLimit, cfg::setChatHistoryLimit))
                                .option(intOption("soundLogLimit",
                                        ModConfig.DEFAULT_SOUND_LOG_LIMIT,
                                        ModConfig.MIN_SOUND_LOG_LIMIT, ModConfig.MAX_SOUND_LOG_LIMIT,
                                        cfg::getSoundLogLimit, cfg::setSoundLogLimit))
                                .option(intOption("particleLogLimit",
                                        ModConfig.DEFAULT_PARTICLE_LOG_LIMIT,
                                        ModConfig.MIN_PARTICLE_LOG_LIMIT, ModConfig.MAX_PARTICLE_LOG_LIMIT,
                                        cfg::getParticleLogLimit, cfg::setParticleLogLimit))
                                .option(intOption("eventCacheSize",
                                        ModConfig.DEFAULT_EVENT_CACHE_SIZE,
                                        ModConfig.MIN_EVENT_CACHE_SIZE, ModConfig.MAX_EVENT_CACHE_SIZE,
                                        cfg::getEventCacheSize, cfg::setEventCacheSize))
                                .option(intOption("eventSummaryMaxLength",
                                        ModConfig.DEFAULT_EVENT_SUMMARY_MAX_LENGTH,
                                        ModConfig.MIN_EVENT_SUMMARY_MAX_LENGTH, ModConfig.MAX_EVENT_SUMMARY_MAX_LENGTH,
                                        cfg::getEventSummaryMaxLength, cfg::setEventSummaryMaxLength))
                                .option(intOption("eventTickSampleInterval",
                                        ModConfig.DEFAULT_EVENT_TICK_SAMPLE_INTERVAL,
                                        ModConfig.MIN_EVENT_TICK_SAMPLE_INTERVAL, ModConfig.MAX_EVENT_TICK_SAMPLE_INTERVAL,
                                        cfg::getEventTickSampleInterval, cfg::setEventTickSampleInterval))
                                .option(doubleOption("eventMoveSampleDistance",
                                        ModConfig.DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE,
                                        ModConfig.MIN_EVENT_MOVE_SAMPLE_DISTANCE, ModConfig.MAX_EVENT_MOVE_SAMPLE_DISTANCE,
                                        cfg::getEventMoveSampleDistance, cfg::setEventMoveSampleDistance))
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.debug"))
                                .option(enumOption("debugOverlayMode", RenderMode.F3_ONLY,
                                        cfg::getDebugOverlayMode, cfg::setDebugOverlayMode))
                                .option(booleanOption("navigateEnabled", true,
                                        cfg::isNavigateEnabled, cfg::setNavigateEnabled))
                                .option(enumOption("navigateRenderMode", RenderMode.F3_ONLY,
                                        cfg::getNavigateRenderMode, cfg::setNavigateRenderMode))
                                .option(intOption("fakePlayerChunkRadius",
                                        ModConfig.DEFAULT_FAKE_PLAYER_CHUNK_RADIUS,
                                        ModConfig.MIN_FAKE_PLAYER_CHUNK_RADIUS, ModConfig.MAX_FAKE_PLAYER_CHUNK_RADIUS,
                                        cfg::getFakePlayerChunkRadius, cfg::setFakePlayerChunkRadius))
                                .option(intOption("batchMaxCount",
                                        ModConfig.DEFAULT_BATCH_MAX_COUNT,
                                        ModConfig.MIN_BATCH_MAX_COUNT, ModConfig.MAX_BATCH_MAX_COUNT,
                                        cfg::getBatchMaxCount, cfg::setBatchMaxCount))
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.gui"))
                                .option(booleanOption("guiEnabled", true,
                                        cfg::isGuiEnabled, cfg::setGuiEnabled))
                                .option(openControlsButton())
                                .option(opacityOption(
                                        () -> (double) cfg.getGuiOpacity(),
                                        value -> cfg.setGuiOpacity(value.floatValue())))
                                .option(intOption("guiBlur",
                                        ModConfig.DEFAULT_GUI_BLUR,
                                        ModConfig.MIN_GUI_BLUR, ModConfig.MAX_GUI_BLUR,
                                        cfg::getGuiBlur, cfg::setGuiBlur))
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("config.mockplayer.category.network"))
                        .tooltip(Component.translatable("config.mockplayer.category.network.tooltip"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.payload"))
                                .option(booleanOption("payloadInterceptEnabled",
                                        ModConfig.DEFAULT_PAYLOAD_INTERCEPT_ENABLED,
                                        cfg::isPayloadInterceptEnabled, cfg::setPayloadInterceptEnabled))
                                .option(intOption("payloadLogLimit",
                                        ModConfig.DEFAULT_PAYLOAD_LOG_LIMIT,
                                        ModConfig.MIN_PAYLOAD_LOG_LIMIT, ModConfig.MAX_PAYLOAD_LOG_LIMIT,
                                        cfg::getPayloadLogLimit, cfg::setPayloadLogLimit))
                                .option(booleanOption("payloadSendLogEnabled",
                                        ModConfig.DEFAULT_PAYLOAD_SEND_LOG_ENABLED,
                                        cfg::isPayloadSendLogEnabled, cfg::setPayloadSendLogEnabled))
                                .option(intOption("payloadSendLogLimit",
                                        ModConfig.DEFAULT_PAYLOAD_SEND_LOG_LIMIT,
                                        ModConfig.MIN_PAYLOAD_SEND_LOG_LIMIT, ModConfig.MAX_PAYLOAD_SEND_LOG_LIMIT,
                                        cfg::getPayloadSendLogLimit, cfg::setPayloadSendLogLimit))
                                .option(stringOption("payloadPassthroughNamespaces", "option",
                                        () -> String.join(", ", cfg.getPayloadPassthroughNamespaces()),
                                        value -> {
                                            List<String> list = new ArrayList<>();
                                            for (String ns : value.split(",")) {
                                                String trimmed = ns.trim();
                                                if (!trimmed.isEmpty()) {
                                                    list.add(trimmed);
                                                }
                                            }
                                            cfg.setPayloadPassthroughNamespaces(list);
                                        }))
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("config.mockplayer.category.commands"))
                        .tooltip(Component.translatable("config.mockplayer.category.commands.tooltip"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.commands"))
                                .option(stringOption(ModCommands.CONTROL,
                                        () -> cfg.getCommandName(ModCommands.CONTROL),
                                        value -> cfg.setCommandName(ModCommands.CONTROL, value)))
                                .option(stringOption(ModCommands.QUERY,
                                        () -> cfg.getCommandName(ModCommands.QUERY),
                                        value -> cfg.setCommandName(ModCommands.QUERY, value)))
                                .option(stringOption(ModCommands.NEWPLAYER,
                                        () -> cfg.getCommandName(ModCommands.NEWPLAYER),
                                        value -> cfg.setCommandName(ModCommands.NEWPLAYER, value)))
                                .option(stringOption(ModCommands.DELPLAYER,
                                        () -> cfg.getCommandName(ModCommands.DELPLAYER),
                                        value -> cfg.setCommandName(ModCommands.DELPLAYER, value)))
                                .option(stringOption(ModCommands.CONNECT,
                                        () -> cfg.getCommandName(ModCommands.CONNECT),
                                        value -> cfg.setCommandName(ModCommands.CONNECT, value)))
                                .option(resetButton())
                                .build())
                        .build())
                .save(() -> MockplayerConfig.save(cfg))
                .build();
    }

    /** 一键恢复默认按钮：全部选项恢复默认后保存（写文件 + 热重载）。 */
    private static ButtonOption resetButton() {
        return ButtonOption.createBuilder()
                .name(Component.translatable("config.mockplayer.reset_all"))
                .text(Component.translatable("config.mockplayer.reset_all"))
                .description(OptionDescription.of(Component.translatable("config.mockplayer.reset_all.tooltip")))
                .action(ModConfigScreen::resetAllAndSave)
                .build();
    }

    /** 恢复全部配置为默认并保存（按钮与测试共用同一路径）。 */
    public static void resetAllAndSave(YACLScreen screen) {
        OptionUtils.forEachOptions(screen.config, option -> {
            if (!(option instanceof ButtonOption)) {
                option.requestSetDefault();
            }
        });
        screen.finishOrSave();
    }

    /** int 字段选项：名称/描述/绑定/数字输入控件（带范围）。 */
    private static Option<Integer> intOption(String key, int fallback, int min, int max,
                                             Supplier<Integer> getter, Consumer<Integer> setter) {
        return Option.<Integer>createBuilder()
                .name(Component.translatable("config.mockplayer.option." + key))
                .description(OptionDescription.of(Component.translatable("config.mockplayer.option." + key + ".description")))
                .binding(fallback, getter, setter)
                .controller(option -> IntegerFieldControllerBuilder.create(option).range(min, max))
                .build();
    }

    /** double 字段选项：名称/描述/绑定/数字输入控件（带范围）。 */
    private static Option<Double> doubleOption(String key, double fallback, double min, double max,
                                               Supplier<Double> getter, Consumer<Double> setter) {
        return Option.<Double>createBuilder()
                .name(Component.translatable("config.mockplayer.option." + key))
                .description(OptionDescription.of(Component.translatable("config.mockplayer.option." + key + ".description")))
                .binding(fallback, getter, setter)
                .controller(option -> DoubleFieldControllerBuilder.create(option).range(min, max))
                .build();
    }

    /** 命令名字符串选项（i18n 前缀 = command）：名称/描述/绑定/文本输入（空串 = 禁用）。 */
    private static Option<String> stringOption(String key,
                                               Supplier<String> getter, Consumer<String> setter) {
        return stringOption(key, "command", getter, setter);
    }

    /** 字符串选项（prefix = option/command 的 i18n 前缀，防引用错前缀显示原始 key）。 */
    private static Option<String> stringOption(String key, String prefix,
                                               Supplier<String> getter, Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(Component.translatable("config.mockplayer." + prefix + "." + key))
                .description(OptionDescription.of(
                        Component.translatable("config.mockplayer." + prefix + "." + key + ".description")))
                .binding(key, getter, setter)
                .controller(option -> StringControllerBuilder.create(option))
                .build();
    }

    /** 布尔开关：名称/描述/绑定/TickBox。 */
    private static Option<Boolean> booleanOption(String key, boolean fallback,
                                                 Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(Component.translatable("config.mockplayer.option." + key))
                .description(OptionDescription.of(
                        Component.translatable("config.mockplayer.option." + key + ".description")))
                .binding(fallback, getter, setter)
                .controller(option -> TickBoxControllerBuilder.create(option))
                .build();
    }

    /** 三态按钮（点击循环枚举值）：名称/描述/绑定/EnumController。 */
    private static Option<RenderMode> enumOption(String key, RenderMode fallback,
                                                 Supplier<RenderMode> getter, Consumer<RenderMode> setter) {
        return Option.<RenderMode>createBuilder()
                .name(Component.translatable("config.mockplayer.option." + key))
                .description(OptionDescription.of(
                        Component.translatable("config.mockplayer.option." + key + ".description")))
                .binding(fallback, getter, setter)
                .controller(option -> EnumControllerBuilder.create(option)
                        .enumClass(RenderMode.class)
                        .formatValue(mode -> Component.translatable(
                                "config.mockplayer.renderMode." + mode.name().toLowerCase(Locale.ROOT))))
                .build();
    }

    /** 打开原版「键位」列表按钮：找到「打开假人控制台」，点一下按对应键；Esc 解绑。 */
    private static ButtonOption openControlsButton() {
        return ButtonOption.createBuilder()
                .name(Component.translatable("config.mockplayer.open_controls"))
                .text(Component.translatable("config.mockplayer.open_controls"))
                .description(OptionDescription.of(
                        Component.translatable("config.mockplayer.open_controls.description")))
                .action((screen, button) -> {
                    // 直接打开原版「控制 → 键位」列表，一步到位
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(
                            screen, mc.options));
                })
                .build();
    }

    /** 不透明度选项：0.05-1.0，百分比显示，保存即热重载。 */
    private static Option<Double> opacityOption(Supplier<Double> getter, Consumer<Double> setter) {
        return Option.<Double>createBuilder()
                .name(Component.translatable("config.mockplayer.option.guiOpacity"))
                .description(OptionDescription.of(
                        Component.translatable("config.mockplayer.option.guiOpacity.description")))
                .binding((double) ModConfig.DEFAULT_GUI_OPACITY, getter, setter)
                .controller(option -> DoubleFieldControllerBuilder.create(option)
                        .range((double) ModConfig.MIN_GUI_OPACITY, (double) ModConfig.MAX_GUI_OPACITY)
                        .formatValue(value -> Component.literal(
                                String.format(java.util.Locale.ROOT, "%.0f%%", value * 100))))
                .build();
    }
}
