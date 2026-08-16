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

package com.mockplayer.baritone.utils.player;

import com.mockplayer.baritone.Baritone;
import com.mockplayer.baritone.api.Settings;
import com.mockplayer.baritone.api.cache.IWorldData;
import com.mockplayer.baritone.api.utils.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/**
 * Implementation of {@link IPlayerContext} that provides information about the primary player.
 *
 * @author Brady
 * @since 11/12/2018
 */
public final class BaritonePlayerContext implements IPlayerContext {

    private final Baritone baritone;
    private final Minecraft mc;
    private final IPlayerController playerController;
    /** 绑定的假人 player（null = 动态读 mc.player）。 */
    private volatile LocalPlayer boundPlayer;
    /** 绑定的假人 gameMode（null = 用 mc.gameMode）。 */
    private final MultiPlayerGameMode boundGameMode;

    public BaritonePlayerContext(Baritone baritone, Minecraft mc) {
        this(baritone, mc, null, null);
    }

    /**
     * @param baritone 所属实例
     * @param mc       Minecraft 单例
     * @param player   绑定的假人 player（null = 动态 mc.player）
     * @param gameMode 绑定的假人 gameMode（null = 用 mc.gameMode）
     */
    public BaritonePlayerContext(Baritone baritone, Minecraft mc,
                                 LocalPlayer player, MultiPlayerGameMode gameMode) {
        this.baritone = baritone;
        this.mc = mc;
        this.boundPlayer = player;
        this.boundGameMode = gameMode;
        this.playerController = new BaritonePlayerController(mc, gameMode);
    }

    /** 假人重生/切换 player 后更新绑定引用。 */
    public void setPlayer(LocalPlayer player) {
        this.boundPlayer = player;
    }

    @Override
    public Minecraft minecraft() {
        return this.mc;
    }

    @Override
    public LocalPlayer player() {
        LocalPlayer bound = this.boundPlayer;
        return bound != null ? bound : this.mc.player;
    }

    @Override
    public IPlayerController playerController() {
        return this.playerController;
    }

    @Override
    public Level world() {
        LocalPlayer bound = this.boundPlayer;
        if (bound != null) {
            Level level = bound.level();
            if (level != null) {
                return level;
            }
        }
        return this.mc.level;
    }

    @Override
    public IWorldData worldData() {
        return this.baritone.getWorldProvider().getCurrentWorld();
    }

    @Override
    public Settings settings() {
        return this.baritone.settings();
    }

    @Override
    public BetterBlockPos viewerPos() {
        LocalPlayer bound = this.boundPlayer;
        if (bound != null) {
            return BetterBlockPos.from(bound.blockPosition());
        }
        final Entity entity = this.mc.getCameraEntity();
        return entity == null ? this.playerFeet() : BetterBlockPos.from(entity.blockPosition());
    }

    @Override
    public Rotation playerRotations() {
        return this.baritone.getLookBehavior().getEffectiveRotation().orElseGet(IPlayerContext.super::playerRotations);
    }

    @Override
    public HitResult objectMouseOver() {
        return RayTraceUtils.rayTraceTowards(player(), playerRotations(), playerController().getBlockReachDistance());
    }
}
