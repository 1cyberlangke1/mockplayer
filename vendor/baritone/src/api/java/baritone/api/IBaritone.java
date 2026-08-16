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

package com.mockplayer.baritone.api;

import com.mockplayer.baritone.api.behavior.ILookBehavior;
import com.mockplayer.baritone.api.behavior.IPathingBehavior;
import com.mockplayer.baritone.api.cache.IWorldProvider;
import com.mockplayer.baritone.api.Settings;
import com.mockplayer.baritone.api.event.listener.IEventBus;
import com.mockplayer.baritone.api.pathing.calc.IPathingControlManager;
import com.mockplayer.baritone.api.process.*;
import com.mockplayer.baritone.api.selection.ISelectionManager;
import com.mockplayer.baritone.api.utils.IInputOverrideHandler;
import com.mockplayer.baritone.api.utils.IPlayerContext;
import net.minecraft.client.player.LocalPlayer;

/**
 * @author Brady
 * @since 9/29/2018
 */
public interface IBaritone {

    /**
     * @return The {@link IPathingBehavior} instance
     * @see IPathingBehavior
     */
    IPathingBehavior getPathingBehavior();

    /**
     * @return The {@link ILookBehavior} instance
     * @see ILookBehavior
     */
    ILookBehavior getLookBehavior();

    /**
     * @return The {@link IFollowProcess} instance
     * @see IFollowProcess
     */
    IFollowProcess getFollowProcess();

    /**
     * @return The {@link IMineProcess} instance
     * @see IMineProcess
     */
    IMineProcess getMineProcess();

    /**
     * @return The {@link IBuilderProcess} instance
     * @see IBuilderProcess
     */
    IBuilderProcess getBuilderProcess();

    /**
     * @return The {@link IExploreProcess} instance
     * @see IExploreProcess
     */
    IExploreProcess getExploreProcess();

    /**
     * @return The {@link IFarmProcess} instance
     * @see IFarmProcess
     */
    IFarmProcess getFarmProcess();

    /**
     * @return The {@link ICustomGoalProcess} instance
     * @see ICustomGoalProcess
     */
    ICustomGoalProcess getCustomGoalProcess();

    /**
     * @return The {@link IGetToBlockProcess} instance
     * @see IGetToBlockProcess
     */
    IGetToBlockProcess getGetToBlockProcess();

    /**
     * @return The {@link IElytraProcess} instance
     * @see IElytraProcess
     */
    IElytraProcess getElytraProcess();

    /**
     * @return The {@link IWorldProvider} instance
     * @see IWorldProvider
     */
    IWorldProvider getWorldProvider();

    /**
     * Returns the {@link IPathingControlManager} for this {@link IBaritone} instance, which is responsible
     * for managing the {@link IBaritoneProcess}es which control the {@link IPathingBehavior} state.
     *
     * @return The {@link IPathingControlManager} instance
     * @see IPathingControlManager
     */
    IPathingControlManager getPathingControlManager();

    /**
     * @return The {@link IInputOverrideHandler} instance
     * @see IInputOverrideHandler
     */
    IInputOverrideHandler getInputOverrideHandler();

    /**
     * @return The {@link IPlayerContext} instance
     * @see IPlayerContext
     */
    IPlayerContext getPlayerContext();

    /**
     * 本 Baritone 实例的设置（per-instance；假人之间配置独立）。
     * 默认回退到全局设置，实例实现（Baritone）返回自己的 settings。
     *
     * @return settings
     */
    default Settings settings() {
        return com.mockplayer.baritone.api.BaritoneAPI.getSettings();
    }

    /**
     * @return The {@link IEventBus} instance
     * @see IEventBus
     */
    IEventBus getGameEventHandler();

    /**
     * @return The {@link ISelectionManager} instance
     * @see ISelectionManager
     */
    ISelectionManager getSelectionManager();

    /**
     * Open click
     */
    /**
     * 更新绑定的假人 player（假人重生/切换 player 后调用；primary 实例无操作）。
     *
     * @param player 新的假人 LocalPlayer
     */
    default void updateBoundPlayer(LocalPlayer player) {
    }

    /**
     * 关闭并保存当前世界缓存（假人销毁时调用；实现类负责引用清理）。
     */
    default void closeWorldCache() {
    }

    /**
     * 设置假人连接的服务器标识（缓存目录键；"singleplayer" = 本机单机/局域网）。
     *
     * @param serverKey 服务器标识
     */
    default void updateServerKey(String serverKey) {
    }
}
