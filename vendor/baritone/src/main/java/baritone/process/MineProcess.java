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

import net.minecraft.network.chat.Component;
import com.mockplayer.baritone.Baritone;
import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.baritone.api.pathing.goals.*;
import com.mockplayer.baritone.api.process.IMineProcess;
import com.mockplayer.baritone.api.process.PathingCommand;
import com.mockplayer.baritone.api.process.PathingCommandType;
import com.mockplayer.baritone.api.utils.*;
import com.mockplayer.baritone.api.utils.input.Input;
import com.mockplayer.baritone.cache.CachedChunk;
import com.mockplayer.baritone.pathing.movement.CalculationContext;
import com.mockplayer.baritone.pathing.movement.MovementHelper;
import com.mockplayer.baritone.utils.BaritoneProcessHelper;
import com.mockplayer.baritone.utils.BlockStateInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

import static com.mockplayer.baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess {

    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;

    public MineProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (desiredQuantity > 0) {
            int curr = ctx.player().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> filter.has(stack))
                    .mapToInt(ItemStack::getCount).sum();
            if (curr >= desiredQuantity) {
                logDirect(Component.translatableEscape("baritone.log.mine.have_items", curr));
                cancel();
                return null;
            }
        }
        if (calcFailed) {
            if (!knownOreLocations.isEmpty() && settings().blacklistClosestOnFailure.value) {
                logDirect(Component.translatableEscape("baritone.log.mine.no_path_blacklist", filter));
                if (settings().notificationOnMineFail.value) {
                    logNotification(Component.translatableEscape("baritone.log.mine.no_path_blacklist", filter), true);
                }
                knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(blacklist::add);
                knownOreLocations.removeIf(blacklist::contains);
            } else {
                logDirect(Component.translatableEscape("baritone.log.mine.no_path_cancel", filter));
                if (settings().notificationOnMineFail.value) {
                    logNotification(Component.translatableEscape("baritone.log.mine.no_path_cancel", filter), true);
                }
                cancel();
                return null;
            }
        }

        updateLoucaSystem();
        int mineGoalUpdateInterval = settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            Baritone.getExecutor().execute(() -> rescan(curr, context));
        }
        if (settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        Optional<BlockPos> shaft = curr.stream()
                .filter(pos -> pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ())
                .filter(pos -> pos.getY() >= ctx.playerFeet().getY())
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .min(Comparator.comparingDouble(ctx.playerFeet().above()::distSqr));
        baritone.getInputOverrideHandler().clearAllKeys();
        if (shaft.isPresent() && ctx.player().onGround()) {
            BlockPos pos = shaft.get();
            BlockState state = baritone.bsi.get0(pos);
            if (!MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent() && isSafeToCancel) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        PathingCommand command = updateGoal();
        if (command == null) {
            // 无已知目标：不取消——首次 rescan 是异步的（executor 线程扫区块），
            // 首 tick 立即 cancel 会在 rescan 完成前杀死进程：dev 超平坦扫描快侥幸
            // 通过，生产真实世界扫描慢必现「mine 完全不动」。用 REQUEST_PAUSE 保持
            // 进程等待扫描结果；真的找不到由 rescan 自行 cancel + 反馈。
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return command;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = settings().legitMine.value;
        List<BlockPos> locs = knownOreLocations;
        if (locs.isEmpty()) {
            // 无已知矿点时先扫掉落物：mine 应能捡面前的匹配掉落物
            // （原版首 tick knownOreLocations 为空直接取消，掉落物永远没机会进目标）
            List<BlockPos> dropped = droppedItemsScan();
            if (!dropped.isEmpty()) {
                // 直接采用实时扫描结果（掉落物已过 filter 匹配）；每 tick 重扫
                // （原版语义）：捡走/消失后目标自然失效，不会卡在旧位置
                knownOreLocations = dropped;
                locs = dropped;
            }
        }
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> dropped = droppedItemsScan();
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, settings().mineMaxOreLocationsCount.value, blacklist, dropped);
            if (locs2.isEmpty()) {
                // 全部目标失效（掉落物被捡走/方块已挖）：交 rescan 权威判定
                // （扫空 + 区块已加载 → 取消反馈），不生成空 goal 挂住任务
                return null;
            }
            // can't reassign locs, gotta make a new var locs2, because we use it in a lambda right here, and variables you use in a lambda must be effectively final
            // 掉落物位置：coalesce 会生成「站进空气格」的不可达目标（掉落物在空气里）。
            // 用 GoalNear(半径 0) 精确站到掉落物所在格——MC 拾取要求玩家与物品 AABB 相交，
            // 半径 1 站相邻格够不到（物品碰撞箱仅 0.25 格）；矿点仍走 coalesce。
            Goal goal = new GoalComposite(locs2.stream().map(loc ->
                    dropped.contains(loc) ? new GoalNear(loc, 0) : coalesce(loc, locs2, context)
            ).toArray(Goal[]::new));
            knownOreLocations = locs2;
            // 用 SET_GOAL_AND_PATH（与 CustomGoalProcess 一致）：REVALIDATE 的 postTick
            // 每 tick 无条件重设路径，movement 完成瞬间就重启计算，任何一次失败都会进入
            // 「失败→重算」循环导致假人原地不动；SET_GOAL_AND_PATH 只在 preTick 处理，
            // current 存在时不打断，与已验证的 goTo 链路完全一致。
            return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
        }
        // 找不到目标（无矿点且无匹配掉落物）：
        // mockplayer 移除 exploreForBlocks/legitMine 的 GoalRunAway 探索乱跑，
        // 直接取消任务（onTick 收到 null → cancel），由 mod 层决定反馈。
        return null;
    }

    private void rescan(List<BlockPos> already, CalculationContext context) {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return;
        }
        if (settings().legitMine.value) {
            return;
        }
        List<BlockPos> dropped = droppedItemsScan();
        List<BlockPos> locs = searchWorld(context, filter, settings().mineMaxOreLocationsCount.value, already, blacklist, dropped);
        locs.addAll(dropped);
        // 竞态防护：rescan 是异步的（executor 线程），执行期间用户可能已切换任务
        // （再次 mine 别的方块 / pathstop / 新命令）——this.filter 已被替换成新对象时，
        // 本 rescan 的「扫空/扫到」结论对新任务无效：绝不 cancel、绝不覆盖 knownOreLocations。
        // （回归实测：mine diamond_ore 的旧 rescan 扫空后 cancel() 把刚启动的 mine dirt
        //   任务杀死 → 假人完全不动；生产真实世界扫描慢，旧线程存活久，竞态必现）
        if (this.filter != filter) {
            return;
        }
        // mockplayer 不做 exploreForBlocks 探索乱跑（updateGoal 已移除 GoalRunAway）：
        // 扫空即权威取消（不依赖原版 explore=true 时的「探索等待」，否则无目标任务永不结束）。
        if (locs.isEmpty()) {
            // 区块未加载时扫描结果空 ≠ 世界上没有目标：假人刚进世界/刚传送时
            // 周围区块可能还没同步完，此时取消会让 mine 在区块就绪前死掉。
            // 脚下 chunk 已加载仍扫不到 → 真没有，反馈 + 取消。
            if (context.bsi.worldContainsLoadedChunk(
                    ctx.playerFeet().getX(), ctx.playerFeet().getZ())) {
                logDirect(Component.translatableEscape("baritone.log.mine.no_locations", filter));
                if (settings().notificationOnMineFail.value) {
                    logNotification(Component.translatableEscape("baritone.log.mine.no_locations", filter), true);
                }
                cancel();
            }
            return;
        }
        knownOreLocations = locs;
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public List<BlockPos> droppedItemsScan() {
        // 实时扫描（原版语义，不做缓存）：掉落物被捡走/消失后目标立即失效，
        // 缓存的「位置锁定」会让任务在捡完后永久挂住（生产实测新 bug）
        return scanDroppedItems();
    }

    private List<BlockPos> scanDroppedItems() {
        if (!settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                if (filter.has(ei.getItem())) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        ret.addAll(anticipatedDrops.keySet());
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.baritone.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        ctx.baritone.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped);

            if (!untracked.isEmpty() || (ctx.baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            locs.addAll(BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getBaritone().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            )); // maxSearchRadius is NOT sq
        }

        locs.addAll(alreadyKnown);

        return prune(ctx, locs, filter, max, blacklist, dropped);
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        dropped.removeIf(drop -> {
            for (BlockPos pos : locs2) {
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) { // TODO maybe drop also has to be supported? no lava below?
                    return true;
                }
            }
            return false;
        });
        List<BlockPos> locs = locs2
                .stream()
                .distinct()

                // remove any that are within loaded chunks that aren't actually what we want
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                .filter(pos -> {
                        if (ctx.baritone.settings().allowOnlyExposedOres.value) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                    .filter(pos -> pos.getY() >= ctx.baritone.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                  .filter(pos -> pos.getY() <= ctx.baritone.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        if (locs.size() > max) {
            return locs.subList(0, max);
        }
        return locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
            int radius = ctx.baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
        if (filter != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone));
        }
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect(Component.translatableEscape("baritone.log.mine.allow_break_off"));
                return null;
            }
            return f;
        }
        return filter;
    }
}
