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

package com.mockplayer.baritone.utils;

import com.mockplayer.baritone.Baritone;
import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.baritone.api.event.events.TickEvent;
import com.mockplayer.baritone.api.utils.IInputOverrideHandler;
import com.mockplayer.baritone.api.utils.input.Input;
import com.mockplayer.baritone.behavior.Behavior;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    /** 写空输入（stop 后清残留 moveVector/keyPresses；主项目 stop 路径调用）。 */
    public void clearInput() {
        ClientInput input = ctx.player().input;
        if (input == null) {
            return;
        }
        com.mockplayer.baritone.utils.accessor.IClientInputAccessor accessor =
                (com.mockplayer.baritone.utils.accessor.IClientInputAccessor) input;
        accessor.baritone$setMoveVector(new Vec2(0.0F, 0.0F));
        accessor.baritone$setKeyPresses(new net.minecraft.world.entity.player.Input(
                false, false, false, false, false, false, false));
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT));
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));

        if (inControl()) {
            writeInput();
        }
    }

    /**
     * 直接写假人 input 字段（不替换 input 对象，与 BotActions 同一写入机制；
     * 谁活跃谁写，空闲时让位 BotActions）。时序：Tick IN 事件在假人
     * LocalPlayer.tick 之前，本 tick 物理直接读本次写入。
     */
    private void writeInput() {
        ClientInput input = ctx.player().input;
        if (input == null) {
            return;
        }
        float leftImpulse = 0.0F;
        float forwardImpulse = 0.0F;
        boolean up = isInputForcedDown(Input.MOVE_FORWARD);
        if (up) {
            forwardImpulse++;
        }
        boolean down = isInputForcedDown(Input.MOVE_BACK);
        if (down) {
            forwardImpulse--;
        }
        boolean left = isInputForcedDown(Input.MOVE_LEFT);
        if (left) {
            leftImpulse++;
        }
        boolean right = isInputForcedDown(Input.MOVE_RIGHT);
        if (right) {
            leftImpulse--;
        }
        boolean sneaking = isInputForcedDown(Input.SNEAK);
        if (sneaking) {
            leftImpulse *= 0.3F;
            forwardImpulse *= 0.3F;
        }
        com.mockplayer.baritone.utils.accessor.IClientInputAccessor accessor =
                (com.mockplayer.baritone.utils.accessor.IClientInputAccessor) input;
        accessor.baritone$setMoveVector(new Vec2(leftImpulse, forwardImpulse));
        boolean jumping = isInputForcedDown(Input.JUMP);
        boolean sprinting = isInputForcedDown(Input.SPRINT);
        accessor.baritone$setKeyPresses(new net.minecraft.world.entity.player.Input(
                up, down, left, right, jumping, sneaking, sprinting));
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // 有路径段执行中，或有活跃进程（goTo/follow/mine/elytra）→ 接管输入
        return baritone.getPathingBehavior().isPathing()
                || baritone.getPathingControlManager().mostRecentInControl()
                .map(proc -> proc.isActive()).orElse(false);
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
