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

package com.mockplayer.baritone;

import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.Settings;
import com.mockplayer.baritone.api.behavior.IBehavior;
import com.mockplayer.baritone.api.event.listener.IEventBus;
import com.mockplayer.baritone.api.process.IBaritoneProcess;
import com.mockplayer.baritone.api.process.IElytraProcess;
import com.mockplayer.baritone.api.utils.IPlayerContext;
import com.mockplayer.baritone.behavior.*;
import com.mockplayer.baritone.cache.WorldProvider;
import com.mockplayer.baritone.event.GameEventHandler;
import com.mockplayer.baritone.process.*;
import com.mockplayer.baritone.selection.SelectionManager;
import com.mockplayer.baritone.utils.BlockStateInterface;
import com.mockplayer.baritone.utils.InputOverrideHandler;
import com.mockplayer.baritone.utils.PathingControlManager;
import com.mockplayer.baritone.utils.player.BaritonePlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Brady
 * @since 7/31/2018
 */
public class Baritone implements IBaritone {

    private static final ThreadPoolExecutor threadPool;

    static {
        threadPool = new ThreadPoolExecutor(4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    }

    private final Minecraft mc;
    private final Path directory;

    private final GameEventHandler gameEventHandler;

    private final PathingBehavior pathingBehavior;
    private final LookBehavior lookBehavior;
    private final InventoryBehavior inventoryBehavior;
    private final InputOverrideHandler inputOverrideHandler;

    private final FollowProcess followProcess;
    private final MineProcess mineProcess;
    private final GetToBlockProcess getToBlockProcess;
    private final CustomGoalProcess customGoalProcess;
    private final BuilderProcess builderProcess;
    private final ExploreProcess exploreProcess;
    private final FarmProcess farmProcess;
    private final InventoryPauserProcess inventoryPauserProcess;
    private final IElytraProcess elytraProcess;

    private final PathingControlManager pathingControlManager;
    private final SelectionManager selectionManager;

    private final IPlayerContext playerContext;
    private final WorldProvider worldProvider;

    public BlockStateInterface bsi;

    /** 本实例自己的设置（per-instance，假人之间配置独立；不再用全局静态单例） */
    public final Settings settings = new Settings();
    /** 假人连接的服务器标识（null = 主玩家语义；非 null 时做缓存目录键）。 */
    private volatile String serverKey;

    Baritone(Minecraft mc) {
        this(mc, null, null);
    }

    /**
     * @param mc       Minecraft 单例
     * @param player   绑定的假人 player（null = 动态 mc.player）
     * @param gameMode 绑定的假人 gameMode（null = 用 mc.gameMode）
     */
    Baritone(Minecraft mc, LocalPlayer player, MultiPlayerGameMode gameMode) {
        this.mc = mc;
        this.gameEventHandler = new GameEventHandler(this);

        this.directory = mc.gameDirectory.toPath().resolve("baritone");
        if (!Files.exists(this.directory)) {
            try {
                Files.createDirectories(this.directory);
            } catch (IOException ignored) {}
        }

        // Define this before behaviors try and get it, or else it will be null and the builds will fail!
        this.playerContext = new BaritonePlayerContext(this, mc, player, gameMode);

        {
            this.lookBehavior         = this.registerBehavior(LookBehavior::new);
            this.pathingBehavior      = this.registerBehavior(PathingBehavior::new);
            this.inventoryBehavior    = this.registerBehavior(InventoryBehavior::new);
            this.inputOverrideHandler = this.registerBehavior(InputOverrideHandler::new);
            this.registerBehavior(WaypointBehavior::new);
        }

        this.pathingControlManager = new PathingControlManager(this);
        {
            this.followProcess           = this.registerProcess(FollowProcess::new);
            this.mineProcess             = this.registerProcess(MineProcess::new);
            this.customGoalProcess       = this.registerProcess(CustomGoalProcess::new); // very high iq
            this.getToBlockProcess       = this.registerProcess(GetToBlockProcess::new);
            this.builderProcess          = this.registerProcess(BuilderProcess::new);
            this.exploreProcess          = this.registerProcess(ExploreProcess::new);
            this.farmProcess             = this.registerProcess(FarmProcess::new);
            this.inventoryPauserProcess  = this.registerProcess(InventoryPauserProcess::new);
            this.elytraProcess           = this.registerProcess(ElytraProcess::create);
            this.registerProcess(BackfillProcess::new);
        }

        this.worldProvider = new WorldProvider(this);
        this.selectionManager = new SelectionManager(this);
    }

    public void registerBehavior(IBehavior behavior) {
        this.gameEventHandler.registerEventListener(behavior);
    }

    public <T extends IBehavior> T registerBehavior(Function<Baritone, T> constructor) {
        final T behavior = constructor.apply(this);
        this.registerBehavior(behavior);
        return behavior;
    }

    public <T extends IBaritoneProcess> T registerProcess(Function<Baritone, T> constructor) {
        final T behavior = constructor.apply(this);
        this.pathingControlManager.registerProcess(behavior);
        return behavior;
    }

    @Override
    public PathingControlManager getPathingControlManager() {
        return this.pathingControlManager;
    }

    @Override
    public InputOverrideHandler getInputOverrideHandler() {
        return this.inputOverrideHandler;
    }

    @Override
    public CustomGoalProcess getCustomGoalProcess() {
        return this.customGoalProcess;
    }

    @Override
    public GetToBlockProcess getGetToBlockProcess() {
        return this.getToBlockProcess;
    }

    @Override
    public IPlayerContext getPlayerContext() {
        return this.playerContext;
    }

    @Override
    public FollowProcess getFollowProcess() {
        return this.followProcess;
    }

    @Override
    public BuilderProcess getBuilderProcess() {
        return this.builderProcess;
    }

    public InventoryBehavior getInventoryBehavior() {
        return this.inventoryBehavior;
    }

    @Override
    public LookBehavior getLookBehavior() {
        return this.lookBehavior;
    }

    @Override
    public ExploreProcess getExploreProcess() {
        return this.exploreProcess;
    }

    @Override
    public MineProcess getMineProcess() {
        return this.mineProcess;
    }

    @Override
    public FarmProcess getFarmProcess() {
        return this.farmProcess;
    }

    public InventoryPauserProcess getInventoryPauserProcess() {
        return this.inventoryPauserProcess;
    }

    @Override
    public PathingBehavior getPathingBehavior() {
        return this.pathingBehavior;
    }

    @Override
    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    @Override
    public WorldProvider getWorldProvider() {
        return this.worldProvider;
    }

    @Override
    public IEventBus getGameEventHandler() {
        return this.gameEventHandler;
    }

    @Override
    public IElytraProcess getElytraProcess() {
        return this.elytraProcess;
    }

    public Path getDirectory() {
        return this.directory;
    }

    public Settings settings() {
        return this.settings;
    }

    /** 设置服务器标识（mockplayer 假人创建时写入；"singleplayer" = 本机单机/局域网）。 */
    public void setServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    /** 服务器标识（null = 未设置，走主玩家语义）。 */
    public String getServerKey() {
        return this.serverKey;
    }

    /** 假人重生/切换 player 后更新绑定引用（primary 实例是空操作）。 */
    @Override
    public void updateBoundPlayer(LocalPlayer player) {
        ((BaritonePlayerContext) this.playerContext).setPlayer(player);
    }

    /** 关闭并保存当前世界缓存（假人销毁时调用；无其他实例引用时从静态 map 移除）。 */
    @Override
    public void closeWorldCache() {
        this.worldProvider.closeWorld();
    }

    @Override
    public void updateServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    public static Executor getExecutor() {
        return threadPool;
    }
}
