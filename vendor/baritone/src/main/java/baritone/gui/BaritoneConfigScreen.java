/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mockplayer.baritone.gui;

import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.Settings;
import com.mockplayer.baritone.api.utils.SettingsUtil;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Baritone 全局配置界面（YACL，只编辑全局 {@link BaritoneAPI#getSettings()}，与 mockplayer
 * 的 ModConfigScreen 同款写法）。
 *
 * 输入：全局 Settings（baritone/settings.txt 对应，首次启动为默认值）
 * 输出：保存按钮 → SettingsUtil.save(全局) 写 settings.txt + 全部假人实例 copyFrom（继承新全局）
 *
 * 仅当 YACL 在运行时存在时被构造（平台入口反射桥 + 条件加载），缺失时用
 * {@link BaritoneMissingYaclScreen} 兜底（配置仍可手改 settings.txt）。
 *
 * 说明：全局值只决定「默认」；per-bot override（/control config set）与实例级设置
 * 在 mockplayer 侧管理（NavigateSupport.applyToSession），本界面不改动它们。
 */
public final class BaritoneConfigScreen extends YACLScreen {

    public BaritoneConfigScreen(Screen parent) {
        super(buildYacl(), parent);
    }

    /** 构建 YACL 界面树（行为/日志/渲染三组 + 保存函数）。 */
    private static YetAnotherConfigLib buildYacl() {
        Settings g = BaritoneAPI.getSettings();
        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("baritone.config.title"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("baritone.config.category.behavior"))
                        .tooltip(Component.translatable("baritone.config.category.behavior.tooltip"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("baritone.config.group.movement"))
                                .option(boolOption("allowSprint", g.allowSprint))
                                .option(boolOption("allowBreak", g.allowBreak))
                                .option(boolOption("allowPlace", g.allowPlace))
                                .option(boolOption("allowParkour", g.allowParkour))
                                // baritone 无单一 allowDiagonal：上下坡同值写入
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("baritone.config.option.allowDiagonal"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "baritone.config.option.allowDiagonal.description")))
                                        .binding(g.allowDiagonalAscend.value,
                                                () -> g.allowDiagonalAscend.value,
                                                v -> {
                                                    g.allowDiagonalAscend.value = v;
                                                    g.allowDiagonalDescend.value = v;
                                                })
                                        .controller(TickBoxControllerBuilder::create)
                                        .build())
                                .option(boolOption("avoidance", g.avoidance))
                                .option(boolOption("preferSilkTouch", g.preferSilkTouch))
                                .option(boolOption("mineScanDroppedItems", g.mineScanDroppedItems))
                                .option(intOption("pathTimeoutMs", g.primaryTimeoutMS))
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("baritone.config.group.logging"))
                                .option(boolOption("logToChat", g.logToChat))
                                .option(boolOption("logDebugToChat", g.logDebugToChat))
                                .option(boolOption("logNotificationToChat", g.logNotificationToChat))
                                .option(boolOption("logToastToChat", g.logToastToChat))
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("baritone.config.group.rendering"))
                                .option(boolOption("renderPath", g.renderPath))
                                .option(boolOption("renderGoal", g.renderGoal))
                                .build())
                        .build())
                .save(() -> {
                    SettingsUtil.save(BaritoneAPI.getSettings());
                    // 新全局 → 全部假人实例继承（per-bot override 在 mockplayer 侧
                    // 下次 applyToSession 时重新覆盖；这里保证「改全局立即对现有假人默认生效」）
                    for (IBaritone b : BaritoneAPI.getProvider().getAllBaritones()) {
                        b.settings().copyFrom(BaritoneAPI.getSettings());
                    }
                })
                .build();
    }

    /** 布尔选项（直接绑定 Setting.value）。 */
    private static Option<Boolean> boolOption(String key, Settings.Setting<Boolean> setting) {
        return Option.<Boolean>createBuilder()
                .name(Component.translatable("baritone.config.option." + key))
                .description(OptionDescription.of(
                        Component.translatable("baritone.config.option." + key + ".description")))
                .binding(setting.value, () -> setting.value, v -> setting.value = v)
                .controller(TickBoxControllerBuilder::create)
                .build();
    }

    /** 整数选项（pathTimeoutMs → primaryTimeoutMS，long）。 */
    private static Option<Integer> intOption(String key, Settings.Setting<Long> setting) {
        return Option.<Integer>createBuilder()
                .name(Component.translatable("baritone.config.option." + key))
                .description(OptionDescription.of(
                        Component.translatable("baritone.config.option." + key + ".description")))
                .binding((int) Math.min(Integer.MAX_VALUE, setting.value),
                        () -> (int) Math.min(Integer.MAX_VALUE, setting.value),
                        v -> setting.value = (long) v)
                .controller(IntegerFieldControllerBuilder::create)
                .build();
    }

    /** 当前全局布尔值（YACL binding getter；allowDiagonal 读 ascend 代表上下坡同值）。 */
    private static boolean readBool(String key) {
        Settings g = BaritoneAPI.getSettings();
        return switch (key) {
            case "allowSprint" -> g.allowSprint.value;
            case "allowBreak" -> g.allowBreak.value;
            case "allowPlace" -> g.allowPlace.value;
            case "allowParkour" -> g.allowParkour.value;
            case "allowDiagonal" -> g.allowDiagonalAscend.value;
            case "avoidance" -> g.avoidance.value;
            case "preferSilkTouch" -> g.preferSilkTouch.value;
            case "mineScanDroppedItems" -> g.mineScanDroppedItems.value;
            case "logToChat" -> g.logToChat.value;
            case "logDebugToChat" -> g.logDebugToChat.value;
            case "logNotificationToChat" -> g.logNotificationToChat.value;
            case "logToastToChat" -> g.logToastToChat.value;
            case "renderPath" -> g.renderPath.value;
            case "renderGoal" -> g.renderGoal.value;
            default -> false;
        };
    }
}
