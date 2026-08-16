package com.mockplayer.session;

import com.mockplayer.api.action.BotActions;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * BotActions 实现：持续状态写入假人 LocalPlayer.input（每 tick 由 BotImpl.tick 应用），
 * 一次性动作走假人 MultiPlayerGameMode（复用原版客户端逻辑，服务端反作弊合规）。
 */
public class BotActionsImpl implements BotActions {

    private final BotImpl bot;

    private float forward;
    private float strafe;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    /** 下马 shift 保持 tick 数：模拟原版「按住 Shift 下马，再松开」，到期自动复位 false。 */
    private int dismountShiftTicks;
    private Entity sustainedAttackTarget;
    private Entity sustainedUseTarget;
    /** 长按左键/右键的射线持续状态（沿视线，stopSustained 停止）。 */
    private boolean sustainedAttackLook;
    private boolean sustainedUseLook;
    /** 连点左键/右键开关（类似疾跑的开关状态：不随 GUI 关闭停止，stop() 停止）。 */
    private boolean rapidAttackLook;
    private boolean rapidUseLook;
    /** 连点右键 20 tick 计数（1 秒一次）。 */
    private int rapidUseTicks;
    private BlockPos miningPos;
    /** 原版 handleKeybinds 输入节流（照抄 Minecraft.missTime / rightClickDelay 语义）。 */
    private int missTime;
    private int rightClickDelay;
    /** 长按左键按住状态（点击沿触发 startAttack，按住期间由 continueAttack 挖方块）。 */
    private boolean sustainedAttackHeld;

    public BotActionsImpl(BotImpl bot) {
        this.bot = bot;
    }

    @Override
    public BotActions setForward(float value) {
        this.forward = value;
        return this;
    }

    @Override
    public BotActions setStrafe(float value) {
        this.strafe = value;
        return this;
    }

    @Override
    public BotActions setSneak(boolean sneaking) {
        this.sneaking = sneaking;
        this.bot.fireOnSneakToggle(sneaking);
        return this;
    }

    @Override
    public BotActions setSprint(boolean sprinting) {
        this.sprinting = sprinting;
        this.bot.fireOnSprintToggle(sprinting);
        return this;
    }

    @Override
    public BotActions look(float yaw, float pitch) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.setYRot(yaw % 360.0F);
            player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        }
        return this;
    }

    @Override
    public BotActions lookAt(Vec3 position) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, position);
        }
        return this;
    }

    @Override
    public BotActions lookAt(Entity entity) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null && entity != null) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
        }
        return this;
    }

    @Override
    public BotActions turn(float yaw, float pitch) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player != null) {
            player.setYRot(player.getYRot() + yaw);
            player.setXRot(Mth.clamp(player.getXRot() + pitch, -90.0F, 90.0F));
        }
        return this;
    }

    @Override
    public BotActions jump() {
        this.jumping = true;
        return this;
    }

    @Override
    public BotActions holdAttack(Entity target) {
        this.sustainedAttackTarget = target;
        return this;
    }

    @Override
    public BotActions holdUse(Entity target) {
        this.sustainedUseTarget = target;
        return this;
    }

    @Override
    public BotActions stopSustained() {
        this.sustainedAttackTarget = null;
        this.sustainedUseTarget = null;
        this.sustainedAttackLook = false;
        this.sustainedUseLook = false;
        this.sustainedAttackHeld = false;
        this.missTime = 0;
        this.rightClickDelay = 0;
        this.stopMining();
        // 原版松开右键：正在使用物品（举盾/拉弓/吃东西）→ releaseUse
        LocalPlayer usingPlayer = this.bot.getLocalPlayer();
        if (usingPlayer != null && usingPlayer.isUsingItem()) {
            this.releaseUse();
        }
        return this;
    }

    @Override
    public BotActions stop() {
        this.forward = 0.0F;
        this.strafe = 0.0F;
        this.sneaking = false;
        this.sprinting = false;
        this.jumping = false;
        this.dismountShiftTicks = 0;
        this.sustainedAttackTarget = null;
        this.sustainedUseTarget = null;
        this.sustainedAttackLook = false;
        this.sustainedUseLook = false;
        this.rapidAttackLook = false;
        this.rapidUseLook = false;
        this.rapidUseTicks = 0;
        this.sustainedAttackHeld = false;
        this.missTime = 0;
        this.rightClickDelay = 0;
        this.stopMining();
        return this;
    }

    @Override
    public BotActions setRapidAttack(boolean enabled) {
        this.rapidAttackLook = enabled;
        return this;
    }

    @Override
    public BotActions setRapidUse(boolean enabled) {
        this.rapidUseLook = enabled;
        if (!enabled) {
            this.rapidUseTicks = 0;
        }
        return this;
    }

    @Override
    public boolean isSneaking() {
        return this.sneaking || this.dismountShiftTicks > 0;
    }

    @Override
    public boolean isSprinting() {
        return this.sprinting;
    }

    @Override
    public boolean isJumping() {
        return this.jumping;
    }

    @Override
    public boolean isMining() {
        return this.miningPos != null;
    }

    @Override
    public boolean isSustainedAttacking() {
        return this.sustainedAttackTarget != null || this.sustainedAttackLook;
    }

    @Override
    public boolean isSustainedUsing() {
        return this.sustainedUseTarget != null || this.sustainedUseLook;
    }

    @Override
    public boolean isRapidAttacking() {
        return this.rapidAttackLook;
    }

    @Override
    public boolean isRapidUsing() {
        return this.rapidUseLook;
    }

    /** 取消持续挖掘：清目标 + 发 ABORT_DESTROY_BLOCK（原版松开左键等价）。 */
    private void stopMining() {
        if (this.miningPos != null && this.bot.getGameMode() != null) {
            this.bot.getGameMode().stopDestroyBlock();
        }
        this.miningPos = null;
    }

    /** 内部：每 tick 应用持续输入 + 持续挖掘（BotImpl.tick 调用）。
     *  26.2 输入模型：移动（xxa/zza）由 LocalPlayer.applyInput 从 input.getMoveVector() 读——
     *  Bot 用抽象 moveVector（Vec2 左右/前后）驱动，不写 keyPresses 的移动按键位（解耦：setForward 是"前进"动作，
     *  不是"按 W"）。jump/shift/sprint 原版从 keyPresses 位读（applyInput 的 jump / isSprintingDown / 潜行 shift），
     *  Bot 抽象动作驱动这三个输入位。 */
    void applyInput() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 寻路互斥（2026-08-16）：Baritone 激活时 InputOverrideHandler 直写假人
        // input（moveVector/keyPresses，不替换 input 对象），移动/跳跃/潜行/疾跑
        // 由 Baritone 每 tick 计算；这里完全让位（含持续攻击/挖掘/长按连点，
        // 避免与 Baritone 的 CLICK_LEFT/CLICK_RIGHT 强制输入打架），
        // 只保留原版节流递减（missTime/rightClickDelay），一次性动作由命令
        // 显式调用不受影响。
        if (this.bot.isNavigating()) {
            this.tickVanillaInput();
            return;
        }
        // 原版输入节流：missTime / rightClickDelay 每 tick 递减（Minecraft.tick 等价）
        this.tickVanillaInput();
        // 移动：只用抽象 moveVector（x=左右=strafe, y=前后=forward），原版 applyInput 从它设 xxa/zza
        ((com.mockplayer.session.accessor.MockplayerClientInputAccessor) player.input)
                .mockplayer$setMoveVector(new Vec2(this.strafe, this.forward));
        // jump/shift/sprint：原版从 keyPresses 位读，Bot 抽象动作驱动输入位（不涉及移动按键）
        // shift 位 = 用户潜行状态 或 下马瞬间（dismount 置 2 tick，到期自动复位 false）
        boolean shift = this.sneaking || this.dismountShiftTicks > 0;
        if (this.dismountShiftTicks > 0) {
            this.dismountShiftTicks--;
        }
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, this.jumping, shift, this.sprinting);
        // 持续挖掘（原版按住左键：每 tick continueDestroyBlock）
        this.tickMining();
        // 持续攻击/使用：目标死亡/无效则自动停止
        if (this.sustainedAttackTarget != null) {
            if (this.sustainedAttackTarget.isAlive()) {
                this.attack(this.sustainedAttackTarget);
            } else {
                this.sustainedAttackTarget = null;
            }
        }
        if (this.sustainedUseTarget != null) {
            if (this.sustainedUseTarget.isAlive()) {
                this.use(this.sustainedUseTarget);
            } else {
                this.sustainedUseTarget = null;
            }
        }
        // 长按左键（原版按住左键驱动）：点击沿 startAttack，按住期间 continueAttack 挖方块；
        // 实体/空挥在按住时继续触发（持矛由 MINIMUM_ATTACK_CHARGE 自然控制戳刺节奏）
        if (this.sustainedAttackLook) {
            if (player.isUsingItem()) {
                // 原版：使用物品期间不响应左键（松开后重新按下才再触发）
                this.sustainedAttackHeld = false;
            } else {
                if (!this.sustainedAttackHeld) {
                    this.sustainedAttackHeld = true;
                    this.startAttackLikeVanilla();
                } else {
                    net.minecraft.world.phys.HitResult heldHit = this.pickLookTarget();
                    if (!(heldHit instanceof net.minecraft.world.phys.BlockHitResult)) {
                        this.startAttackLikeVanilla();
                    }
                }
                this.continueAttackLikeVanilla(true);
            }
        }
        // 长按右键（原版按住右键驱动）：未使用且节流到 0 时 startUseItem；
        // 使用中保持蓄力不重发（矛动能/进食/举盾都靠原版 tick 推进）
        if (this.sustainedUseLook) {
            if (player.isUsingItem()) {
                // 保持使用（矛蓄力/进食/举盾），松开由 stopSustained 发 RELEASE_USE_ITEM
            } else if (this.rightClickDelay == 0 && player.containerMenu == player.inventoryMenu) {
                // 容器开着时跳过（等价原版 screen != null 阻止 handleKeybinds，防每 4 tick 重开）
                this.startUseItemLikeVanilla();
            }
        }
        // 连点左键：主手蓄力满（attack strength 1.0）才攻击一次（原版攻击节奏）
        if (this.rapidAttackLook && player.getAttackStrengthScale(0.0F) >= 1.0F) {
            this.attack();
        }
        // 连点右键：每 20 tick（1 秒）使用一次
        if (this.rapidUseLook) {
            if (++this.rapidUseTicks >= 20) {
                this.rapidUseTicks = 0;
                this.use();
            }
        }
    }

    @Override
    public void attack(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null || this.bot.getGameMode() == null) {
            return;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON)) {
            // 矛：攻击 = 沿视线戳刺（STAB 包，服务端射线判定）；蓄力未满时原版 attack 也是空挥
            if (!player.cannotAttackWithItem(held, 0)) {
                this.bot.getGameMode().piercingAttack(
                        held.get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON));
                player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            this.bot.getGameMode().attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnAttackEntity(target);
    }

    @Override
    public void stab() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        net.minecraft.world.item.component.PiercingWeapon weapon =
                player.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (weapon != null) {
            // 复用原版 MultiPlayerGameMode.piercingAttack（MixinMultiPlayerGameMode 把方法内
            // this.minecraft.player 换成假人），与 mod 扩展的原版戳刺逻辑保持一致。
            // 原版方法硬编码主玩家，假人不能直接调（污染主玩家），Mixin 替换作用对象后即可复用。
            this.bot.getGameMode().piercingAttack(weapon);
            // 原版 Minecraft.startAttack 持矛分支：piercingAttack 后 swing（ServerboundSwingPacket →
            // 服务端广播 AnimatePacket，主客户端能看到假人挥矛动作）
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void use(Entity target) {
        this.interactResult(target);
    }

    /** 右键实体（交易/喂食/骑乘等），返回原版分发结果（use() 需判断 fallthrough）。 */
    private net.minecraft.world.InteractionResult interactResult(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null || this.bot.getGameMode() == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // 右键实体（村民交易/喂食/骑乘）：EntityHitResult 定位目标
        net.minecraft.world.phys.EntityHitResult hit = new net.minecraft.world.phys.EntityHitResult(target);
        net.minecraft.world.InteractionResult result =
                this.bot.getGameMode().interact(player, target, hit, InteractionHand.MAIN_HAND);
        // 与原版 Minecraft 右键分发一致：Success + swingSource==CLIENT → player.swing（本地动画 + ServerboundSwingPacket 广播）
        if (result instanceof net.minecraft.world.InteractionResult.Success success
                && success.swingSource() == net.minecraft.world.InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnInteractEntity(target);
        return result;
    }

    @Override
    public void attack() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 单点左键 = 原版 startAttack 一次 + 立即松开（实体挥击/持矛戳刺/方块打一下）
        net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
        this.startAttackLikeVanilla();
        if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && !player.level().getBlockState(blockHit.getBlockPos()).isAir()) {
            // 单点方块 = 打一下（事件语义与 attack(BlockPos) 一致）
            this.bot.fireOnBreakBlock(blockHit.getBlockPos());
        }
        this.continueAttackLikeVanilla(false);
    }

    @Override
    public void use() {
        if (this.bot.getLocalPlayer() == null) {
            return;
        }
        // 单点右键 = 原版 startUseItem 一次（实体 use(Entity) fallthrough / 方块 use(BlockPos, Direction) fallthrough / 空 use(hand)）
        this.startUseItemLikeVanilla();
    }

    @Override
    public BotActions holdAttack() {
        this.sustainedAttackLook = true;
        return this;
    }

    @Override
    public BotActions holdUse() {
        this.sustainedUseLook = true;
        return this;
    }

    /**
     * 原版等价射线（对齐 LocalPlayer.raycastHitResult / pick）：方块 clip +
     * 实体相交最近优先 + 作用距离过滤。返回 null 表示假人未就绪。
     */
    private net.minecraft.world.phys.HitResult pickLookTarget() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return null;
        }
        // 完全复用原版 LocalPlayer.raycastHitResult（含主手 AttackRange 组件、方块/实体
        // 最近优先与距离过滤）——不再自写射线，任何细微差异都会导致命中不一致
        return player.raycastHitResult(1.0F, player);
    }

    @Override
    public void attack(BlockPos pos) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || pos == null || this.bot.getGameMode() == null) {
            return;
        }
        // 原版等价距离判断：与 mine(BlockPos) 同口径（blockInteractionRange，生存约 4.5 格），
        // 超距不发包（服务端也会拒绝，客户端提前拦下避免无意义包）
        if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
            return;
        }
        // 26.2 无 attack(BlockPos) 的原版等价：用 startDestroyBlock + 立即 stopDestroyBlock 模拟一次左键点击
        this.bot.getGameMode().startDestroyBlock(pos, Direction.UP);
        this.bot.getGameMode().stopDestroyBlock();
        player.swing(InteractionHand.MAIN_HAND);
        this.bot.fireOnBreakBlock(pos);
    }

    @Override
    public void mine(BlockPos pos) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || pos == null || this.bot.getGameMode() == null) {
            return;
        }
        // 原版等价距离判断：复用 Player.isWithinBlockInteractionRange（blockInteractionRange，生存约 4.5 格），
        // 与原版 Minecraft.startAttack 的 hitResult 射线范围一致；不做视线射线（方块在背后也能按坐标挖，方便使用）
        if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
            return;
        }
        // 复用原版挖掘驱动（Minecraft.continueAttack）：
        // startDestroyBlock（按住第一帧，发 START_DESTROY_BLOCK）→ 之后由 tickMining 每 tick
        // continueDestroyBlock（原版按住期间每 tick 持续发 START + 本地裂纹）→ 服务端进度推进到 1.0
        // 破坏后假人 level 方块变 air → continueDestroyBlock 返回 false → stopDestroyBlock（松开）。
        // MultiPlayerGameMode 内部 this.minecraft.player/level 已被 Mixin @Redirect 换成假人（隔离）。
        this.miningPos = pos;
        this.bot.getGameMode().startDestroyBlock(pos, Direction.UP);
        player.swing(InteractionHand.MAIN_HAND);
    }

    /** 内部：每 tick 持续挖掘（BotImpl.tick 调用）——复用原版 continueAttack 逐行逻辑：
     *  continueDestroyBlock（持续挖）+ addBreakingBlockEffect（裂纹）+ swing（持续挥动），服务端挖掉自动松开。 */
    void tickMining() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.miningPos == null || this.bot.getGameMode() == null) {
            return;
        }
        BlockPos pos = this.miningPos;
        // 原版等价：玩家移动/目标离开交互范围后，continueAttack 的 hitResult 不再指向该方块 → 松开左键
        if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
            this.stopMining();
            return;
        }
        // 原版 Minecraft.continueAttack：continueDestroyBlock → addBreakingBlockEffect（裂纹）→ swing（挥动）
        if (this.bot.getGameMode().continueDestroyBlock(pos, Direction.UP)) {
            if (player.level() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
                cl.addBreakingBlockEffect(pos, Direction.UP);
            }
            player.swing(InteractionHand.MAIN_HAND);
        } else {
            // 方块已被挖掉（假人 level 该位置变 air → continueDestroyBlock 返回 false）：松开 + 触发事件
            this.bot.getGameMode().stopDestroyBlock();
            this.miningPos = null;
            this.bot.fireOnBreakBlock(pos);
        }
    }

    @Override
    public void use(InteractionHand hand) {
        this.useItemResult(hand);
    }

    /** 使用手中物品（吃/拉弓/扔/喝药水/矛蓄力等），返回原版分发结果（startUseItem 需判断 Success）。 */
    private net.minecraft.world.InteractionResult useItemResult(InteractionHand hand) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // 原版 Minecraft.java:1743-1745：useItem 返回 Success + swingSource==CLIENT → swing（吃食物/投掷物/饮用药水等）
        InteractionResult result = this.bot.getGameMode().useItem(player, hand);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(hand);
        }
        this.bot.fireOnUseItem(hand, player.getItemInHand(hand));
        return result;
    }

    @Override
    public void releaseUse() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        // 复用原版 MultiPlayerGameMode.releaseUsingItem：发 RELEASE_USE_ITEM 包 + player.releaseUsingItem
        // （服务端触发弓放箭/投掷物抛出/盾牌解除格挡/食物提前取消）
        this.bot.getGameMode().releaseUsingItem(player);
    }

    @Override
    public void use(BlockPos pos, Direction side) {
        this.useItemOnResult(pos, side);
    }

    /** 右键交互方块（开箱/点门/放方块前的位置），返回原版分发结果（startUseItem 需判断 Success/Fail）。 */
    private net.minecraft.world.InteractionResult useItemOnResult(BlockPos pos, Direction side) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || pos == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // 26.2：useItemOn(LocalPlayer, InteractionHand, BlockHitResult)
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), side != null ? side : Direction.UP, pos, false);
        InteractionResult result = this.bot.getGameMode().useItemOn(player, InteractionHand.MAIN_HAND, hit);
        // 与原版 Minecraft 右键分发一致：Success + swingSource==CLIENT → player.swing（本地动画 + ServerboundSwingPacket 广播）
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        this.bot.fireOnInteractBlock(pos, side != null ? side : Direction.UP);
        return result;
    }

    // ===== 原版等价输入驱动（照抄 Minecraft.handleKeybinds 的左/右键链路，只换作用对象为假人） =====
    // 所有 look 路径（单点/长按/连点）都走这里：持矛戳刺、动能蓄力、方块交互 fallthrough、
    // 副手举盾等特殊物品行为由原版逻辑统一处理，不再逐处手动适配。

    /** 每 tick 推进原版输入节流（Minecraft.tick 的 missTime/rightClickDelay 递减）。 */
    private void tickVanillaInput() {
        if (this.missTime > 0) {
            this.missTime--;
        }
        if (this.rightClickDelay > 0) {
            this.rightClickDelay--;
        }
    }

    /**
     * 原版 Minecraft.startAttack 等价：持矛 → piercingAttack（STAB 包）；实体 → gameMode.attack；
     * 方块 → startDestroyBlock；空 → 挥空 + missTime=10 + 蓄力复位。
     * 返回是否瞬间破坏（创造模式一击可碎）。
     */
    private boolean startAttackLikeVanilla() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return false;
        }
        if (this.missTime > 0) {
            return false;
        }
        net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
        if (hit == null) {
            // 原版：null hitResult 不应发生，按 miss 处理
            if (this.bot.getGameMode().hasMissTime()) {
                this.missTime = 10;
            }
            return false;
        }
        if (player.isHandsBusy()) {
            return false;
        }
        // 假人不进旁观模式：原版 isSpectator（spectate）分支省略，等价非旁观
        net.minecraft.world.item.ItemStack heldItem = player.getMainHandItem();
        if (!heldItem.isItemEnabled(player.level().enabledFeatures())) {
            return false;
        }
        if (player.cannotAttackWithItem(heldItem, 0)) {
            return false;
        }
        net.minecraft.world.item.component.PiercingWeapon piercingWeapon =
                heldItem.get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (piercingWeapon != null) {
            // 持矛：STAB 包 + 客户端音效/状态（服务端射线判定伤害），不依赖射线命中
            this.bot.getGameMode().piercingAttack(piercingWeapon);
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        boolean endAttack = false;
        switch (hit.getType()) {
            case ENTITY -> {
                net.minecraft.world.phys.EntityHitResult entityHit = (net.minecraft.world.phys.EntityHitResult) hit;
                net.minecraft.world.item.component.AttackRange customRange =
                        heldItem.get(net.minecraft.core.component.DataComponents.ATTACK_RANGE);
                if (customRange == null || customRange.isInRange(player, hit.getLocation())) {
                    this.bot.getGameMode().attack(player, entityHit.getEntity());
                    this.bot.fireOnAttackEntity(entityHit.getEntity());
                }
            }
            case BLOCK -> {
                net.minecraft.world.phys.BlockHitResult blockHit = (net.minecraft.world.phys.BlockHitResult) hit;
                net.minecraft.core.BlockPos pos = blockHit.getBlockPos();
                if (!player.level().getBlockState(pos).isAir()) {
                    this.bot.getGameMode().startDestroyBlock(pos, blockHit.getDirection());
                    if (player.level().getBlockState(pos).isAir()) {
                        endAttack = true;
                    }
                }
            }
            case MISS -> {
                if (this.bot.getGameMode().hasMissTime()) {
                    this.missTime = 10;
                }
                player.resetAttackStrengthTicker();
            }
            default -> { }
        }
        player.swing(InteractionHand.MAIN_HAND);
        return endAttack;
    }

    /**
     * 原版 Minecraft.continueAttack 等价：按住左键时持续挖掘（continueDestroyBlock + 裂纹 + 挥动），
     * 松开/目标变空气/持矛时停止或跳过。
     */
    private void continueAttackLikeVanilla(boolean down) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        if (!down) {
            this.missTime = 0;
        }
        if (this.missTime <= 0 && !player.isUsingItem()) {
            net.minecraft.world.item.ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON)) {
                net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
                if (down && hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    net.minecraft.world.phys.BlockHitResult blockHit = (net.minecraft.world.phys.BlockHitResult) hit;
                    net.minecraft.core.BlockPos pos = blockHit.getBlockPos();
                    if (!player.level().getBlockState(pos).isAir()) {
                        if (this.bot.getGameMode().continueDestroyBlock(pos, blockHit.getDirection())) {
                            if (player.level() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
                                cl.addBreakingBlockEffect(pos, blockHit.getDirection());
                            }
                            player.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                } else {
                    this.bot.getGameMode().stopDestroyBlock();
                }
            }
        }
    }

    /**
     * 原版 Minecraft.startUseItem 等价：实体 → interact（失败 fallthrough 到 useItem）；
     * 方块 → useItemOn（Success/Fail 返回，PASS fallthrough 到 useItem）；空 → useItem。
     * 主手优先、副手 fallback（如副手盾举盾），持矛右键 = 动能蓄力（CONSUME）。
     */
    private void startUseItemLikeVanilla() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null) {
            return;
        }
        if (this.bot.getGameMode().isDestroying()) {
            return;
        }
        this.rightClickDelay = 4;
        if (player.isHandsBusy()) {
            return;
        }
        net.minecraft.world.phys.HitResult hit = this.pickLookTarget();
        for (InteractionHand hand : InteractionHand.values()) {
            net.minecraft.world.item.ItemStack heldItem = player.getItemInHand(hand);
            if (!heldItem.isItemEnabled(player.level().enabledFeatures())) {
                return;
            }
            if (hit != null) {
                switch (hit.getType()) {
                    case ENTITY -> {
                        net.minecraft.world.phys.EntityHitResult entityHit = (net.minecraft.world.phys.EntityHitResult) hit;
                        net.minecraft.world.entity.Entity entity = entityHit.getEntity();
                        if (!player.level().getWorldBorder().isWithinBounds(entity.blockPosition())) {
                            return;
                        }
                        if (player.isWithinEntityInteractionRange(entity, 0.0)) {
                            InteractionResult interactResult =
                                    this.bot.getGameMode().interact(player, entity, entityHit, hand);
                            this.bot.fireOnInteractEntity(entity);
                            if (interactResult instanceof InteractionResult.Success success) {
                                if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                                    player.swing(hand);
                                }
                                return;
                            }
                        }
                    }
                    case BLOCK -> {
                        net.minecraft.world.phys.BlockHitResult blockHit = (net.minecraft.world.phys.BlockHitResult) hit;
                        InteractionResult useResult =
                                this.bot.getGameMode().useItemOn(player, hand, blockHit);
                        this.bot.fireOnInteractBlock(blockHit.getBlockPos(), blockHit.getDirection());
                        if (useResult instanceof InteractionResult.Success success) {
                            if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                                player.swing(hand);
                            }
                            return;
                        }
                        if (useResult instanceof InteractionResult.Fail) {
                            return;
                        }
                    }
                    default -> { }
                }
            }
            if (!heldItem.isEmpty() && this.bot.getGameMode().useItem(player, hand) instanceof InteractionResult.Success success) {
                if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                    player.swing(hand);
                }
                this.bot.fireOnUseItem(hand, heldItem);
                return;
            }
        }
    }

    @Override
    public void place(BlockPos pos, Direction side) {
        // 点击 pos 的 side 面，实际放置位置由服务端 BlockPlaceContext 决定
        // （可替换方块 → pos 本身；实心方块 → pos.relative(side)）；朝向由外部调用者负责
        this.use(pos, side);
        this.bot.fireOnPlaceBlock(pos);
    }

    @Override
    public void placeAt(BlockPos target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || target == null || this.bot.getGameMode() == null) {
            return;
        }
        Level level = player.level();
        // 找支撑块：6 方向中不可替换（canBeReplaced=false，如实心方块）且离假人眼睛最近的相邻方块；
        // 点击它时 BlockPlaceContext.replaceClicked=false → 放置点 = 支撑块.relative(反方向) = target
        Vec3 eye = player.getEyePosition();
        BlockPos support = null;
        Direction supportSide = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.relative(dir);
            if (level.getBlockState(neighbor).canBeReplaced()) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(neighbor));
            if (d < bestDist) {
                bestDist = d;
                support = neighbor;
                supportSide = dir.getOpposite();
            }
        }
        if (support == null || supportSide == null) {
            return; // 无支撑块，不放置
        }
        this.use(support, supportSide);
        this.bot.fireOnPlaceBlock(target);
    }

    @Override
    public void drop() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        player.drop(false);
        this.bot.fireOnDropItem(player.getMainHandItem());
    }

    @Override
    public void drop(int slot, boolean dropAll) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 只能丢快捷栏当前选中槽（原版 Q/Ctrl+Q）；slot 越界钳制到 0-8
        int selected = Mth.clamp(slot, 0, Inventory.getSelectionSize() - 1);
        player.getInventory().setSelectedSlot(selected);
        player.drop(dropAll);
        this.bot.fireOnDropItem(player.getMainHandItem());
    }

    @Override
    public void swapHands() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN));
        this.bot.fireOnSwapHands();
    }

    @Override
    public BotActions setSelectedSlot(int slot) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return this;
        }
        int selected = Mth.clamp(slot, 0, net.minecraft.world.entity.player.Inventory.getSelectionSize() - 1);
        player.getInventory().setSelectedSlot(selected);
        // 原版快捷栏切换：本地更新后，向当前 LocalPlayer.connection 发包；这里必须是 bot connection。
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selected));
        this.bot.fireOnHeldSlotChanged(selected);
        return this;
    }

    @Override
    public void mount(boolean onlyRideables) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || this.bot.getLevel() == null) {
            return;
        }
        List<Entity> entities;
        if (onlyRideables) {
            entities = this.bot.getLevel().getEntities(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D),
                    e -> e instanceof Minecart || e instanceof Boat || e instanceof AbstractHorse);
        } else {
            entities = this.bot.getLevel().getEntities(player, player.getBoundingBox().inflate(3.0D, 1.0D, 3.0D));
        }
        Entity closest = entities.stream()
                .filter(e -> e != player && e.isAlive() && e.getVehicle() != player)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);
        if (closest == null) {
            return;
        }
        this.mount(closest);
    }

    @Override
    public void mount(Entity target) {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null || this.bot.getGameMode() == null || target == null) {
            return;
        }
        // 客户端骑乘：右键坐骑走 ServerboundInteractPacket，服务端处理上马
        this.bot.getGameMode().interact(player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
    }

    @Override
    public void dismount() {
        LocalPlayer player = this.bot.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 原版等价下马（2026-08-08 修复，对照 26.2 反编译源码）：
        // 真玩家按 Shift 下马的完整链路是：
        //   客户端 input.shift=true → LocalPlayer.tick 检测输入变化发 ServerboundPlayerInputPacket
        //   → 服务端 handlePlayerInput 置 shiftKeyDown
        //   → ServerPlayer.rideTick() 检测 wantsToStopRiding()（= isShiftKeyDown）→ stopRiding()
        //   → 服务端广播 SetPassengers，客户端本地脱离。
        // 实现：置 dismountShiftTicks=10，applyInput 写 keyPresses.shift=true 并保持 10 tick，
        // 到期自动写回 false（LocalPlayer.tick 检测变化再发复位包）——等价于「按住 Shift 再松开」。
        // 保持时间不能太短：true 和 false 复位包若被服务端同一 tick 处理，rideTick 检查时
        // shift 已是 false 会漏掉下马（间歇性「下不来」的竞态），10 tick = 0.5 秒足够稳定。
        // 注意不能只发一次 shift=true：BotActionsImpl 输入驱动从不主动产生 shift=false 变化，
        // 服务端会永久潜行，导致后续右键箱子/门被抑制（isSecondaryUseActive）。
        // 之前只调 removeVehicle() 只改本地引用不发包，服务端不知道下马，矿车/船/马都下不来。
        this.dismountShiftTicks = 10;
        // 本地立即脱离（无头客户端不等渲染层 SetPassengers 回包）：
        // 下一个动作可能立刻执行（如打开容器），骑乘状态会挡住交互，所以同步清本地引用。
        // 服务端下马仍由上面的 shift 输入包驱动，二者不冲突。
        player.removeVehicle();
    }

    // ===== GUI 操作直接发包（不走 Screen 按钮，包路由到假人 connection，零主玩家污染） =====

    /** 假人 connection 发包（FakeLocalPlayer.connection = 假人 ClientPacketListener → 假人 TCP） */
    private net.minecraft.client.multiplayer.ClientPacketListener conn() {
        LocalPlayer player = this.bot.getLocalPlayer();
        return player != null ? player.connection : null;
    }

    @Override
    public void chat(String message) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        // 原版等价过滤：StringUtil.filterText 移除 §(167) 等非法聊天字符，
        // 服务端 tryHandleChat 对含 § 的消息直接 disconnect(illegal_characters) 踢人。
        String filtered = net.minecraft.util.StringUtil.filterText(message);
        if (filtered.isEmpty()) {
            return;
        }
        if (filtered.startsWith("/")) {
            this.sendCommand(filtered.substring(1));
            return;
        }
        // offline 服务器不要求签名（signature=null）+ 空 lastSeen（原版无历史消息时等同）
        c.send(new net.minecraft.network.protocol.game.ServerboundChatPacket(
                filtered, java.time.Instant.now(), net.minecraft.util.Crypt.SaltSupplier.getLong(), null,
                new net.minecraft.network.chat.LastSeenMessages.Update(0, new java.util.BitSet(), (byte) 0)));
    }

    @Override
    public void sendCommand(String command) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        // 命令通道同样走 tryHandleChat 校验：含 § 会被踢，先过滤
        String filtered = net.minecraft.util.StringUtil.filterText(command);
        if (filtered.isEmpty()) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(filtered));
    }

    @Override
    public void wakeUp() {
        LocalPlayer player = this.bot.getLocalPlayer();
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (player == null || c == null) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
                player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
    }

    @Override
    public void respawn() {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    @Override
    public void editBook(int slot, java.util.List<String> pages, java.util.Optional<String> title) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundEditBookPacket(slot, pages, title));
    }

    @Override
    public void editSign(net.minecraft.core.BlockPos pos, boolean isFrontText, String[] lines) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null || lines == null || lines.length < 4) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundSignUpdatePacket(
                pos, isFrontText, lines[0], lines[1], lines[2], lines[3]));
    }

    @Override
    public void setBeacon(
            java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> primary,
            java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> secondary) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundSetBeaconPacket(primary, secondary));
    }

    @Override
    public void renameItem(String name) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundRenameItemPacket(name));
    }

    @Override
    public void pickItemFromBlock(net.minecraft.core.BlockPos pos, boolean includeData) {
        net.minecraft.client.multiplayer.ClientPacketListener c = this.conn();
        if (c == null) {
            return;
        }
        c.send(new net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket(pos, includeData));
    }
}
