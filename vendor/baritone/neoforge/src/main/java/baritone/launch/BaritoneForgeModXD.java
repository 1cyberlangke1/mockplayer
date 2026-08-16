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

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = "mockplayer_baritone", dist = Dist.CLIENT)
public class BaritoneForgeModXD {

    /** YACL 可选：缺 YACL 时模组列表不出现「配置」按钮，配置仍可手改 settings.txt（零崩溃）。 */
    public BaritoneForgeModXD(ModContainer container) {
        if (ModList.get().isLoaded("yet_another_config_lib_v3")) {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (java.util.function.Supplier<IConfigScreenFactory>)
                            () -> (modContainer, parent) -> {
                                // 反射桥：类加载验证不直接引用 YACL 类，缺 YACL 也不会 NoClassDefFoundError
                                net.minecraft.client.gui.screens.Screen screen =
                                        BaritoneConfigScreenFactory.create(parent);
                                return screen != null ? screen : new BaritoneMissingYaclScreen(parent);
                            });
        }
    }
}
