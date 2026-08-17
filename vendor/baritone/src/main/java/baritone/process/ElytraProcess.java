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

package com.mockplayer.baritone.process;

import com.mockplayer.baritone.Baritone;
import com.mockplayer.baritone.api.Settings;
import com.mockplayer.baritone.api.IBaritone;
import com.mockplayer.baritone.api.event.events.*;
import com.mockplayer.baritone.api.event.events.type.EventState;
import com.mockplayer.baritone.api.event.listener.AbstractGameEventListener;
import com.mockplayer.baritone.api.pathing.goals.Goal;
import com.mockplayer.baritone.api.pathing.goals.GoalBlock;
import com.mockplayer.baritone.api.pathing.goals.GoalXZ;
import com.mockplayer.baritone.api.pathing.goals.GoalYLevel;
import com.mockplayer.baritone.api.pathing.movement.IMovement;
import com.mockplayer.baritone.api.pathing.path.IPathExecutor;
import com.mockplayer.baritone.api.process.IBaritoneProcess;
import com.mockplayer.baritone.api.process.IElytraProcess;
import com.mockplayer.baritone.api.process.PathingCommand;
import com.mockplayer.baritone.api.process.PathingCommandType;
import com.mockplayer.baritone.api.utils.BetterBlockPos;
import com.mockplayer.baritone.api.utils.Rotation;
import com.mockplayer.baritone.api.utils.RotationUtils;
import com.mockplayer.baritone.api.utils.input.Input;
import com.mockplayer.baritone.pathing.movement.CalculationContext;
import com.mockplayer.baritone.pathing.movement.movements.MovementFall;
import com.mockplayer.baritone.process.elytra.*;
import com.mockplayer.baritone.utils.BaritoneProcessHelper;
import com.mockplayer.baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.mockplayer.baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private NetherPathfinderContext npfContext;
    private boolean predictingTerrain;
    private boolean allowTight;
    private boolean allowAboveBuildLimit;
    private boolean allowAboveRoof;
    /** mockplayer P15：平地起飞等待滑翔的 tick 计数（超时放弃，防输入锁死）。 */
    private int startFlyingTicks;
    private final Semaphore npfSema = new Semaphore(1);

    private static final int SHORT_LANDING_COLUMN_HEIGHT = 15;
    private static final int LONG_LANDING_COLUMN_HEIGHT = 39;
    private static final long LANDING_SEARCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(25); // half a tick
    private int landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();
    private LandingSearchState landingSearchState;

    @Override
    public void onLostControl() {
        onLostControl(true);
    }

    public void onLostControl(boolean destroyNpf) {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.reachedGoal = false;
        this.goal = null;
        destroyBehaviorAsync();
        if (destroyNpf) {
            destroyNpfContextAsync();
        }
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        System.err.println("[elytra-diag] onTick state=" + this.state
                + " fallFlying=" + (ctx.player() != null && ctx.player().isFallFlying())
                + " active=" + this.isActive());
        try {
            final long seedSetting = settings().elytraNetherSeed.value;
            if (seedSetting != this.behavior.npfContext.getSeed()) {
                logDirect(Component.translatableEscape("baritone.log.elytra.seed_changed"));
                this.resetState();
            }
            if (predictingTerrain != settings().elytraPredictTerrain.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect(Component.translatableEscape("baritone.log.elytra.setting_predict"));
                predictingTerrain = settings().elytraPredictTerrain.value;
                this.resetState();
            }
            if (allowTight != settings().elytraAllowTightSpaces.value) {
                logDirect(Component.translatableEscape("baritone.log.elytra.setting_tight"));
                allowTight = settings().elytraAllowTightSpaces.value;
                this.resetState();
            }
            if (allowAboveBuildLimit != settings().elytraAllowAboveBuildLimit.value) {
                logDirect(Component.translatableEscape("baritone.log.elytra.setting_build_limit"));
                allowAboveBuildLimit = settings().elytraAllowAboveBuildLimit.value;
                this.resetState();
            }
            if (allowAboveRoof != settings().elytraAllowAboveRoof.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect(Component.translatableEscape("baritone.log.elytra.setting_roof"));
                allowAboveRoof = settings().elytraAllowAboveRoof.value;
                this.resetState();
            }
        } catch (IllegalArgumentException e) {
            logDirect(e.getMessage(), ChatFormatting.RED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        this.behavior.onTick();

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && shouldLandForSafety()) {
            if (settings().elytraAllowEmergencyLand.value) {
                logDirect(Component.translatableEscape("baritone.log.elytra.emergency_land"));
                safetyLanding = true;
            } else {
                logDirect(Component.translatableEscape("baritone.log.elytra.no_emergency_land"));
            }
        }
        if (ctx.player().isFallFlying() && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (last.distToCenterSqr(ctx.player().position()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                if (this.landingSearchState == null) {
                    logDirect(Component.translatableEscape("baritone.log.elytra.land_search"));
                }
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    logDirect(Component.translatableEscape("baritone.log.elytra.land_found"));
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                    this.goingToLandingSpot = true;
                } else {
                    this.goingToLandingSpot = false;
                }
            }

            if (last != null && last.distToCenterSqr(ctx.player().position()) < 1) {
                if (settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification(Component.translatableEscape("baritone.log.misc.pathing_complete"), false);
                }
                if (settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot && landingSpot != null) {
                    this.state = State.LANDING;
                    logDirect(Component.translatableEscape("baritone.log.elytra.landing"));
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - this.landingColumnHeight) {
                    logDirect(Component.translatableEscape("baritone.log.elytra.land_bad"));
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.behavior.npfContext.tryAcquireReadLock()) {
                try {
                    behavior.tick();
                } finally {
                    this.behavior.npfContext.releaseReadLock();
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect(Component.translatableEscape("baritone.log.elytra.land_wait"));
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect(Component.translatableEscape("baritone.log.elytra.done"));
            baritone.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround() && settings().elytraAutoJump.value
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect(Component.translatableEscape("baritone.log.elytra.no_takeoff"));
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.behavior == null) {
                // mockplayer P15（解锁修复）：behavior 已释放（onLostControl 后），
                // 不再 forced jump——否则假人原地无限跳 + 输入锁死
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // mockplayer P15（平地起飞）：原版只在下落中（deltaMovement.y < -0.377）按跳，
            // 平地假人（autoJump=false 或找不到悬崖）永远不跳 → 卡死在 START_FLYING：
            // 路径已算出但人不飞（生产「一眼有路径但直接失败」根因）。
            // 改为始终按跳：平地持续按跳 → 跳起离地 → 原版 aiStep 的
            // 「jump 边沿（!wasJumping）+ tryToStartFallFlying」自动开鞘翅
            // （服务端验证鞘翅装备后进入滑翔）。已滑翔时切 FLYING 交回 ElytraBehavior。
            if (ctx.player().isFallFlying()) {
                this.state = State.FLYING;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // mockplayer P15（平地起飞 v2）：forced jump 持续按住时原版
            // 「!wasJumping（jumpTriggerTime==0）」边沿永不成立 → 站地面永远触发不了
            // tryToStartFallFlying → 开不了鞘翅。改为显式等效双击跳跃：
            // tryToStartFallFlying + START_FALL_FLYING 包（服务端验证鞘翅装备），
            // 再配合 forced jump 跳起离地保持 flag（着地逻辑不会清滑翔）。
            if (ctx.player() instanceof LocalPlayer localPlayer) {
                if (localPlayer.tryToStartFallFlying()) {
                    localPlayer.connection.send(new ServerboundPlayerCommandPacket(
                            localPlayer, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
            }
            // mockplayer P15（解锁修复）：起飞超时（7.5 秒没进入滑翔：未穿鞘翅/
            // flag 未生效）→ 放弃任务释放控制，防止无限跳 + 输入锁死
            if (++this.startFlyingTicks > 150) {
                logDirect(Component.translatableEscape("baritone.log.elytra.takeoff_timeout"));
                this.onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                // 保持原版下落中语义（跳崖后下落加速），同时平地也覆盖（上面已按）
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // 兜底（防御）：所有 State 枚举值在上方分支均有 return，此路径不可达
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(() -> {
                behavior.destroy();
            });
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.npfContext == null) return;

        ChunkSource chunkProvider = ctx.world().getChunkSource();
        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    npfContext.queueForPacking(chunk);
                }
            }
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public List<BetterBlockPos> getPath() {
        return this.behavior != null ? behavior.pathManager.getPath() : Collections.emptyList();
    }

    @Override
    public void pathTo(BlockPos destination) {
        if (!isSupportedPos(destination)) {
            throw new IllegalArgumentException("The goal must be within bounds to use elytra flight.");
        }

        if (ctx.player() != null && !isSupportedPos(ctx.playerFeet())) {
            throw new IllegalArgumentException("The player must be within bounds to use elytra flight.");
        }

        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null) {
            return;
        }
        this.startFlyingTicks = 0;
        this.onLostControl(false);
        this.predictingTerrain = ctx.player().level().dimension() == Level.NETHER && settings().elytraPredictTerrain.value;
        this.allowTight = settings().elytraAllowTightSpaces.value;
        this.allowAboveBuildLimit = settings().elytraAllowAboveBuildLimit.value;
        this.allowAboveRoof = settings().elytraAllowAboveRoof.value;
        this.behavior = new ElytraBehavior(this.baritone, this, getNpfContext(), destination, appendDestination);

        if (ctx.world() != null) {
            this.repackChunks();
        }
        this.behavior.pathTo();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            // ElytraBehavior will automatically change the destination height depending on if we're above or below the roof
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }

        this.pathTo((new BlockPos(x, y, z)));
    }

    private boolean isSupportedPos(BlockPos pos) {
        final boolean isNether = ctx.world().dimension() == Level.NETHER;
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = (isNether && !settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, ctx.world().dimensionType().height() + minY);

        final boolean aboveRoof = settings().elytraAllowAboveRoof.value;
        final boolean aboveBuild = settings().elytraAllowAboveBuildLimit.value;

        final boolean enforceMaxY = isNether ? !(aboveRoof && aboveBuild) : !aboveBuild;

        if (pos.getY() < minY) {
            return false;
        }

        return !enforceMaxY || pos.getY() < maxY;
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(Level dim, BlockPos pos, Settings settings) {
        DimensionType dimType = dim.dimensionType();
        int minY = dimType.minY();
        int maxY = (dim.dimension() == Level.NETHER && !settings.elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, dimType.height() + minY);
        return pos.getY() >= minY && pos.getY() < maxY;
    }

    private boolean isSafeBlock(Block block) {
        return block == Blocks.NETHERRACK || block == Blocks.GRAVEL || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL || (block == Blocks.NETHER_BRICKS && settings().elytraAllowLandOnNetherFortress.value)
                || block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRASS_BLOCK || block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.TERRACOTTA
                || block == Blocks.SNOW || block == Blocks.ICE || block == Blocks.MYCELIUM || block == Blocks.PODZOL
                || block == Blocks.DARK_OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.END_STONE || block == Blocks.BEDROCK
                || block == Blocks.OBSIDIAN || block == Blocks.COBBLESTONE;
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().dimensionType().minY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        final boolean useHeightmap = ctx.player().getY() > ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ());
        if (this.landingSearchState == null || !this.landingSearchState.isCompatible(start, useHeightmap)) {
            this.landingSearchState = new LandingSearchState(start, this.behavior.destination, useHeightmap);
        } else {
            this.landingSearchState.updateStartPosition(start);
        }

        BetterBlockPos landingSpot = this.landingSearchState.advance();
        if (landingSpot != null || this.landingSearchState.exhausted) {
            this.landingSearchState = null;
        }
        return landingSpot;
    }

    private boolean isChunkLoaded(BetterBlockPos pos) {
        return ctx.world().getChunkSource().hasChunk(pos.x >> 4, pos.z >> 4);
    }

    private final class LandingSearchState {
        private final BetterBlockPos origin;
        private final boolean useHeightmap;
        private final Queue<BetterBlockPos> queue;
        private final Set<BetterBlockPos> visited = new HashSet<>();
        private final LongOpenHashSet checkedPositions = new LongOpenHashSet();
        private boolean exhausted;

        private LandingSearchState(BetterBlockPos origin, BetterBlockPos dest, boolean useHeightmap) {
            this.origin = origin;
            this.useHeightmap = useHeightmap;

            final BetterBlockPos target = isChunkLoaded(dest) ? dest : origin;
            this.queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - target.x) * (pos.x - target.x) + (pos.z - target.z) * (pos.z - target.z)).thenComparingInt(pos -> -pos.y));
            this.queue.add(target);
        }

        private boolean isCompatible(BetterBlockPos start, boolean useHeightmap) {
            // Restart if we've moved more than a chunk so the priority adjusts and newly loaded chunks get revisited
            return this.useHeightmap == useHeightmap && this.origin.distanceSq(start) <= (16 * 16);
        }

        private void updateStartPosition(BetterBlockPos start) {
            if (this.visited.add(start)) {
                this.queue.add(start);
            }
        }

        private BetterBlockPos advance() {
            final long deadline = System.nanoTime() + LANDING_SEARCH_BUDGET_NANOS;
            while (!this.queue.isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                BetterBlockPos qPos = this.queue.poll();
                if (!isChunkLoaded(qPos)) {
                    continue;
                }
                BetterBlockPos landing = this.useHeightmap ? this.advanceHeightmap(qPos) : this.advanceUnderground(qPos);
                if (landing != null) {
                    return landing;
                }
            }
            this.exhausted = true;
            return null;
        }

        private BetterBlockPos advanceUnderground(BetterBlockPos pos) {
        if (isInBounds(ctx.world(), pos, settings()) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
                    if (isColumnAir(actualLandingSpot, landingColumnHeight) && hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
                if (this.visited.add(pos.above())) this.queue.add(pos.above());
                if (this.visited.add(pos.below())) this.queue.add(pos.below());
            }
            return null;
        }

        private BetterBlockPos advanceHeightmap(BetterBlockPos qPos) {
            int height = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, qPos.getX(), qPos.getZ());
            BetterBlockPos pos = new BetterBlockPos(qPos.getX(), height + 1, qPos.getZ());
        if (isInBounds(ctx.world(), pos, settings()) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = ctx.playerFeet().y - actualLandingSpot.y < LONG_LANDING_COLUMN_HEIGHT ? SHORT_LANDING_COLUMN_HEIGHT : LONG_LANDING_COLUMN_HEIGHT;
                    if (hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
            }
            return null;
        }
    }

    private NetherPathfinderContext getNpfContext() {
        if(this.npfContext == null) {
            npfSema.acquireUninterruptibly();
            this.npfContext = new NetherPathfinderContext(
                    settings().elytraNetherSeed.value,
                    settings().elytraUseCache.value ? baritone.getWorldProvider().getCurrentWorld().directory.resolve("cache") : null,
                    ctx.world(),
                    settings()
            );
        }
        return this.npfContext;
    }

    private void destroyNpfContextAsync() {
        NetherPathfinderContext npf = this.npfContext;
        if (npf != null) {
            this.npfContext = null;
            Baritone.getExecutor().execute(() -> {
                npf.destroy();
                npfSema.release();
            });
        }
    }
}
