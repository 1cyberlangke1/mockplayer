package com.mockplayer.session;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mockplayer.session.accessor.MockplayerClientPacketListenerAccessor;

/**
 * 假人的 play 阶段 listener：复用 ClientPacketListener 的全部 handler，
 * 但 handleLogin 走「假人分支」——只建假人自己的 world/player，绝不写主玩家全局。
 *
 * 无头小客户端：不渲染、不弹 UI，但网络/协议/物理全做，反作弊合规。
 */
public class FakePlayListener extends ClientPacketListener {

    /** 关联的假人会话 */
    private final FakeSession session;
    /** 假人自己的 gameMode（交互用） */
    private MultiPlayerGameMode fakeGameMode;
    /** 假人自己的 LocalPlayer（物理） */
    private LocalPlayer fakePlayer;
    /**
     * 登录后首包血量检查标志：覆盖「假人死亡后掉线、重连服务端仍判死」的竞态。
     * handleLogin 重置为 true；收到登录后第一个 handleSetHealth 包即检查一次（无论血量多少都清标志），
     * 若血量 <= 0 说明服务端认为假人已死，触发 respawn 重生。
     */
    private boolean checkDeathOnLogin;
    /** 上次血量（供 onHealthChanged/onDamage 事件推断） */
    private float lastHealth = 20.0F;
    /** 服务端 damage_event 先于 set_health 到达时暂存的 bot 伤害来源。 */
    private net.minecraft.world.damagesource.DamageSource pendingDamageSource;
    /** 暂存的伤害是否由实体造成，用于区分 onDamage 与 onEntityAttacked。 */
    private boolean pendingEntityAttack;

    public FakePlayListener(FakeSession session, Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
        super(minecraft, connection, cookie);
        this.session = session;
    }

    public FakeSession getSession() {
        return this.session;
    }

    public LocalPlayer getFakePlayer() {
        return this.fakePlayer;
    }

    public MultiPlayerGameMode getFakeGameMode() {
        return this.fakeGameMode;
    }

    /** 关联的 Bot（用于派发事件；BotManagerImpl 创建时已 setBot，理论上非 null） */
    private BotImpl botOrNull() {
        return this.session != null ? this.session.getBot() : null;
    }

    /** 事件派发辅助：bot 非 null 才触发（惰性由 BotEventBus 内部判断） */
    private void fire(java.util.function.Consumer<BotImpl> action) {
        BotImpl bot = botOrNull();
        if (bot != null) {
            action.accept(bot);
        }
    }

    /**
     * 假人分支 handleBlockUpdate：方块状态包。
     *
     * 显式 ensureRunningOnSameThread（主玩家渲染线程）再写假人 level——neoforge 版父类不走该检查，
     * 包在 Netty IO 线程直接处理，setServerVerifiedBlockState 内部 requestModelData（渲染）崩
     * `Cannot request ModelData refresh outside the owning thread`。双端统一走渲染线程。
     */
    @Override
    public void handleBlockUpdate(net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.client.multiplayer.ClientLevel level = self.mockplayer$getLevel();
        net.minecraft.core.BlockPos pos = packet.getPos();
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(
                net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()));
        int sectionIndex = chunk.getSectionIndex(pos.getY());
        long before = sectionSerializedBytes(chunk, sectionIndex);
        level.setServerVerifiedBlockState(pos, packet.getBlockState(), 19);
        long after = sectionSerializedBytes(chunk, sectionIndex);
        if (before != after) {
            fire(b -> b.fireOnSectionDataChanged(chunk.getPos(), sectionIndex, before, after));
        }
    }

    /**
     * 假人分支 handleChunkBlocksUpdate：批量方块变化包（爆炸/结构等）。
     * 先记 section 序列化尺寸前后差值，再走原版应用路径（写假人 level）。
     */
    @Override
    public void handleChunkBlocksUpdate(
            net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        final long[] before = {0};
        final net.minecraft.world.level.ChunkPos[] chunkPos = {null};
        final int[] sectionIndex = {0};
        packet.runUpdates((pos, state) -> {
            if (chunkPos[0] == null) {
                MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
                net.minecraft.client.multiplayer.ClientLevel level = self.mockplayer$getLevel();
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(
                        net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),
                        net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()));
                chunkPos[0] = chunk.getPos();
                sectionIndex[0] = chunk.getSectionIndex(pos.getY());
                before[0] = sectionSerializedBytes(chunk, sectionIndex[0]);
            }
        });
        super.handleChunkBlocksUpdate(packet);
        if (chunkPos[0] != null) {
            MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
            net.minecraft.world.level.chunk.LevelChunk chunk =
                    self.mockplayer$getLevel().getChunk(chunkPos[0].x(), chunkPos[0].z());
            long after = sectionSerializedBytes(chunk, sectionIndex[0]);
            if (before[0] != after) {
                fire(b -> b.fireOnSectionDataChanged(chunkPos[0], sectionIndex[0], before[0], after));
            }
        }
    }

    /** 某 section 的序列化存储字节（空 section 记 0）。 */
    private long sectionSerializedBytes(net.minecraft.world.level.chunk.LevelChunk chunk, int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return 0;
        }
        net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.hasOnlyAir()) {
            return 0;
        }
        return section.getStates().getSerializedSize();
    }

    /**
     * 假人收到区块缓存半径包：只更新假人自己的 level 与 serverChunkRadius。
     * 原版会写主玩家 {@code options.setServerRenderDistance}，假人绝不能碰（隔离铁律）。
     */
    @Override
    public void handleSetChunkCacheRadius(
            net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$setServerChunkRadius(packet.getRadius());
        self.mockplayer$getLevel().getChunkSource().updateViewRadius(packet.getRadius());
    }

    /**
     * 假人分支 handleLogin：只建假人自己的 world/player，不写主玩家全局。
     * 不调 minecraft.setLevel / setCameraEntity / startWaitingForNewLevel（不污染主玩家、不弹加载界面）。
     */
    /**
     * 假人分支 handleCustomPayload：mod payload 统一拦截（YSM 等 mod 兼容）。
     *
     * 背景：fabric 的 mod payload 分发是父类方法 HEAD @Inject(cancellable)，neoforge 在父类
     * 方法尾部 patch 调 handleModdedPayload；两者的 handler context 都硬编码主玩家
     * （Minecraft.getInstance().player），会把 YSM 这类「同步到客户端玩家」的数据应用到主玩家。
     * 本 override 不调 super 时双端分发链同时被绕过（虚分派到本方法后父类方法体不执行）。
     *
     * 规则（纯代码，不看 payload 内容，不逐个 mod 适配）：
     * - DiscardedPayload：同原版忽略
     * - BrandPayload（minecraft:brand）：写继承字段 serverBrand（不调 telemetry——
     *   telemetryManager 是主玩家侧实例）
     * - namespace == "minecraft" 的其他包：照原版（调 super，走原版处理 + 双端分发链）
     * - 配置 payloadInterceptEnabled=false：不拦截，调 super（兼容「某些 mod 的 payload
     *   假人需要真处理」的场景）
     * - 配置 payloadPassthroughNamespaces 含该 namespace：放行，调 super（逃生舱，
     *   接受 mod handler 应用到主玩家的代价，协议类兼容才用）
     * - 其余（全部 mod payload）：记录到 state（元信息 + 原始对象，零丢弃铁律），不调 super
     *
     * 副作用：fabric channel 注册协商在 listener 构造时已定，play 阶段拦截不影响连接建立；
     * 服务端若仍发 mod 包则被拦截记录 = 双保险。
     */
    @Override
    public void handleCustomPayload(net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet) {
        net.minecraft.network.protocol.common.custom.CustomPacketPayload payload = packet.payload();
        if (payload instanceof net.minecraft.network.protocol.common.custom.DiscardedPayload) {
            return;
        }
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        if (payload instanceof net.minecraft.network.protocol.common.custom.BrandPayload brand) {
            this.serverBrand = brand.brand();
            return;
        }
        String namespace = payload.type().id().getNamespace();
        com.mockplayer.config.ModConfig cfg = com.mockplayer.config.MockplayerConfig.get();
        boolean intercept = cfg.isPayloadInterceptEnabled()
                && !"minecraft".equals(namespace)
                && !cfg.getPayloadPassthroughNamespaces().contains(namespace);
        if (intercept) {
            // neoforge 平台扩展容器数据（如附魔成本走 advanced_container_set_data）：
            // 解析应用到假人菜单（等价原版语义），同时记录（零丢弃）；不调 super
            // （neoforge handler 的 context.player() 硬编码主玩家，会污染）
            com.mockplayer.platform.Services.PLATFORM.handlePlatformContainerPayload(
                    payload, this.fakePlayer != null ? this.fakePlayer.containerMenu : null);
            // 记录元信息 + 原始对象（网络层已解码），不调 super（跳过双端 mod 分发链）
            this.session.getState().recordReceivedModPayload(new com.mockplayer.api.ModPayloadInfo(
                    payload.type().id(),
                    namespace,
                    com.mockplayer.platform.ModNameCache.get(namespace),
                    this.minecraft.level != null ? this.minecraft.level.getGameTime() : 0L,
                    PayloadInspector.estimateSize(payload)), payload);
            return;
        }
        super.handleCustomPayload(packet);
    }

    @Override
    public void handleLogin(ClientboundLoginPacket packet) {
        // 显式转渲染线程：假人 level/player 必须在渲染线程创建，否则 neoforge ModelDataManager
        // 绑定 Netty 线程，后续渲染线程操作 level（如 setBlock 移除 BlockEntity → requestModelData）崩
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        // 会话可能已在登录完成前被删除：放弃创建 level/player（防孤儿 level 泄漏）
        if (this.session.isDisposed()) {
            this.connection.disconnect(
                    net.minecraft.network.chat.Component.translatable(
                            "disconnect.mockplayer.fake_player_removed"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;

        // 1. 假人自己的 gameMode（不写 mc.gameMode）
        this.fakeGameMode = new MultiPlayerGameMode(mc, this);

        // 2. 假人自己的 level（不调 mc.setLevel）
        var spawnInfo = packet.commonPlayerSpawnInfo();
        List<ResourceKey<Level>> levels = Lists.newArrayList(packet.levels());
        Collections.shuffle(levels);
        self.mockplayer$setLevels(Sets.newLinkedHashSet(levels));
        ResourceKey<Level> dimension = spawnInfo.dimension();
        Holder<DimensionType> dimensionType = spawnInfo.dimensionType();
        self.mockplayer$setServerChunkRadius(packet.chunkRadius());
        self.mockplayer$setServerSimulationDistance(packet.simulationDistance());
        boolean isDebug = spawnInfo.isDebug();
        boolean isFlat = spawnInfo.isFlat();
        int seaLevel = spawnInfo.seaLevel();
        ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(Difficulty.NORMAL, packet.hardcore(), isFlat);
        self.mockplayer$setLevelData(levelData);
        self.mockplayer$setLevel(new ClientLevel(
                this,
                levelData,
                dimension,
                dimensionType,
                self.mockplayer$getServerChunkRadius(),
                self.mockplayer$getServerSimulationDistance(),
                mc.levelExtractor,
                isDebug,
                spawnInfo.seed(),
                seaLevel
        ));
        com.mockplayer.session.FakeLevelRegistry.registerFakeLevel(self.mockplayer$getLevel());
        // 假人 chunk 缓存按配置默认半径（默认 2）：本地只保留更少区块，节约内存
        self.mockplayer$getLevel().getChunkSource().updateViewRadius(
                com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius());

        // 3. 假人自己的 player（不写 mc.player）——FakeLocalPlayer 强制物理发包
        this.fakePlayer = new FakeLocalPlayer(
                mc,
                self.mockplayer$getLevel(),
                this,
                new StatsCounter(),
                new ClientRecipeBook(),
                new net.minecraft.world.entity.player.Input(false, false, false, false, false, false, false),
                false,
                mc.computeChatAbilities()
        );
        this.fakePlayer.setYRot(-180.0F);
        this.fakePlayer.resetPos();
        this.fakePlayer.setId(packet.playerId());
        self.mockplayer$getLevel().addEntity(this.fakePlayer);
        // 标记未加载：等 chunk 包（handleLevelChunkWithLight）→ notifyPlayerLoaded 恢复物理 +
        // 发 ServerboundPlayerLoadedPacket 告知服务端已加载。
        // 原版 ClientPacketListener 登录后等 chunk 加载完成才 notifyPlayerLoaded——若直接 setClientLoaded(true)，
        // loaded 包永不发送 → 服务端 ServerPlayer.hasClientLoaded() 恒 false → 拒绝假人一切交互（useItemOn 等）。
        self.mockplayer$setClientLoaded(false);
        // 用 ClientInput（零输入），不用 KeyboardInput——KeyboardInput 读主玩家的按键，
        // 会导致假人跟着主玩家移动（挂机时假人应静止，只受重力/击退等环境影响）
        this.fakePlayer.input = new net.minecraft.client.player.ClientInput();
        this.fakeGameMode.adjustPlayer(this.fakePlayer);
        this.fakePlayer.setReducedDebugInfo(packet.reducedDebugInfo());
        this.fakePlayer.setShowDeathScreen(packet.showDeathScreen());
        this.fakePlayer.setDoLimitedCrafting(packet.doLimitedCrafting());
        this.fakePlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        this.fakePlayer.setPortalCooldown(spawnInfo.portalCooldown());
        // 假人自己的 gameType：只改假人 abilities + 记录字段，
        // 绝不用 setLocalMode——它内部硬编码 this.minecraft.player（会污染主玩家！）
        spawnInfo.gameType().updatePlayerAbilities(this.fakePlayer.getAbilities());
        ((com.mockplayer.session.accessor.MockplayerMultiPlayerGameModeAccessor) this.fakeGameMode)
                .mockplayer$setLocalPlayerMode(spawnInfo.gameType());
        ((com.mockplayer.session.accessor.MockplayerMultiPlayerGameModeAccessor) this.fakeGameMode)
                .mockplayer$setPreviousLocalPlayerMode(spawnInfo.previousGameType());
        // 同步假人自己的 gameType 到 FakeLocalPlayer（aiStep 判断 spectator 用，不读主玩家）
        ((com.mockplayer.session.FakeLocalPlayer) this.fakePlayer).setFakeGameType(spawnInfo.gameType());

        // 4. 存进 session（供 tick 驱动物理）
        this.session.setFakePlayer(this.fakePlayer);
        this.session.setPlayListener(this);
        // 5. 假人专属 Baritone 实例：绑定假人 player/gameMode（隔离铁律：交互走假人
        //    gameMode，不碰主玩家）；事件由 FakeSession.tick 手动喂（激进改造：
        //    不依赖 Baritone 全局 mixin，时序对齐官方 Minecraft.tick）
        com.mockplayer.baritone.api.IBaritone botBaritone = com.mockplayer.baritone.api.BaritoneAPI.getProvider()
                .createBaritone(mc, this.fakePlayer, this.fakeGameMode);
        this.session.setBaritone(botBaritone);
        // 服务器标识 → 缓存目录键（"singleplayer" 共享主世界缓存，host:port 独立）
        botBaritone.updateServerKey(this.session.getServerKey());
        // 世界加载事件（LookBehavior/ElytraProcess 状态初始化；WorldProvider 靠
        // detectAndHandleBrokenLoading 轮询自愈，不依赖此事件）
        botBaritone.getGameEventHandler().onWorldEvent(
                new com.mockplayer.baritone.api.event.events.WorldEvent(
                        self.mockplayer$getLevel(),
                        com.mockplayer.baritone.api.event.events.type.EventState.PRE));
        botBaritone.getGameEventHandler().onWorldEvent(
                new com.mockplayer.baritone.api.event.events.WorldEvent(
                        self.mockplayer$getLevel(),
                        com.mockplayer.baritone.api.event.events.type.EventState.POST));
        // 应用寻路配置（全局 + per-bot 覆盖 → 该实例 Settings）
        com.mockplayer.session.NavigateSupport.applyToSession(this.session);
        // 重置登录后首包血量检查标志（服务端若认为假人已死，登录后第一个 setHealth 包血量 <= 0）
        this.checkDeathOnLogin = true;
        // 标记已连接（消除 connected 与 PLAYING 的跨线程竞态，见 FakeSession.doConnectTcp 注释）
        this.session.markConnected();
        FakeSession.LOG.info("[{}] 假人进入 play 阶段，已创建独立 world/player", this.session.getName());
        // Bot 事件：登录完成、LocalPlayer 就绪
        fire(b -> b.fireOnPlayReady());
    }

    // ===== override 污染源 handler：假人收到的一切包处理到假人自己，绝不写主玩家全局 =====

    /**
     * 聊天：假人记录到自己的 state，不写主玩家聊天栏（防止双发）。
     */
    @Override
    public void handlePlayerChat(net.minecraft.network.protocol.game.ClientboundPlayerChatPacket packet) {
        // 签名聊天：完整记录原始包（签名/时间戳/聊天气泡类型）供 AI 读取；
        // 聊天文本优先取 unsignedContent（未签名展示文本），缺失时取签名 body 的 content。
        this.session.getState().recordPacket("handlePlayerChat", packet);
        net.minecraft.network.chat.Component text = packet.unsignedContent() != null
                ? packet.unsignedContent()
                : net.minecraft.network.chat.Component.literal(packet.body().content());
        this.session.getState().addChat(text);
        fire(b -> b.fireOnChat(text));
    }

    @Override
    public void handleSystemChat(net.minecraft.network.protocol.game.ClientboundSystemChatPacket packet) {
        this.session.getState().addChat(packet.content());
        fire(b -> b.fireOnChat(packet.content()));
    }

    @Override
    public void handleDisguisedChat(net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket packet) {
        this.session.getState().addChat(packet.message());
        fire(b -> b.fireOnChat(packet.message()));
    }

    /**
     * 血量/饥饿：写到假人自己的 player（不写主玩家 → 攻击假人主玩家不掉血）。
     */
    @Override
    public void handleSetHealth(net.minecraft.network.protocol.game.ClientboundSetHealthPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.hurtTo(packet.getHealth());
            this.fakePlayer.getFoodData().setFoodLevel(packet.getFood());
            this.fakePlayer.getFoodData().setSaturation(packet.getSaturation());
        }
        this.session.getState().setHealth(packet.getHealth());
        this.session.getState().setFoodLevel(packet.getFood());
        // Bot 事件只在血量真的变化时派发；登录首包通常也是 20，不应伪造一次变化事件。
        float oldHealth = this.lastHealth;
        float newHealth = packet.getHealth();
        if (Float.compare(oldHealth, newHealth) != 0) {
            fire(b -> b.fireOnHealthChanged(oldHealth, newHealth));
            // SetHealth 包没有 DamageSource，伤害量由 bot 自己的前后血量精确计算。
            if (newHealth < oldHealth) {
                float amount = oldHealth - newHealth;
                net.minecraft.world.damagesource.DamageSource source = this.pendingDamageSource;
                fire(b -> b.fireOnDamage(source, amount));
                if (this.pendingEntityAttack) {
                    fire(b -> b.fireOnEntityAttacked(source, amount));
                }
                this.pendingDamageSource = null;
                this.pendingEntityAttack = false;
            }
        }
        this.lastHealth = newHealth;

        // 登录后首包血量检查（只检查一次）：覆盖「死亡后掉线、重连服务端仍判死」的竞态。
        // 登录包不含血量，服务端会随登录后第一批包下发 setHealth(当前血量)——血量 <= 0 即服务端认为假人已死。
        // 无论血量多少都清标志（首包血量 > 0 = 服务端认为假人活着，无需再查），
        // 否则正常死亡时 setHealth(0) 会与 handlePlayerCombatKill 双 respawn。
        if (this.checkDeathOnLogin) {
            this.checkDeathOnLogin = false;
            if (packet.getHealth() <= 0.0F && this.fakePlayer != null) {
                // 发 PERFORM_RESPAWN，服务端回 respawn 包走已有 handleRespawn 链路（等价原版死亡重生）
                this.fakePlayer.respawn();
                FakeSession.LOG.warn("[{}] 登录后首包血量 <= 0，服务端认为假人已死，触发重生", this.session.getName());
            }
        }
    }

    /**
     * 伤害事件（含 DamageSource）：假人被实体攻击时触发 onEntityAttacked（onDamage 在 handleSetHealth 已触发）。
     */
    @Override
    public void handleDamageEvent(net.minecraft.network.protocol.game.ClientboundDamageEventPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        try {
            if (this.fakePlayer != null && packet.entityId() == this.fakePlayer.getId()) {
                net.minecraft.world.damagesource.DamageSource source = packet.getSource(this.fakePlayer.level());
                // 伤害包携带的 cause/direct id 才是“实体攻击”的权威标记；
                // 客户端暂时没同步攻击者实体时，source.getEntity() 可能为空，不能因此丢掉事件。
                this.pendingDamageSource = source;
                this.pendingEntityAttack = packet.sourceCauseId() >= 0 || packet.sourceDirectId() >= 0;
            }
        } catch (Exception e) {
            FakeSession.LOG.warn("[{}] handleDamageEvent 异常: {}", this.session.getName(), e.toString());
        }
        super.handleDamageEvent(packet);
    }

    /**
     * 经验：写到假人自己的 player。
     */
    @Override
    public void handleSetExperience(net.minecraft.network.protocol.game.ClientboundSetExperiencePacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.setExperienceValues(packet.getExperienceProgress(), packet.getTotalExperience(), packet.getExperienceLevel());
        }
        this.session.getState().setExperienceLevel(packet.getExperienceLevel());
    }

    /**
     * 能力（飞行等）：写到假人自己的 player。
     */
    @Override
    public void handlePlayerAbilities(net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket packet) {
        if (this.fakePlayer != null) {
            // 与原版完全一致（this.minecraft.player → this.fakePlayer）
            this.fakePlayer.getAbilities().flying = packet.isFlying();
            this.fakePlayer.getAbilities().instabuild = packet.canInstabuild();
            this.fakePlayer.getAbilities().invulnerable = packet.isInvulnerable();
            this.fakePlayer.getAbilities().mayfly = packet.canFly();
            this.fakePlayer.getAbilities().setFlyingSpeed(packet.getFlyingSpeed());
            this.fakePlayer.getAbilities().setWalkingSpeed(packet.getWalkingSpeed());
        }
    }

    /**
     * 游戏事件：全部数据拿全，写到假人状态（不碰主玩家 gameMode/gui）。
     * - 天气（rain/thunder）→ 假人 level
     * - CHANGE_GAME_MODE → 假人自己的 gameMode
     * - IMMEDIATE_RESPAWN / LIMITED_CRAFTING → 假人 player
     * - 音效/粒子 → 假人 level
     * - 消息类 / WIN / DEMO → 记录到 state
     */
    @Override
    public void handleGameEvent(net.minecraft.network.protocol.game.ClientboundGameEventPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.client.player.LocalPlayer player = this.fakePlayer;
        if (player == null) {
            return;
        }
        net.minecraft.network.protocol.game.ClientboundGameEventPacket.Type event = packet.getEvent();
        float paramFloat = packet.getParam();
        int param = net.minecraft.util.Mth.floor(paramFloat + 0.5F);
        if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE) {
            this.session.getState().addChat(net.minecraft.network.chat.Component.translatable("block.minecraft.spawn.not_valid"));
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING) {
            self.mockplayer$getLevel().setRainLevel(0.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING) {
            self.mockplayer$getLevel().setRainLevel(1.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            // 假人自己的 gameType：只改假人 abilities + 记录字段，不用 setLocalMode（会污染主玩家！）
            net.minecraft.world.level.GameType mode = net.minecraft.world.level.GameType.byId(param);
            mode.updatePlayerAbilities(this.fakePlayer.getAbilities());
            ((com.mockplayer.session.accessor.MockplayerMultiPlayerGameModeAccessor) this.fakeGameMode)
                    .mockplayer$setLocalPlayerMode(mode);
            // 同步假人自己的 gameType 到 FakeLocalPlayer
            ((com.mockplayer.session.FakeLocalPlayer) this.fakePlayer).setFakeGameType(mode);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.WIN_GAME) {
            // 假人无头不弹 WinScreen，记录到 state
            this.session.getState().recordWinGame();
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.DEMO_EVENT) {
            // 假人无头不弹演示 UI，记录到 state
            this.session.getState().recordDemoEvent(paramFloat);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND) {
            // 音效记录到假人 state（不播放到主玩家音箱，零污染）
            this.session.getState().recordSound("arrow_hit", player.getX(), player.getEyeY(), player.getZ());
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            self.mockplayer$getLevel().setRainLevel(paramFloat);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            self.mockplayer$getLevel().setThunderLevel(paramFloat);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.PUFFER_FISH_STING) {
            this.session.getState().recordSound("puffer_sting", player.getX(), player.getY(), player.getZ());
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT) {
            // 粒子记录到假人 state（不渲染到主玩家屏幕，零污染）
            this.session.getState().recordParticle("elder_guardian", player.getX(), player.getY(), player.getZ());
            if (param == 1) {
                this.session.getState().recordSound("elder_guardian_curse", player.getX(), player.getY(), player.getZ());
            }
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.IMMEDIATE_RESPAWN) {
            player.setShowDeathScreen(paramFloat == 0.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.LIMITED_CRAFTING) {
            player.setDoLimitedCrafting(paramFloat == 1.0F);
        } else if (event == net.minecraft.network.protocol.game.ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START) {
            // chunk 开始加载：恢复物理已由 handleLevelChunkWithLight override 处理（收到 chunk 即就绪）
        }
    }

    /**
     * 玩家列表更新：完整记录到假人 state（不写主玩家社交管理器/playerInfoMap），
     * 供程序化 AI 感知周围玩家。新条目记录在线，动作更新游戏模式/名字。
     */
    @Override
    public void handlePlayerInfoRemove(net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        try {
            MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
            for (java.util.UUID profileId : packet.profileIds()) {
                this.session.getState().removePlayerOnline(profileId);
                // 从假人自己的 playerInfoMap 移除（原版 super 会做同样的事，但还会调
                // minecraft.getPlayerSocialManager().removePlayer——那是主玩家的社交管理器，
                // 与 handlePlayerInfoUpdate 跳过 addPlayer 不对称，这里一并跳过，零污染）
                net.minecraft.client.multiplayer.PlayerInfo info = self.mockplayer$getPlayerInfoMap().remove(profileId);
                if (info != null) {
                    // 原版 super 同步维护 listedPlayers（tab 名单），假人保持状态完整
                    self.mockplayer$getListedPlayers().remove(info);
                    fire(b -> b.fireOnPlayerLeft(info.getProfile()));
                }
            }
        } catch (Exception e) {
            FakeSession.LOG.warn("[{}] handlePlayerInfoRemove 异常: {}", this.session.getName(), e.toString());
        }
    }

    @Override
    public void handlePlayerInfoUpdate(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket packet) {
        // 玩家列表更新：像原版一样填假人 playerInfoMap（否则 Bot.getOnlinePlayers() 读不到），
        // 同时完整记录到假人 state。跳过原版主玩家 playerSocialManager.addPlayer（不污染主玩家）。
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        java.util.Map<java.util.UUID, net.minecraft.client.multiplayer.PlayerInfo> infoMap = self.mockplayer$getPlayerInfoMap();
        for (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
            // 新玩家上线：填假人 playerInfoMap（enforcesSecureChat 父类 private，简化 true）+ 记录 state
            if (entry.profile() != null) {
                net.minecraft.client.multiplayer.PlayerInfo playerInfo =
                        new net.minecraft.client.multiplayer.PlayerInfo(entry.profile(), true);
                infoMap.putIfAbsent(entry.profileId(), playerInfo);
            }
            String name = entry.profile() != null ? entry.profile().name() : entry.profileId().toString();
            this.session.getState().recordPlayerOnline(entry.profileId(), name,
                    entry.latency(), entry.gameMode(), entry.listed());
            if (entry.profile() != null) {
                fire(b -> b.fireOnPlayerJoined(entry.profile()));
            }
        }
        for (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            // 已有玩家更新动作：像原版 applyPlayerInfoUpdate 一样更新假人 playerInfoMap
            // （否则 Bot.getOnlinePlayers() 读到的 latency/gameMode 永远是新上线时的旧值），
            // 只替换作用对象：不碰主玩家 player.onGameModeChanged / socialManager。
            net.minecraft.client.multiplayer.PlayerInfo info = infoMap.get(entry.profileId());
            if (info != null) {
                for (net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action action : packet.actions()) {
                    switch (action) {
                        case UPDATE_GAME_MODE -> {
                            // 原版还会对「自己」调 minecraft.player.onGameModeChanged（主玩家污染点）；
                            // 假人自己的游戏模式由 handleGameEvent CHANGE_GAME_MODE 独立链路处理，这里只更新 tab 信息
                            ((com.mockplayer.session.accessor.MockplayerPlayerInfoAccessor) info)
                                    .mockplayer$setGameMode(entry.gameMode());
                        }
                        case UPDATE_LISTED -> {
                            // 原版语义：listed = 加入 tab 渲染名单；假人无渲染但保持状态完整
                            if (entry.listed()) {
                                self.mockplayer$getListedPlayers().add(info);
                            } else {
                                self.mockplayer$getListedPlayers().remove(info);
                            }
                        }
                        case UPDATE_LATENCY -> ((com.mockplayer.session.accessor.MockplayerPlayerInfoAccessor) info)
                                .mockplayer$setLatency(entry.latency());
                        case UPDATE_DISPLAY_NAME -> info.setTabListDisplayName(entry.displayName());
                        case UPDATE_HAT -> info.setShowHat(entry.showHat());
                        case UPDATE_LIST_ORDER -> info.setTabListOrder(entry.listOrder());
                        case INITIALIZE_CHAT -> {
                            // 聊天会话校验原版调主玩家 services 签名验证；假人不校验聊天签名，跳过
                        }
                    }
                }
            }
            // 记录 state（供 AI 读取最新值）
            String name = entry.profile() != null ? entry.profile().name() : entry.profileId().toString();
            this.session.getState().recordPlayerOnline(entry.profileId(), name,
                    entry.latency(), entry.gameMode(), entry.listed());
        }
    }

    // ===== 位置/旋转：换假人引用，假人位置由服务端同步（解决下坠/横跳） =====

    /**
     * 服务端位置包 → 应用到假人自己（不写主玩家）。
     * 父类用 this.minecraft.player 会导致主玩家视角被拽到假人位置（横跳），这里换成 fakePlayer。
     */
    @Override
    public void handleMovePlayer(net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer == null) {
            // 防御：位置包应在 handleLogin 建 player 之后到达（服务端顺序保证），
            // 异常时序下静默丢弃，不 NPE 崩连接
            return;
        }
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.player.Player player = this.fakePlayer;
        if (!player.isPassenger()) {
            MockplayerClientPacketListenerAccessor.mockplayer$setValuesFromPositionPacket(packet.change(), packet.relatives(), player, false);
        }
        this.connection.send(new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(packet.id()));
        this.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), false, false));
        // 与原版父类一致：传送后清理假人 level 的方块状态预测（等价替代 minecraft.level → 假人 level）
        ((com.mockplayer.session.accessor.MockplayerClientLevelAccessor) self.mockplayer$getLevel())
                .mockplayer$getBlockStatePredictionHandler().onTeleport();
    }

    /**
     * 旋转包 → 应用到假人自己。
     */
    @Override
    public void handleRotatePlayer(net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer == null) {
            return;
        }
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.player.Player player = this.fakePlayer;
        Set<net.minecraft.world.entity.Relative> relatives = net.minecraft.world.entity.Relative.rotation(packet.relativeY(), packet.relativeX());
        net.minecraft.world.entity.PositionMoveRotation currentValues = net.minecraft.world.entity.PositionMoveRotation.of(player);
        net.minecraft.world.entity.PositionMoveRotation newValues = net.minecraft.world.entity.PositionMoveRotation.calculateAbsolute(
                currentValues, currentValues.withRotation(packet.yRot(), packet.xRot()), relatives
        );
        player.setYRot(newValues.yRot());
        player.setXRot(newValues.xRot());
        player.setOldRot();
        this.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), false, false));
    }

    /**
     * 快捷栏选择 → 应用到假人自己的背包。
     */
    @Override
    public void handleSetHeldSlot(net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket packet) {
        if (this.fakePlayer != null && net.minecraft.world.entity.player.Inventory.isHotbarSlot(packet.slot())) {
            this.fakePlayer.getInventory().setSelectedSlot(packet.slot());
            fire(b -> b.fireOnHeldSlotChanged(packet.slot()));
        }
    }

    /**
     * 容器内容 → 应用到假人自己的菜单/背包。
     */
    @Override
    public void handleContainerContent(net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket packet) {
        try {
            if (this.fakePlayer != null) {
                if (packet.containerId() == 0) {
                    this.fakePlayer.inventoryMenu.initializeContents(packet.stateId(), packet.items(), packet.carriedItem());
                } else if (packet.containerId() == this.fakePlayer.containerMenu.containerId) {
                    this.fakePlayer.containerMenu.initializeContents(packet.stateId(), packet.items(), packet.carriedItem());
                }
            }
        } catch (Exception e) {
            // 某些菜单（如 BeaconMenu）槽位/状态初始化可能抛——记录不崩连接（假人仍保持容器会话）
            FakeSession.LOG.warn("[{}] handleContainerContent 异常: {}", this.session.getName(), e.toString());
        }
    }

    /**
     * 容器槽位 → 应用到假人自己的背包。
     */
    @Override
    public void handleContainerSetSlot(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        try {
            if (this.fakePlayer != null) {
                net.minecraft.world.item.ItemStack itemStack = packet.getItem();
                int slot = packet.getSlot();
                if (packet.getContainerId() == 0) {
                    // 与原版一致：快捷栏新物品动画（假人无渲染动画，仅维护计数逻辑等价）
                    if (net.minecraft.world.inventory.InventoryMenu.isHotbarSlot(slot) && !itemStack.isEmpty()) {
                        net.minecraft.world.item.ItemStack lastItemStack = this.fakePlayer.inventoryMenu.getSlot(slot).getItem();
                        if (lastItemStack.isEmpty() || lastItemStack.getCount() < itemStack.getCount()) {
                            itemStack.setPopTime(5);
                        }
                    }
                    this.fakePlayer.inventoryMenu.setItem(slot, packet.getStateId(), itemStack);
                } else if (packet.getContainerId() == this.fakePlayer.containerMenu.containerId) {
                    this.fakePlayer.containerMenu.setItem(slot, packet.getStateId(), itemStack);
                }
                fire(b -> b.fireOnContainerSlotChanged(packet.getContainerId(), slot, itemStack));
            }
        } catch (Exception e) {
            FakeSession.LOG.warn("[{}] handleContainerSetSlot 异常: {}", this.session.getName(), e.toString());
        }
    }

    /**
     * 容器关闭 → 应用到假人自己的菜单。
     */
    @Override
    public void handleContainerClose(net.minecraft.network.protocol.game.ClientboundContainerClosePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer != null) {
            // 与原版 Player.closeContainer() 一致：菜单设回背包菜单
            // （父类 clientSideCloseContainer 还会 gui.setScreen(null)，假人无头跳过）
            this.fakePlayer.containerMenu = this.fakePlayer.inventoryMenu;
        }
        fire(b -> {
            b.clearOpenMenu();
            b.fireOnContainerClosed(packet.getContainerId());
        });
    }

    // ===== 粒子（假人无头不渲染到主玩家屏幕，完整记录原始包供 AI 读取） =====

    @Override
    public void handleParticleEvent(net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet) {
        // 完整记录原始包（粒子类型/位置/数量/速度）供 AI 读取。
        // 不调父类——父类 this.level.addParticle → doAddParticle → 经主玩家 Minecraft.particleEngine 渲染。
        this.session.getState().recordPacket("handleParticleEvent", packet);
        // 粒子类型用注册表 key（minecraft:xxx），与 SessionManager.recordFakeParticle 一致——
        // ParticleType 不重写 toString()，直接 toString 是类名垃圾
        net.minecraft.resources.Identifier key =
                BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType());
        this.session.getState().recordParticle(
                key != null ? key.toString() : packet.getParticle().getType().toString(),
                packet.getX(), packet.getY(), packet.getZ());
    }

    // ===== 成就/进度/统计（假人自己记录，不弹主玩家） =====

    /**
     * 进度更新：记录到假人自己的进度（不触发主玩家进度弹窗）。
     * 父类 this.advancements 是主玩家的，override 后更新假人自己的（若有）。
     */
    @Override
    public void handleUpdateAdvancementsPacket(net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket packet) {
        // 假人无头不弹进度窗，但进度数据记录到会话状态（供程序化 AI 读取）
        this.session.getState().recordAdvancements(packet);
    }

    @Override
    public void handleSelectAdvancementsTab(net.minecraft.network.protocol.game.ClientboundSelectAdvancementsTabPacket packet) {
        // 进度 tab 选择：完整记录原始包（tab 位置）供 AI 读取
        this.session.getState().recordPacket("handleSelectAdvancementsTab", packet);
    }

    /**
     * 统计：写到假人自己的统计（不写主玩家）。
     */
    @Override
    public void handleAwardStats(net.minecraft.network.protocol.game.ClientboundAwardStatsPacket packet) {
        if (this.fakePlayer != null) {
            for (var entry : packet.stats().object2IntEntrySet()) {
                this.fakePlayer.getStats().setValue(this.fakePlayer, entry.getKey(), entry.getIntValue());
            }
        }
    }

    // ===== 音效（假人 level 播放，假人也感知） =====

    @Override
    public void handleDebugSample(net.minecraft.network.protocol.game.ClientboundDebugSamplePacket packet) {
        // 调试采样数据（服务端性能采样）：完整记录原始包供 AI 读取（假人无头不显示 F3 浮层）
        this.session.getState().recordPacket("handleDebugSample", packet);
    }

    @Override
    public void handleSoundEvent(net.minecraft.network.protocol.game.ClientboundSoundPacket packet) {
        // 音效记录到假人 state（不播放到主玩家音箱，零污染）
        this.session.getState().recordSound(
                packet.getSound().value().location().toString(),
                packet.getX(), packet.getY(), packet.getZ());
    }

    @Override
    public void handleStopSoundEvent(net.minecraft.network.protocol.game.ClientboundStopSoundPacket packet) {
        // 停音效：假人无头不播主玩家音效，记录停止请求到 state（供 AI 感知环境声音变化）
        this.session.getState().recordStopSound(packet);
    }

    @Override
    public void handleOpenScreen(net.minecraft.network.protocol.game.ClientboundOpenScreenPacket packet) {
        // 打开容器界面：照原版 ClientPacketListener.handleOpenScreen 建对应菜单赋给假人，
        // 使后续 ContainerContent/ContainerSetSlot 能写进假人菜单（补铁律：容器内容不丢失），
        // 并作为 Bot.getContainer() 的容器会话基础。假人无头不弹主玩家 UI（跳过 gui.setScreen）。
        if (this.fakePlayer != null) {
            net.minecraft.world.inventory.MenuType<?> menuType = packet.getType();
            if (menuType != null) {
                try {
                    // 26.2：MenuType.create(containerId, inventory)（标题由菜单/资源包另行提供）
                    net.minecraft.world.inventory.AbstractContainerMenu menu = menuType.create(
                            packet.getContainerId(), this.fakePlayer.getInventory());
                    this.fakePlayer.containerMenu = menu;
                } catch (Exception e) {
                    // 菜单构造失败（如 MERCHANT 客户端状态未就绪）：记录，不崩连接
                    FakeSession.LOG.warn("[{}] 打开容器菜单 {} 失败: {}", this.session.getName(), menuType, e.toString());
                }
            }
        }
        // 记录到 state（保留 AI 感知：类型/标题/id）
        this.session.getState().recordOpenScreen(packet.getType(), packet.getContainerId(), packet.getTitle());
        // Bot 事件：容器打开 + 更新容器会话
        fire(b -> {
            b.setOpenMenu(this.fakePlayer != null ? this.fakePlayer.containerMenu : null, packet.getTitle());
            b.fireOnContainerOpened(packet.getType(), packet.getContainerId(), packet.getTitle());
        });
    }

    // ===== 骑乘（假人自己） =====

    @Override
    public void handleSetEntityPassengersPacket(net.minecraft.network.protocol.game.ClientboundSetPassengersPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer != null) {
            MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
            net.minecraft.world.entity.Entity vehicle = self.mockplayer$getLevel().getEntity(packet.getVehicle());
            if (vehicle == null) {
                org.slf4j.LoggerFactory.getLogger("mockplayer").warn("Received passengers for unknown entity");
            } else {
                boolean wasPlayerMounted = vehicle.hasIndirectPassenger(this.fakePlayer);
                vehicle.ejectPassengers();
                for (int id : packet.getPassengers()) {
                    net.minecraft.world.entity.Entity passenger = self.mockplayer$getLevel().getEntity(id);
                    if (passenger != null) {
                        passenger.startRiding(vehicle, true, false);
                        if (passenger == this.fakePlayer) {
                            self.mockplayer$setRemovedPlayerVehicleId(java.util.OptionalInt.empty());
                            if (!wasPlayerMounted) {
                                if (vehicle instanceof net.minecraft.world.entity.vehicle.boat.Boat) {
                                    this.fakePlayer.yRotO = vehicle.getYRot();
                                    this.fakePlayer.setYRot(vehicle.getYRot());
                                    this.fakePlayer.setYHeadRot(vehicle.getYRot());
                                }
                                // 骑乘提示：假人无头不弹主玩家 UI，记录到 state
                                this.session.getState().recordPacket("mountOnboard", packet);
                            }
                        }
                    }
                }
            }
        }
    }

    // ===== 冷却（假人自己） =====

    @Override
    public void handleItemCooldown(net.minecraft.network.protocol.game.ClientboundCooldownPacket packet) {
        if (this.fakePlayer != null) {
            if (packet.duration() == 0) {
                this.fakePlayer.getCooldowns().removeCooldown(packet.cooldownGroup());
            } else {
                this.fakePlayer.getCooldowns().addCooldown(packet.cooldownGroup(), packet.duration());
            }
        }
        fire(b -> b.fireOnItemCooldown(packet.cooldownGroup(), packet.duration()));
    }

    // ===== 标题/UI（假人无头，完整记录原始包供 AI 读取） =====

    @Override
    public void handleTitlesClear(net.minecraft.network.protocol.game.ClientboundClearTitlesPacket packet) {
        // 标题清除：完整记录原始包（是否重置时间）供 AI 读取
        this.session.getState().recordPacket("handleTitlesClear", packet);
    }

    // ===== 实体事件/音效实体（假人 level） =====

    @Override
    public void handleSoundEntityEvent(net.minecraft.network.protocol.game.ClientboundSoundEntityPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.getId());
        if (entity != null) {
            // 音效记录到假人 state（不播放到主玩家音箱，零污染）
            this.session.getState().recordSound(
                    packet.getSound().value().location().toString(),
                    entity.getX(), entity.getY(), entity.getZ());
        }
    }

    @Override
    public void handleEntityEvent(net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet) {
        // 完整处理实体事件到假人 level（不调 super——父类碰主玩家音效/粒子/不死图腾 UI）
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = packet.getEntity(self.mockplayer$getLevel());
        if (entity != null) {
            switch (packet.getEventId()) {
                case 21 -> {
                    // 守卫者音效：记录到假人 state（不播放到主玩家音箱，零污染）
                    this.session.getState().recordSound("guardian_attack", entity.getX(), entity.getY(), entity.getZ());
                }
                case 35 -> {
                    // 不死图腾：记录到假人 state（不渲染粒子/不弹 UI，零污染）
                    this.session.getState().recordSound("totem_use", entity.getX(), entity.getY(), entity.getZ());
                }
                case 63 -> {
                    // 嗅探兽音效：记录到假人 state
                    this.session.getState().recordSound("sniffer_digging_stop", entity.getX(), entity.getY(), entity.getZ());
                }
                default -> entity.handleEntityEvent(packet.getEventId());
            }
        }
    }

    @Override
    public void handleAnimate(net.minecraft.network.protocol.game.ClientboundAnimatePacket packet) {
        // 实体动画状态更新到假人 level（不碰主玩家粒子引擎）
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.getId());
        if (entity != null) {
            if (packet.getAction() == 0) {
                ((net.minecraft.world.entity.LivingEntity) entity).swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            } else if (packet.getAction() == 3) {
                ((net.minecraft.world.entity.LivingEntity) entity).swing(net.minecraft.world.InteractionHand.OFF_HAND);
            } else if (packet.getAction() == 2) {
                ((net.minecraft.world.entity.player.Player) entity).stopSleepInBed(false, false);
            } else if (packet.getAction() == 4 || packet.getAction() == 5) {
                // 暴击/附魔粒子：假人无头不渲染，但完整记录原始包供 AI 感知攻击动画
                this.session.getState().recordPacket("handleAnimate", packet);
            }
        }
    }

    // ===== 其他容器/背包 =====

    @Override
    public void handleSetCursorItem(net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.containerMenu.setCarried(packet.contents());
        }
    }

    @Override
    public void handleSetPlayerInventory(net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket packet) {
        if (this.fakePlayer != null) {
            this.fakePlayer.getInventory().setItem(packet.slot(), packet.contents());
        }
    }

    @Override
    public void handleContainerSetData(net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer != null && this.fakePlayer.containerMenu.containerId == packet.getContainerId()) {
            this.fakePlayer.containerMenu.setData(packet.getId(), packet.getValue());
        }
    }

    // ===== 重生（假人分支，完整迁移假人状态，不碰 mc.player/mc.gameMode/mc.setLevel/mc.setCameraEntity） =====

    @Override
    public void handleRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.network.protocol.game.CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey = spawnInfo.dimension();
        net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> dimensionType = spawnInfo.dimensionType();
        if (this.fakePlayer == null) {
            return;
        }
        net.minecraft.client.player.LocalPlayer oldPlayer = this.fakePlayer;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> oldDimensionKey = oldPlayer.level().dimension();
        boolean dimensionChanged = dimensionKey != oldDimensionKey;
        if (dimensionChanged) {
            // 迁移地图数据（不能丢！假人重生不罕见）：从旧 level 取，写入新 level
            com.mockplayer.session.accessor.MockplayerClientLevelAccessor oldLevelAccessor =
                    (com.mockplayer.session.accessor.MockplayerClientLevelAccessor) self.mockplayer$getLevel();
            java.util.Map<net.minecraft.world.level.saveddata.maps.MapId, net.minecraft.world.level.saveddata.maps.MapItemSavedData> mapData =
                    oldLevelAccessor.mockplayer$getAllMapData();
            boolean isDebug = spawnInfo.isDebug();
            boolean isFlat = spawnInfo.isFlat();
            int seaLevel = spawnInfo.seaLevel();
            net.minecraft.client.multiplayer.ClientLevel.ClientLevelData levelData = new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(
                    self.mockplayer$getLevelData().getDifficulty(),
                    self.mockplayer$getLevelData().isHardcore(),
                    isFlat);
            self.mockplayer$setLevelData(levelData);
            net.minecraft.client.multiplayer.ClientLevel newLevel = new net.minecraft.client.multiplayer.ClientLevel(
                    this,
                    levelData,
                    dimensionKey,
                    dimensionType,
                    self.mockplayer$getServerChunkRadius(),
                    self.mockplayer$getServerSimulationDistance(),
                    net.minecraft.client.Minecraft.getInstance().levelExtractor,
                    isDebug,
                    spawnInfo.seed(),
                    seaLevel);
            ((com.mockplayer.session.accessor.MockplayerClientLevelAccessor) newLevel).mockplayer$addMapData(mapData);
            // 换维后隔离注册表必须同步：注销旧 level、注册新 level（防泄漏 + 新 level 粒子/音效漏拦截）
            com.mockplayer.session.FakeLevelRegistry.unregisterFakeLevel(self.mockplayer$getLevel());
            self.mockplayer$setLevel(newLevel);
            com.mockplayer.session.FakeLevelRegistry.registerFakeLevel(newLevel);
        }
        if (oldPlayer.hasContainerOpen()) {
            oldPlayer.closeContainer();
        }
        com.mockplayer.session.FakeLocalPlayer newPlayer;
        if (packet.shouldKeep((byte) 2)) {
            newPlayer = new com.mockplayer.session.FakeLocalPlayer(
                    Minecraft.getInstance(),
                    self.mockplayer$getLevel(),
                    this,
                    oldPlayer.getStats(),
                    oldPlayer.getRecipeBook(),
                    oldPlayer.getLastSentInput(),
                    oldPlayer.isSprinting(),
                    Minecraft.getInstance().computeChatAbilities());
        } else {
            newPlayer = new com.mockplayer.session.FakeLocalPlayer(
                    Minecraft.getInstance(),
                    self.mockplayer$getLevel(),
                    this,
                    oldPlayer.getStats(),
                    oldPlayer.getRecipeBook(),
                    new net.minecraft.world.entity.player.Input(false, false, false, false, false, false, false),
                    false,
                    Minecraft.getInstance().computeChatAbilities());
        }
        // 标记未加载，等待新 level 加载完成再恢复物理（与父类 startWaitingForNewLevel 对应）
        self.mockplayer$setClientLoaded(false);
        newPlayer.setId(oldPlayer.getId());
        this.fakePlayer = newPlayer;
        // 关键：同步到 session，否则 FakeSession.tick() 仍驱动旧 player（旧 level）→ 传送后失去物理/位置错乱
        this.session.setFakePlayer(newPlayer);
        // 同步 Baritone 绑定（假人重生/换维重建 player；gameMode 不重建无需更新）
        com.mockplayer.baritone.api.IBaritone botBaritone = this.session.getBaritone();
        if (botBaritone != null) {
            botBaritone.updateBoundPlayer(newPlayer);
        }
        if (packet.shouldKeep((byte) 2)) {
            java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> data =
                    oldPlayer.getEntityData().getNonDefaultValues();
            if (data != null) {
                newPlayer.getEntityData().assignValues(data);
            }
            newPlayer.setDeltaMovement(oldPlayer.getDeltaMovement());
            newPlayer.setYRot(oldPlayer.getYRot());
            newPlayer.setXRot(oldPlayer.getXRot());
        } else {
            newPlayer.resetPos();
            newPlayer.setYRot(-180.0F);
        }
        if (packet.shouldKeep((byte) 1)) {
            newPlayer.getAttributes().assignAllValues(oldPlayer.getAttributes());
        } else {
            newPlayer.getAttributes().assignBaseValues(oldPlayer.getAttributes());
        }
        self.mockplayer$getLevel().addEntity(newPlayer);
        newPlayer.input = new net.minecraft.client.player.ClientInput();
        this.fakeGameMode.adjustPlayer(newPlayer);
        // 这些标志从旧玩家继承（父类逻辑），不从 packet 读——packet 里没有这些字段
        newPlayer.setReducedDebugInfo(oldPlayer.isReducedDebugInfo());
        newPlayer.setShowDeathScreen(oldPlayer.shouldShowDeathScreen());
        newPlayer.setDoLimitedCrafting(oldPlayer.getDoLimitedCrafting());
        newPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        newPlayer.portalEffectIntensity = oldPlayer.portalEffectIntensity;
        newPlayer.oPortalEffectIntensity = oldPlayer.oPortalEffectIntensity;
        // 假人自己的 gameType：只改假人 abilities + 记录字段，不用 setLocalMode（会污染主玩家！）
        spawnInfo.gameType().updatePlayerAbilities(newPlayer.getAbilities());
        ((com.mockplayer.session.accessor.MockplayerMultiPlayerGameModeAccessor) this.fakeGameMode)
                .mockplayer$setLocalPlayerMode(spawnInfo.gameType());
        ((com.mockplayer.session.accessor.MockplayerMultiPlayerGameModeAccessor) this.fakeGameMode)
                .mockplayer$setPreviousLocalPlayerMode(spawnInfo.previousGameType());
        // 同步假人自己的 gameType 到 FakeLocalPlayer（重生后新 player）
        newPlayer.setFakeGameType(spawnInfo.gameType());
        // 等新 level chunk 加载完成再恢复物理。
        // 不用 LevelLoadTracker——它依赖渲染线程编译回调（假人无渲染会等 30-40s 超时）。
        // 改为：setClientLoaded(false) 保持物理暂停，收到第一个 chunk 包（handleLevelChunkWithLight）
        // 时调用 notifyPlayerLoaded() 恢复物理 + 告知服务端已加载（见 handleLevelChunkWithLight override）。
        // Bot 事件：换维（from != to 时）
        if (dimensionChanged) {
            fire(b -> b.fireOnDimensionChange(oldDimensionKey, dimensionKey));
        }
        // Bot 事件：重生
        fire(b -> b.fireOnRespawn());
    }

    /**
     * 假人收到区块包：先执行父类逻辑（写假人 level），再恢复物理。
     * 假人无渲染线程，不用原版 LevelLoadTracker（会等渲染编译回调超时），
     * 收到 chunk 包即认为新 level 已就绪 → 恢复物理 + 告知服务端已加载。
     */
    @Override
    public void handleLevelChunkWithLight(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet) {
        super.handleLevelChunkWithLight(packet);
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        // 假人区块事件（激进改造：替代 MixinClientPlayNetHandler 的 chunk 广播；
        // CachedWorld.queueForPacking 由 GameEventHandler.onChunkEvent 处理）
        com.mockplayer.baritone.api.IBaritone botBaritone = this.session.getBaritone();
        if (botBaritone != null) {
            botBaritone.getGameEventHandler().onChunkEvent(
                    new com.mockplayer.baritone.api.event.events.ChunkEvent(
                            com.mockplayer.baritone.api.event.events.type.EventState.POST,
                            !packet.isSkippable()
                                    ? com.mockplayer.baritone.api.event.events.ChunkEvent.Type.POPULATE_FULL
                                    : com.mockplayer.baritone.api.event.events.ChunkEvent.Type.POPULATE_PARTIAL,
                            packet.getX(), packet.getZ()));
        }
        if (!this.hasClientLoaded()) {
            self.mockplayer$notifyPlayerLoaded();
        }
        fire(b -> b.fireOnChunkLightData(
                new net.minecraft.world.level.ChunkPos(packet.getX(), packet.getZ()),
                lightArrayCount(packet.getLightData())));
        net.minecraft.world.level.chunk.LevelChunk chunk =
                self.mockplayer$getLevel().getChunk(packet.getX(), packet.getZ());
        fire(b -> b.fireOnChunkLoaded(chunk));
    }

    /** 假人分支 handleLightUpdatePacket：光照变化包（原版写假人 level，再派发光照事件）。 */
    @Override
    public void handleLightUpdatePacket(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        super.handleLightUpdatePacket(packet);
        fire(b -> b.fireOnChunkLightData(
                new net.minecraft.world.level.ChunkPos(packet.getX(), packet.getZ()),
                lightArrayCount(packet.getLightData())));
    }

    /** 光照包携带的 byte[2048] 数量（sky + block 更新层数，无头假人队列积压不消费）。 */
    private int lightArrayCount(net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData data) {
        return data.getSkyUpdates().size() + data.getBlockUpdates().size();
    }

    /** 假人分支 handleForgetLevelChunk：区块卸载（原版逻辑写假人 level，再派发事件）。 */
    @Override
    public void handleForgetLevelChunk(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        super.handleForgetLevelChunk(packet);
        fire(b -> b.fireOnChunkUnloaded(packet.pos()));
    }

    @Override
    public void handleAddEntity(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = createEntityFromPacketFake(packet);
        if (entity != null) {
            entity.recreateFromPacket(packet);
            self.mockplayer$getLevel().addEntity(entity);
            fire(b -> b.fireOnEntityAdded(entity));
            // 矿车/蜜蜂音效：假人无头不播主玩家音效，记录到 state
            if (entity instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart) {
                this.session.getState().recordSound("minecart_ambient", entity.getX(), entity.getY(), entity.getZ());
            } else if (entity instanceof net.minecraft.world.entity.animal.bee.Bee) {
                this.session.getState().recordSound("bee_flying", entity.getX(), entity.getY(), entity.getZ());
            }
        } else {
            org.slf4j.LoggerFactory.getLogger("mockplayer").warn("Skipping Entity with id {}", packet.getType());
        }
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            this.session.getState().recordPacket("handleAddEntity", packet);
        }
    }

    /**
     * 等价于父类 createEntityFromPacket（父类是 private），用假人 level 创建实体。
     * 玩家实体：用 UUID + 名字（从假人 state 的在线名单查）建 RemotePlayer，仅需实体存在。
     */
    private net.minecraft.world.entity.Entity createEntityFromPacketFake(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.EntityType<?> type = packet.getType();
        if (type == net.minecraft.world.entity.EntityTypes.PLAYER) {
            String name = this.session.getState().getOnlinePlayers().containsKey(packet.getUUID())
                    ? this.session.getState().getOnlinePlayers().get(packet.getUUID()).name()
                    : null;
            return new FakeRemotePlayer(
                    self.mockplayer$getLevel(),
                    new com.mojang.authlib.GameProfile(packet.getUUID(), name != null ? name : ""));
        }
        return type.create(self.mockplayer$getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
    }

    /**
     * 假人 level 的玩家拷贝（幽灵）：复用 RemotePlayer 的插值/状态同步（假人 level 整层 tick 后
     * 插值会推进），但跳过 pushEntities——否则拷贝与假人本体重叠时互相推挤，假人物理被扰动 → 发包抖动
     * → 服务端 bot 抖动 → 主玩家被推着滑（「脚下抹了冰」的直接根因）。
     */
    private static final class FakeRemotePlayer extends net.minecraft.client.player.RemotePlayer {

        FakeRemotePlayer(net.minecraft.client.multiplayer.ClientLevel level,
                         com.mojang.authlib.GameProfile profile) {
            super(level, profile);
        }

        @Override
        protected void pushEntities() {
            // 幽灵拷贝只做插值/状态，不参与实体推挤
        }
    }

    // ===== 实体传送/同步（假人 level 操作，minecraft.player 部分换假人） =====

    @Override
    public void handleTeleportEntity(net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.id());
        if (entity == null) {
            // 与原版一致：假人坐骑被移除后，传送应用到假人（this.minecraft.player → this.fakePlayer）
            java.util.OptionalInt removedVehicle = self.mockplayer$getRemovedPlayerVehicleId();
            if (removedVehicle.isPresent() && removedVehicle.getAsInt() == packet.id()) {
                MockplayerClientPacketListenerAccessor.mockplayer$setValuesFromPositionPacket(packet.change(), packet.relatives(), this.fakePlayer, false);
                this.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                        this.fakePlayer.getX(), this.fakePlayer.getY(), this.fakePlayer.getZ(),
                        this.fakePlayer.getYRot(), this.fakePlayer.getXRot(), false, false));
            }
        } else {
            boolean hasRelative = packet.relatives().contains(net.minecraft.world.entity.Relative.X)
                    || packet.relatives().contains(net.minecraft.world.entity.Relative.Y)
                    || packet.relatives().contains(net.minecraft.world.entity.Relative.Z);
            // 原版规则：实体在本 level 被 tick 或非本地权威或相对移动 → 插值；
            // 假人 level 复用原版 tickEntities 后插值会推进，直接走原版路径（不手写 snap）
            boolean interpolate = self.mockplayer$getLevel().isTickingEntity(entity)
                    || !entity.isLocalInstanceAuthoritative()
                    || hasRelative;
            boolean wasInterpolated = MockplayerClientPacketListenerAccessor.mockplayer$setValuesFromPositionPacket(
                    packet.change(), packet.relatives(), entity, interpolate);
            entity.setOnGround(packet.onGround());
            if (!wasInterpolated && this.fakePlayer != null && entity.hasIndirectPassenger(this.fakePlayer)) {
                entity.positionRider(this.fakePlayer);
                this.fakePlayer.setOldPosAndRot();
                if (entity.isLocalInstanceAuthoritative()) {
                    this.connection.send(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket.fromEntity(entity));
                }
            }
        }
    }

    @Override
    public void handleEntityPositionSync(net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.id());
        if (entity != null) {
            net.minecraft.world.phys.Vec3 pos = packet.values().position();
            entity.getPositionCodec().setBase(pos);
            if (!entity.isLocalInstanceAuthoritative()) {
                float yRot = packet.values().yRot();
                float xRot = packet.values().xRot();
                boolean tooBig = entity.position().distanceToSqr(pos) > 4096.0;
                if (self.mockplayer$getLevel().isTickingEntity(entity) && !tooBig) {
                    entity.moveOrInterpolateTo(pos, yRot, xRot);
                } else {
                    entity.snapTo(pos, yRot, xRot);
                }
                // 与原版一致：若假人骑乘此实体，同步假人位置（this.minecraft.player → this.fakePlayer）
                if (!entity.isInterpolating() && entity.hasIndirectPassenger(this.fakePlayer)) {
                    entity.positionRider(this.fakePlayer);
                    this.fakePlayer.setOldPosAndRot();
                }
                entity.setOnGround(packet.onGround());
            }
        }
    }

    @Override
    public void handleRemoveEntities(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        packet.getEntityIds().forEach(entityId -> {
            net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(entityId);
            if (entity != null) {
                // 与原版一致：假人骑乘的坐骑被移除时记录 ID（供 handleTeleportEntity 兜底）
                if (this.fakePlayer != null && entity.hasIndirectPassenger(this.fakePlayer)) {
                    self.mockplayer$setRemovedPlayerVehicleId(java.util.OptionalInt.of(entityId));
                }
                self.mockplayer$getLevel().removeEntity(entityId, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                fire(b -> b.fireOnEntityRemoved(entityId));
            }
        });
    }

    @Override
    public void handleTakeItemEntity(net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity from = self.mockplayer$getLevel().getEntity(packet.getItemId());
        net.minecraft.world.entity.LivingEntity to = (net.minecraft.world.entity.LivingEntity) self.mockplayer$getLevel().getEntity(packet.getPlayerId());
        if (to == null) {
            to = this.fakePlayer;
        }
        if (from != null) {
            // 拾取音效记录到假人 state（不播放到主玩家音箱，零污染）
            if (from instanceof net.minecraft.world.entity.ExperienceOrb) {
                this.session.getState().recordSound("experience_orb_pickup", from.getX(), from.getY(), from.getZ());
            } else {
                this.session.getState().recordSound("item_pickup", from.getX(), from.getY(), from.getZ());
            }
            // 拾取粒子：记录到 state（父类经主玩家 particleEngine 渲染，假人不渲染）
            this.session.getState().recordParticle("item_pickup", from.getX(), from.getY(), from.getZ());
            // 实体被拾取移除（数据保留到假人 level）
            self.mockplayer$getLevel().removeEntity(from.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            fire(b -> b.fireOnEntityRemoved(from.getId()));
            // Bot 事件：拾取物品（经验球不触发）
            if (from instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
                fire(b -> b.fireOnPickupItem(itemEntity.getItem()));
            }
        }
    }

    @Override
    public void handleBlockEntityData(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.core.BlockPos pos = packet.getPos();
        self.mockplayer$getLevel().getBlockEntity(pos, packet.getType()).ifPresent(blockEntity -> {
            // 方块实体数据完整加载进假人 level（不碰主玩家 registryAccess/UI）
            net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, self.mockplayer$getRegistryAccess(), packet.getTag());
            blockEntity.loadWithComponents(input);
            long dataBytes = com.mockplayer.memory.StructureHeap.nbtTag(packet.getTag());
            fire(b -> b.fireOnBlockEntityData(pos, dataBytes));
        });
    }

    @Override
    public void handleMoveVehicle(net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer != null) {
            net.minecraft.world.entity.Entity vehicle = this.fakePlayer.getRootVehicle();
            if (vehicle != this.fakePlayer && vehicle.isLocalInstanceAuthoritative()) {
                net.minecraft.world.phys.Vec3 target = packet.position();
                net.minecraft.world.phys.Vec3 currentTarget;
                if (vehicle.isInterpolating()) {
                    currentTarget = vehicle.getInterpolation().position();
                } else {
                    currentTarget = vehicle.position();
                }
                if (target.distanceTo(currentTarget) > 1.0E-5F) {
                    if (vehicle.isInterpolating()) {
                        vehicle.getInterpolation().cancel();
                    }
                    vehicle.absSnapTo(target.x(), target.y(), target.z(), packet.yRot(), packet.xRot());
                }
                this.connection.send(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket.fromEntity(vehicle));
            }
        }
    }

    @Override
    public void handleLookAt(net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        if (this.fakePlayer != null) {
            net.minecraft.world.phys.Vec3 pos = packet.getPosition(self.mockplayer$getLevel());
            if (pos != null) {
                this.fakePlayer.lookAt(packet.getFromAnchor(), pos);
            }
        }
    }

    @Override
    public void handleExplosion(net.minecraft.network.protocol.game.ClientboundExplodePacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.phys.Vec3 center = packet.center();
        // 音效：记录到假人 state（不播放到主玩家音箱，零污染）
        this.session.getState().recordSound(
                packet.explosionSound().value().location().toString(),
                center.x(), center.y(), center.z());
        // 粒子：记录到 state（不渲染到主玩家屏幕）
        this.session.getState().recordParticle("explosion", center.x(), center.y(), center.z());
        // 与原版一致：假人 level 追踪爆炸效果（方块破碎粒子等）
        self.mockplayer$getLevel().trackExplosionEffects(center, packet.radius(), packet.blockCount(), packet.blockParticles());
        // 与原版一致：假人被爆炸击退（this.minecraft.player → this.fakePlayer）
        packet.playerKnockback().ifPresent(this.fakePlayer::addDeltaMovement);
    }

    // ===== 配方书（假人自己的配方） =====

    @Override
    public void handleRecipeBookAdd(net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket packet) {
        if (this.fakePlayer != null) {
            net.minecraft.client.ClientRecipeBook recipeBook = this.fakePlayer.getRecipeBook();
            if (packet.replace()) {
                recipeBook.clear();
            }
            for (var entry : packet.entries()) {
                // 与原版一致（this.minecraft.player → this.fakePlayer）
                recipeBook.add(entry.contents());
                if (entry.highlight()) {
                    recipeBook.addHighlight(entry.contents().id());
                }
                if (entry.notification()) {
                    // 配方 toast 是主玩家 UI，假人记录到 state
                    this.session.getState().recordPacket("recipeToast", entry.contents());
                }
            }
        }
    }

    @Override
    public void handleRecipeBookRemove(net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket packet) {
        if (this.fakePlayer != null) {
            net.minecraft.client.ClientRecipeBook recipeBook = this.fakePlayer.getRecipeBook();
            for (net.minecraft.world.item.crafting.display.RecipeDisplayId id : packet.recipes()) {
                recipeBook.remove(id);
            }
            // 与原版 refreshRecipeBook 一致（searchTrees 是假人 listener 自己的字段；UI 部分跳过）
            recipeBook.rebuildCollections();
        }
    }

    @Override
    public void handleRecipeBookSettings(net.minecraft.network.protocol.game.ClientboundRecipeBookSettingsPacket packet) {
        if (this.fakePlayer != null) {
            net.minecraft.client.ClientRecipeBook recipeBook = this.fakePlayer.getRecipeBook();
            recipeBook.setBookSettings(packet.bookSettings());
            // 与原版 refreshRecipeBook 一致
            recipeBook.rebuildCollections();
        }
    }

    @Override
    public void handlePlaceRecipe(net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket packet) {
        // 幽灵配方：完整记录原始包（容器 ID + 配方展示）供 AI 读取，不弹主玩家幽灵配方
        this.session.getState().recordPacket("handlePlaceRecipe", packet);
    }

    // ===== 容器/菜单（假人自己的） =====

    @Override
    public void handleOpenBook(net.minecraft.network.protocol.game.ClientboundOpenBookPacket packet) {
        // 打开书本：完整记录原始包（手别）+ 书本内容已在假人背包，不弹主玩家书本界面
        this.session.getState().recordPacket("handleOpenBook", packet);
    }

    @Override
    public void handleMountScreenOpen(net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket packet) {
        // 骑乘容器：完整建假人自己的菜单（不弹主玩家 UI，但数据进假人背包/菜单）
        if (this.fakePlayer == null) {
            return;
        }
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.entity.Entity entity = self.mockplayer$getLevel().getEntity(packet.getEntityId());
        net.minecraft.client.player.LocalPlayer player = this.fakePlayer;
        int inventoryColumns = packet.getInventoryColumns();
        net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(
                net.minecraft.world.inventory.AbstractMountInventoryMenu.getInventorySize(inventoryColumns));
        if (entity instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse) {
            net.minecraft.world.inventory.HorseInventoryMenu menu = new net.minecraft.world.inventory.HorseInventoryMenu(
                    packet.getContainerId(), player.getInventory(), container, horse, inventoryColumns);
            player.containerMenu = menu;
            // 同步 Bot.openMenu（马菜单也要能被 Bot.getContainer() 读到）
            this.fire(b -> b.setOpenMenu(player.containerMenu, net.minecraft.network.chat.Component.translatable("container.horse")));
        } else if (entity instanceof net.minecraft.world.entity.animal.nautilus.AbstractNautilus nautilus) {
            net.minecraft.world.inventory.NautilusInventoryMenu menu = new net.minecraft.world.inventory.NautilusInventoryMenu(
                    packet.getContainerId(), player.getInventory(), container, nautilus, inventoryColumns);
            player.containerMenu = menu;
            this.fire(b -> b.setOpenMenu(player.containerMenu, net.minecraft.network.chat.Component.translatable("container.horse")));
        }
    }

    @Override
    public void handleOpenSignEditor(net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket packet) {
        // 告示牌编辑：完整记录原始包（方块位置/是否正面）供 AI 读取，不弹主玩家编辑界面
        this.session.getState().recordPacket("handleOpenSignEditor", packet);
    }

    @Override
    public void handleMerchantOffers(net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket packet) {
        if (this.fakePlayer != null && this.fakePlayer.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu merchantMenu) {
            // 与原版一致（this.minecraft.player.containerMenu → this.fakePlayer.containerMenu）
            merchantMenu.setOffers(packet.getOffers());
            merchantMenu.setXp(packet.getVillagerXp());
            merchantMenu.setMerchantLevel(packet.getVillagerLevel());
            merchantMenu.setShowProgressBar(packet.showProgress());
            merchantMenu.setCanRestock(packet.canRestock());
            fire(b -> b.fireOnMerchantOffersUpdated(packet.getOffers()));
        }
    }

    // ===== UI/HUD（假人无头，忽略渲染；数据已通过其他 handler 记录） =====

    @Override
    public void handleTabListCustomisation(net.minecraft.network.protocol.game.ClientboundTabListPacket packet) {
        // Tab 列表 header/footer：完整记录原始包供 AI 读取，不弹主玩家 Tab 界面
        this.session.getState().recordPacket("handleTabListCustomisation", packet);
    }

    @Override
    public void handleBossUpdate(net.minecraft.network.protocol.game.ClientboundBossEventPacket packet) {
        // Boss 事件数据记录到假人 state（不弹主玩家 Boss 栏，供 AI 感知 Boss 状态）
        this.session.getState().recordBossEvent(packet);
    }

    @Override
    public void handleLowDiskSpaceWarning(net.minecraft.network.protocol.game.ClientboundLowDiskSpaceWarningPacket packet) {
        // 磁盘警告：完整记录原始包供 AI 读取（不影响假人逻辑）
        this.session.getState().recordPacket("handleLowDiskSpaceWarning", packet);
    }

    @Override
    public void handleChangeDifficulty(net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket packet) {
        // 难度数据写入假人自己的 levelData（父类 this.levelData 是假人的），仅去掉主玩家 GUI 反应
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevelData().setDifficulty(packet.difficulty());
        self.mockplayer$getLevelData().setDifficultyLocked(packet.locked());
    }

    @Override
    public void handleGameRuleValues(net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket packet) {
        // 游戏规则值：完整记录原始包供 AI 读取（假人无头不弹规则界面）
        this.session.getState().recordPacket("handleGameRuleValues", packet);
    }

    @Override
    public void handleConfigurationStart(net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket packet) {
        // 服务端要求重进配置：完整记录原始包供 AI 读取。
        // 假人无头不走主玩家重进 UI（会清主玩家 level），保持连接等待后续 login 包重建假人 level。
        this.session.getState().recordPacket("handleConfigurationStart", packet);
    }

    @Override
    public void handleTestInstanceBlockStatus(net.minecraft.network.protocol.game.ClientboundTestInstanceBlockStatus packet) {
        // 测试实例状态：完整记录原始包供 AI 读取（假人无头不弹测试界面）
        this.session.getState().recordPacket("handleTestInstanceBlockStatus", packet);
    }

    // ===== 地图数据 / 世界事件 / 挖矿 / 生物群系 / 出生点 / tick（假人 level，不碰主玩家） =====

    @Override
    public void handleMapItemData(net.minecraft.network.protocol.game.ClientboundMapItemDataPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.level.saveddata.maps.MapId id = packet.mapId();
        net.minecraft.world.level.saveddata.maps.MapItemSavedData data = self.mockplayer$getLevel().getMapData(id);
        if (data == null) {
            data = net.minecraft.world.level.saveddata.maps.MapItemSavedData.createForClient(
                    packet.scale(), packet.locked(), self.mockplayer$getLevel().dimension());
            self.mockplayer$getLevel().overrideMapData(id, data);
        }
        packet.applyToMap(data);
    }

    @Override
    public void handleTickingState(net.minecraft.network.protocol.game.ClientboundTickingStatePacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        net.minecraft.world.TickRateManager manager = self.mockplayer$getLevel().tickRateManager();
        manager.setTickRate(packet.tickRate());
        manager.setFrozen(packet.isFrozen());
    }

    @Override
    public void handleTickingStep(net.minecraft.network.protocol.game.ClientboundTickingStepPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().tickRateManager().setFrozenTicksToRun(packet.tickSteps());
    }

    @Override
    public void handleLevelEvent(net.minecraft.network.protocol.game.ClientboundLevelEventPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        if (packet.isGlobalEvent()) {
            self.mockplayer$getLevel().globalLevelEvent(packet.getType(), packet.getPos(), packet.getData());
        } else {
            self.mockplayer$getLevel().levelEvent(packet.getType(), packet.getPos(), packet.getData());
        }
    }

    @Override
    public void handleBlockDestruction(net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().destroyBlockProgress(packet.getId(), packet.getPos(), packet.getProgress());
    }

    @Override
    public void handleChunksBiomes(net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        for (net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            self.mockplayer$getLevel().getChunkSource().replaceBiomes(data.pos().x(), data.pos().z(), data.getReadBuffer());
        }
        for (net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            self.mockplayer$getLevel().onChunkLoaded(new net.minecraft.world.level.ChunkPos(data.pos().x(), data.pos().z()));
        }
    }

    @Override
    public void handleSetSpawn(net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().setRespawnData(packet.respawnData());
    }

    @Override
    public void handleBlockEvent(net.minecraft.network.protocol.game.ClientboundBlockEventPacket packet) {
        MockplayerClientPacketListenerAccessor self = (MockplayerClientPacketListenerAccessor) this;
        self.mockplayer$getLevel().blockEvent(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
    }

    @Override
    public void handleGameTestHighlightPos(net.minecraft.network.protocol.game.ClientboundGameTestHighlightPosPacket packet) {
        // 测试高亮位置：完整记录原始包供 AI 读取（假人无头不渲染测试高亮）
        this.session.getState().recordPacket("handleGameTestHighlightPos", packet);
    }

    @Override
    public void handlePlayerCombatKill(net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        if (this.fakePlayer == null || packet.playerId() != this.fakePlayer.getId()) {
            return;
        }
        // 假人死亡：完整记录原始包（死亡消息/击杀者/是否弹死亡屏），数据零丢弃；不弹主玩家死亡界面。
        // 无头 bot 没有可点击的死亡屏，收到死亡包后自动走原版 PERFORM_RESPAWN 网络路径。
        this.session.getState().recordPacket("handlePlayerCombatKill", packet);
        this.session.getState().setHealth(0.0F);
        if (this.fakePlayer != null) {
            // 自动重生开关（默认 true，产品行为不变）：测试关闭后由 respawn 命令触发重生
            if (this.session.isAutoRespawn()) {
                this.fakePlayer.respawn();
            }
        }
        // Bot 事件：死亡（26.2 ClientboundPlayerCombatKillPacket 是 record，死亡消息 accessor = message()）
        net.minecraft.network.chat.Component deathMessage;
        try {
            deathMessage = packet.message();
        } catch (Exception ignored) {
            deathMessage = net.minecraft.network.chat.Component.translatable("death.attack.generic");
        }
        net.minecraft.network.chat.Component finalDeathMessage = deathMessage;
        fire(b -> b.fireOnDeath(finalDeathMessage));
    }

    @Override
    public void handleSetCamera(net.minecraft.network.protocol.game.ClientboundSetCameraPacket packet) {
        // 相机切换：假人无头不切主玩家相机，完整记录原始包（spectator 视角目标实体 ID）供 AI 读取
        this.session.getState().recordPacket("handleSetCamera", packet);
    }

    @Override
    public void handleCustomChatCompletions(net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket packet) {
        // 聊天补全：完整记录原始包（action + entries）供 AI 读取，不碰主玩家补全提供器
        this.session.getState().recordPacket("handleCustomChatCompletions", packet);
    }

    @Override
    public void handleDeleteChat(net.minecraft.network.protocol.game.ClientboundDeleteChatPacket packet) {
        // 聊天删除：完整记录原始包（消息签名）供 AI 读取（聊天历史文本仍由 handlePlayerChat 记录）
        this.session.getState().recordPacket("handleDeleteChat", packet);
    }

    /** 假人收资源包推送：记录到 state，不弹主玩家资源包确认框（父类会 minecraft.gui.setScreen）。 */
    @Override
    public void handleResourcePackPush(net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket packet) {
        this.session.getState().recordPacket("handleResourcePackPush", packet);
    }

    /** 假人收资源包弹出：记录到 state，不碰主玩家 gui（父类经 lambda 弹窗/清理主玩家屏幕）。 */
    @Override
    public void handleResourcePackPop(net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket packet) {
        this.session.getState().recordPacket("handleResourcePackPop", packet);
    }

    /** 假人收对话框显示：记录到 state，不弹主玩家对话框（父类会 minecraft.gui.showDialog）。 */
    @Override
    public void handleShowDialog(net.minecraft.network.protocol.common.ClientboundShowDialogPacket packet) {
        this.session.getState().recordPacket("handleShowDialog", packet);
    }

    /** 假人收对话框清除：记录到 state，不碰主玩家 gui（父类 clearDialog 会关主玩家 WarningScreen）。 */
    @Override
    public void handleClearDialog(net.minecraft.network.protocol.common.ClientboundClearDialogPacket packet) {
        this.session.getState().recordPacket("handleClearDialog", packet);
    }

    /**
     * 假人被服务端传送到子服务器（server transfer，如资源世界/跨服）。
     * 完整版跟随传送：不调父类（父类会 ConnectScreen.startConnecting(minecraft) 劫持主玩家），
     * 改记录 state + 断开旧连接 + 发起到子服的离线登录（原版一致，携带 cookies）。
     * 若重连失败，由 connectTo 的失败路径就地下线（同 kick）。
     */
    @Override
    public void handleTransfer(net.minecraft.network.protocol.common.ClientboundTransferPacket packet) {        this.session.getState().recordPacket("handleTransfer", packet);
        String host = packet.host();
        int port = packet.port();
        FakeSession.LOG.info("[{}] 假人被传送至子服 {}:{}，跟随重连", this.session.getName(), host, port);

        // 原版一致：构造 TransferState（父类 protected 字段：cookies / seenPlayers / seenInsecureChatWarning）
        net.minecraft.client.multiplayer.TransferState transferState = new net.minecraft.client.multiplayer.TransferState(
                this.serverCookies,
                this.seenPlayers,
                this.seenInsecureChatWarning
        );

        // 置重连标志，防止旧连接断开时 cleanupOnKick 删除 session
        this.session.setReconnecting(true);
        // 断开旧连接（只断连接，reconnecting 下不触发删除）
        this.session.disconnect();
        // 发起到子服的离线登录（target 直接用 transfer 地址）
        this.session.connectTo(host, port, transferState);
    }

    /**
     * 假人被服务端踢出（ClientboundDisconnectPacket）。
     * 不调父类——父类 handleDisconnect → connection.disconnect → onDisconnect → Minecraft.disconnect()
     * 会弹主玩家断线界面并断开主玩家连接（污染）。改为只断开假人 + 从管理器移除。
     */
    @Override
    public void handleDisconnect(net.minecraft.network.protocol.common.ClientboundDisconnectPacket packet) {
        this.session.getState().recordPacket("handleDisconnect", packet);
        net.minecraft.network.chat.Component reason = packet.reason();
        FakeSession.LOG.warn("[{}] 假人被踢出: {}", this.session.getName(), reason.getString());
        // 主玩家聊天栏通知：假人被服务端踢出 + 服务端给的原因（主人 2026-08-15 需求）
        notifyMainPlayer("chat.mockplayer.bot_kicked", reason);
        this.cleanupOnKick();
    }

    /**
     * 假人连接断开（被踢/断线/服务端关闭）。
     * 不调父类——父类 onDisconnect 调 Minecraft.disconnect() 会弹主玩家断线界面并断主玩家连接（污染）。
     */
    @Override
    public void onDisconnect(net.minecraft.network.DisconnectionDetails details) {
        this.session.getState().recordPacket("onDisconnect", details);
        net.minecraft.network.chat.Component reason = details.reason();
        FakeSession.LOG.warn("[{}] 假人连接断开: {}", this.session.getName(),
                reason != null ? reason.getString() : "unknown");
        // 主动删除（本地 disconnect，reason=fake_player_removed）不打扰主玩家；
        // 其他断开（被踢后的连接关闭/断线/服务端关闭）通知主玩家
        if (reason == null || !isLocalRemoval(reason)) {
            notifyMainPlayer("chat.mockplayer.bot_disconnected",
                    reason != null ? reason
                            : net.minecraft.network.chat.Component.translatable(
                            "chat.mockplayer.disconnect.unknown"));
        }
        this.cleanupOnKick();
    }

    /** 主动删除的本地断开原因（disconnect() 用该 key 断开假人连接）。 */
    private static boolean isLocalRemoval(net.minecraft.network.chat.Component reason) {
        return reason.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                && "disconnect.mockplayer.fake_player_removed".equals(tc.getKey());
    }

    /**
     * 主玩家聊天栏通知（红色系统消息，不弹任何界面、不动主玩家连接）。
     * 与 BotManagerImpl 连接失败通知同款写法。
     */
    private void notifyMainPlayer(String key, net.minecraft.network.chat.Component detail) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                                key,
                                net.minecraft.network.chat.Component.literal(this.session.getName())
                                        .withStyle(net.minecraft.ChatFormatting.AQUA),
                                detail)
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
        });
    }

    /** 断开假人 + 从 SessionManager 移除（幂等，防 handleDisconnect 与 onDisconnect 双调） */
    private void cleanupOnKick() {
        // transfer 跟随重连期间，旧连接断开不算「被踢下线」——session 由重连成功/失败决定去留
        if (this.session.isReconnecting()) {
            FakeSession.LOG.info("[{}] 假人重连中，忽略旧连接断开", this.session.getName());
            return;
        }
        // 从管理器移除会触发 disconnect()；若已移除则幂等返回
        com.mockplayer.session.SessionManager.getInstance().removeFakePlayer(this.session.getName());
    }
}
