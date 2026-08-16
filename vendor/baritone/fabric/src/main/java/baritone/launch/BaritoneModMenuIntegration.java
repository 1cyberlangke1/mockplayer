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

package com.mockplayer.baritone.launch;

import com.mockplayer.baritone.gui.BaritoneConfigScreenFactory;
import com.mockplayer.baritone.gui.BaritoneMissingYaclScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 模组列表入口（baritone-mockplayer 条目，ModMenu 为可选依赖）。
 *
 * 输入：玩家在模组列表点 baritone 的「配置」
 * 输出：有 YACL → Baritone YACL 配置界面；无 YACL → 手改 settings.txt 提示界面
 */
public class BaritoneModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            net.minecraft.client.gui.screens.Screen screen =
                    BaritoneConfigScreenFactory.create(parent);
            return screen != null ? screen : new BaritoneMissingYaclScreen(parent);
        };
    }
}
