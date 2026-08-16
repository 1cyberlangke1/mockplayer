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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 缺 YACL 时的兜底界面：只提示配置在 baritone/settings.txt 手改（与 mockplayer 的
 * MissingYaclScreen 同款交互，vendor 自包含不依赖主项目）。
 */
public final class BaritoneMissingYaclScreen extends Screen {

    private final Screen parent;

    public BaritoneMissingYaclScreen(Screen parent) {
        super(Component.translatable("baritone.config.missing_yacl.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(this.width / 2 - 75, this.height / 2 + 30, 150, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font,
                Component.translatable("baritone.config.missing_yacl.line1"),
                this.width / 2, this.height / 2 - 20, 0xFFFFFF);
        graphics.centeredText(this.font,
                Component.translatable("baritone.config.missing_yacl.line2"),
                this.width / 2, this.height / 2 + 2, 0xAAAAAA);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
