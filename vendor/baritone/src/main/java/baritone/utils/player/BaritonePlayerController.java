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

import com.mockplayer.baritone.api.utils.IPlayerController;
import com.mockplayer.baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;


/**
 * Implementation of {@link IPlayerController} that chains to the primary player controller's methods
 *
 * @author Brady
 * @since 12/14/2018
 */
public final class BaritonePlayerController implements IPlayerController {

    private final Minecraft mc;
    /** 绑定的假人 gameMode（null = 用 mc.gameMode）。 */
    private final MultiPlayerGameMode boundGameMode;

    public BaritonePlayerController(Minecraft mc) {
        this(mc, null);
    }

    /**
     * @param mc       Minecraft 单例
     * @param gameMode 绑定的假人 gameMode（null = 用 mc.gameMode）
     */
    public BaritonePlayerController(Minecraft mc, MultiPlayerGameMode gameMode) {
        this.mc = mc;
        this.boundGameMode = gameMode;
    }

    private MultiPlayerGameMode gameMode() {
        MultiPlayerGameMode bound = this.boundGameMode;
        return bound != null ? bound : this.mc.gameMode;
    }

    @Override
    public void syncHeldItem() {
        ((IPlayerControllerMP) this.gameMode()).callSyncCurrentPlayItem();
    }

    @Override
    public boolean hasBrokenBlock() {
        return !((IPlayerControllerMP) this.gameMode()).isHittingBlock();
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        return this.gameMode().continueDestroyBlock(pos, side);
    }

    @Override
    public void resetBlockRemoving() {
        this.gameMode().stopDestroyBlock();
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player player) {
        this.gameMode().handleContainerInput(windowId, slotId, mouseButton, type, player);
    }

    @Override
    public GameType getGameType() {
        return this.gameMode().getPlayerMode();
    }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result) {
        // primaryplayercontroller is always in a ClientWorld so this is ok
        return this.gameMode().useItemOn(player, hand, result);
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level world, InteractionHand hand) {
        return this.gameMode().useItem(player, hand);
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        return this.gameMode().startDestroyBlock(loc, face);
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        ((IPlayerControllerMP) this.gameMode()).setIsHittingBlock(hittingBlock);
    }
}
