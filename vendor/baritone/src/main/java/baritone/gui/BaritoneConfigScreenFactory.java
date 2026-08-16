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

import net.minecraft.client.gui.screens.Screen;

/**
 * 反射创建 Baritone YACL 配置界面（YACL 可选依赖安全桥）。
 *
 * 为什么必须反射：JVM 在类加载验证阶段就会解析方法里 {@code new} 的目标类，
 * BaritoneConfigScreen 继承 YACLScreen；缺 YACL 时任何直接引用它的类都会在加载瞬间
 * 抛 NoClassDefFoundError。本类只引用类名字符串，缺 YACL 时完全安全。
 *
 * 输入：父界面；输出：YACL 配置界面；缺 YACL / 类加载失败 → null（调用方兜底）。
 */
public final class BaritoneConfigScreenFactory {

    private BaritoneConfigScreenFactory() {
    }

    /** 反射构造 BaritoneConfigScreen；失败返回 null，绝不抛出（调用方用 BaritoneMissingYaclScreen 兜底）。 */
    public static Screen create(Screen parent) {
        try {
            Class<?> screenClass = Class.forName("com.mockplayer.baritone.gui.BaritoneConfigScreen");
            return (Screen) screenClass.getConstructor(Screen.class).newInstance(parent);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
