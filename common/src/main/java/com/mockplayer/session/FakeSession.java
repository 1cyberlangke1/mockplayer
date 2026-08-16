package com.mockplayer.session;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.LoginProtocols;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 假人会话：持有独立的网络连接，负责离线登录到当前服务器并保持在线。
 *
 * P0 目标：假人能登录上线（服务端 Tab 可见、客户端能看到实体）。
 * 连接流程复用 MC 自带类：Connection + 登录协议，不重写网络层。
 *
 * 内部契约：本类公开方法供同包实现/命令层使用；跨包白名单仅限
 * com.mockplayer.mixin.*（Mixin 注入读取会话状态）、平台入口与测试套件，
 * 外部/附属 mod 请走 {@link com.mockplayer.api.Bot} 接口。
 */
@com.mockplayer.api.Internal
public class FakeSession {

    public static final Logger LOG = LoggerFactory.getLogger("Mockplayer/FakeSession");

    /** 假人名字 */
    private final String name;
    /** 独立网络连接（后台网络线程建连写入，渲染线程 tick 读取 → volatile） */
    private volatile Connection connection;
    /** 当前是否已连接（TCP 层面，网络线程写入 → volatile） */
    private volatile boolean connected;
    /** 服务端下发的登录 profile（后台线程写入 → volatile） */
    private volatile com.mojang.authlib.GameProfile profile;
    /** 假人 play 阶段 listener（进 play 后设置，后台线程写入 → volatile） */
    private volatile FakePlayListener playListener;
    /** 假人完整状态（无头但完整感知） */
    private final FakePlayerState state = new FakePlayerState();
    /** 假人自己的 LocalPlayer（复用物理），进 play 后创建（后台线程写入 → volatile） */
    private volatile net.minecraft.client.player.LocalPlayer fakePlayer;
    /** 连接失败回调（后台网络线程调用，回调内需自行切回主线程；参数为失败提示的翻译 key） */
    private java.util.function.Consumer<String> onConnectFail;
    /**
     * 重连中标志：假人被 transfer 到子服时置 true。
     * 重连期间旧 listener 断开（onDisconnect/handleDisconnect → cleanupOnKick）不能删除 session，
     * 由重连成功/失败决定去留。
     */
    private volatile boolean reconnecting;
    /** 待注入的 transfer cookies（跟随传送时带过去，原版一致） */
    private net.minecraft.client.multiplayer.TransferState pendingTransfer;
    /** 创建者标识（BotManagerImpl 创建时写入）："command" = 主玩家命令；外部 mod 用自己 modId */
    private String owner = BotManagerImpl.COMMAND_OWNER;
    /** 创建来源：CORE = 本 mod 命令创建；API = 外部/附属 mod 经公共 API 创建（默认）。 */
    private volatile com.mockplayer.api.BotSource source = com.mockplayer.api.BotSource.API;
    /** 关联的 Bot 实现（BotManagerImpl 创建时写入，驱动事件/动作） */
    private BotImpl bot;
    /** 假人专属 Baritone 实例（handleLogin 创建并绑定假人 player/gameMode；
     *  disconnect 销毁；重生只更新 player 绑定）。 */
    private volatile com.mockplayer.baritone.api.IBaritone botBaritone;
    /** 死亡后是否自动重生（默认 true = 产品行为不变；测试可关闭以验证 respawn 命令） */
    private volatile boolean autoRespawn = true;
    /** 会话是否已被删除（BotManager 移除后置位；in-flight 登录据此放弃，防孤儿 level 泄漏）。 */
    private volatile boolean disposed;
    /** 假人 tick 计数（喂 Baritone 事件的 tick 序号，官方 TickEvent.count 等价）。 */
    private int baritoneTickCount;
    /** 假人连接的服务器标识（"singleplayer" = 本机单机/局域网，共享主世界缓存；
     *  否则 host:port = 独立服务器，独立缓存目录）。 */
    private volatile String serverKey = "singleplayer";

    public FakeSession(String name) {
        this.name = name;
    }

    /** 设置创建者标识（BotManagerImpl.createBot 调用） */
    public void setOwner(String owner) {
        if (owner != null && !owner.isBlank()) {
            this.owner = owner;
        }
    }

    /** 创建者标识 */
    public String getOwner() {
        return this.owner;
    }

    public com.mockplayer.api.BotSource getSource() {
        return this.source;
    }

    void setSource(com.mockplayer.api.BotSource source) {
        if (source != null) {
            this.source = source;
        }
    }

    /** 设置关联 Bot（BotManagerImpl.createBot 调用） */
    public void setBot(BotImpl bot) {
        this.bot = bot;
    }

    /** 关联的 Bot 实现 */
    public BotImpl getBot() {
        return this.bot;
    }

    /** 设置死亡后自动重生开关（测试用；默认 true） */
    public void setAutoRespawn(boolean autoRespawn) {
        this.autoRespawn = autoRespawn;
    }

    /** 死亡后是否自动重生 */
    public boolean isAutoRespawn() {
        return this.autoRespawn;
    }

    /** 是否处于连接/登录阶段（TCP 建连中或已建连未 PLAYING） */
    public boolean isConnecting() {
        return this.connection != null && !isConnected();
    }

    /** 服务端下发的登录 profile（登录完成前为 null） */
    public com.mojang.authlib.GameProfile getProfile() {
        return this.profile;
    }

    /** 设置连接失败回调（SessionManager 注入，用于回收会话 + 通知玩家） */
    public void setOnConnectFail(java.util.function.Consumer<String> onConnectFail) {
        this.onConnectFail = onConnectFail;
    }

    /** 会话是否已被删除（删除后 in-flight 登录/配置必须放弃）。 */
    public boolean isDisposed() {
        return this.disposed;
    }

    /** 设置重连中标志（FakePlayListener.handleTransfer 置 true，重连成功/失败复位） */
    public void setReconnecting(boolean reconnecting) {
        this.reconnecting = reconnecting;
    }

    public boolean isReconnecting() {
        return this.reconnecting;
    }

    /** 设置待注入的 transfer cookies（跟随传送时，登录成功后合入 CommonListenerCookie） */
    public void setPendingTransfer(net.minecraft.client.multiplayer.TransferState pendingTransfer) {
        this.pendingTransfer = pendingTransfer;
    }

    public net.minecraft.client.multiplayer.TransferState getPendingTransfer() {
        return this.pendingTransfer;
    }

    /** 触发连接失败回调（携带失败提示的翻译 key） */
    private void notifyConnectFail(String key) {
        if (this.onConnectFail != null) {
            this.onConnectFail.accept(key);
        }
    }

    /** 登录成功后由 listener 回调设置 */
    void setProfile(com.mojang.authlib.GameProfile profile) {
        this.profile = profile;
    }

    /** 登录完成（PLAYING）时由 FakePlayListener.handleLogin 调用：标记已连接，消除与 connected 的竞态 */
    public void markConnected() {
        this.connected = true;
    }

    /** play 阶段 listener 设置（进 play 后由配置 Mixin 回调） */
    void setPlayListener(FakePlayListener playListener) {
        this.playListener = playListener;
    }

    /** 假人 LocalPlayer 设置（由 Mixin 在假人 handleLogin 时创建） */
    public void setFakePlayer(net.minecraft.client.player.LocalPlayer fakePlayer) {
        this.fakePlayer = fakePlayer;
    }

    public net.minecraft.client.player.LocalPlayer getFakePlayer() {
        return this.fakePlayer;
    }

    /** 假人专属 Baritone 实例（未 PLAYING 时为 null）。 */
    public com.mockplayer.baritone.api.IBaritone getBaritone() {
        return this.botBaritone;
    }

    /** 设置假人专属 Baritone 实例（FakePlayListener.handleLogin 调用）。 */
    public void setBaritone(com.mockplayer.baritone.api.IBaritone baritone) {
        this.botBaritone = baritone;
    }

    /** 假人寻路配置覆盖项（/control config set 写入；命中优先于全局配置）。 */
    private final java.util.Map<String, Object> navigateOverrides = new java.util.HashMap<>();

    /** 取寻路配置覆盖项（无返回 null）。 */
    public Object getNavigateOverride(String key) {
        return this.navigateOverrides.get(key);
    }

    /** 写入寻路配置覆盖项（null = 清除）。 */
    public void setNavigateOverride(String key, Object value) {
        if (value == null) {
            this.navigateOverrides.remove(key);
        } else {
            this.navigateOverrides.put(key, value);
        }
    }

    /** 全部寻路配置覆盖项（只读快照；命令/查询用）。 */
    public java.util.Map<String, Object> getNavigateOverrides() {
        return java.util.Map.copyOf(this.navigateOverrides);
    }

    /** 假人 level（假人 player 所在 level）——假人 gameMode 操作（挖矿/交互）隔离用，绝不返回主玩家 level */
    public net.minecraft.client.multiplayer.ClientLevel getFakeLevel() {
        return this.fakePlayer != null ? (net.minecraft.client.multiplayer.ClientLevel) this.fakePlayer.level() : null;
    }

    public FakePlayerState getState() {
        return this.state;
    }

    public FakePlayListener getPlayListener() {
        return this.playListener;
    }

    /** 内部：假人 TCP 连接（sendModPayload 出站发送用；PLAYING 后可用，断开为 null）。 */
    @com.mockplayer.api.Internal
    public Connection getConnection() {
        return this.connection;
    }

    /** 假人区块加载半径：登录时取配置默认（2，节约性能），命令可改；与主玩家完全隔离。 */
    private volatile int chunkRadius = com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius();

    /** 登录时发送/保存的 ClientInformation（改半径时复制 viewDistance 后经假人连接重发）。 */
    private volatile net.minecraft.server.level.ClientInformation clientInformation;

    /** 返回假人当前区块加载半径。 */
    public int getChunkRadius() {
        return this.chunkRadius;
    }

    /** 记录登录使用的 ClientInformation（FakeLoginListener 构造后调用）。 */
    void setClientInformation(net.minecraft.server.level.ClientInformation information) {
        this.clientInformation = information;
    }

    /**
     * 设置假人区块加载半径：本地 chunk 缓存 updateViewRadius + 经假人连接发
     * ServerboundClientInformationPacket（viewDistance=radius）→ 服务端
     * requestedViewDistance / ChunkTrackingView 同步（ChunkMap 每 tick 对比生效）。
     */
    public void setChunkRadius(int radius) {
        this.chunkRadius = radius;
        if (this.getFakePlayer() != null
                && this.getFakePlayer().level() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
            cl.getChunkSource().updateViewRadius(radius);
        }
        net.minecraft.server.level.ClientInformation info = this.clientInformation;
        if (this.playListener != null && info != null) {
            info = new net.minecraft.server.level.ClientInformation(
                    info.language(), radius, info.chatVisibility(), info.chatColors(),
                    info.modelCustomisation(), info.mainHand(),
                    info.textFilteringEnabled(), info.allowsListing(), info.particleStatus());
            this.clientInformation = info;
            this.playListener.broadcastClientInformation(info);
        }
    }

    /**
     * 发起离线登录：连接当前客户端所在的服务器，用假人名字登录。
     *
     * 单人/局域网（集成服务器）：必须先对局域网开放（有 TCP 端口），
     * 未开放则不创建；开放后用集成服务器的真实端口（不写死 25565）。
     * 多人（独立服务器）：从主玩家连接取真实地址与端口。
     */
    public void connect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LOG.warn("[{}] 当前不在服务器中，无法创建假人", name);
            return;
        }

        String host = "127.0.0.1";
        int port = 25565;

        var singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) {
            // 单人/局域网：集成服务器的 publishedPort 仅在开放局域网后才有值（未开放为 -1）
            int lanPort = singleplayer.getPort();
            if (lanPort < 0) {
                LOG.warn("[{}] 未开放局域网，拒绝创建假人", name);
                notifyConnectFail("commands.mockplayer.newplayer.lan_not_open");
                return;
            }
            port = lanPort;
        } else {
            // 多人：从当前主玩家连接拿真实地址端口（绝不写死）
            Connection main = mc.getConnection() != null ? mc.getConnection().getConnection() : null;
            if (main != null) {
                java.net.SocketAddress addr = main.getRemoteAddress();
                if (addr instanceof java.net.InetSocketAddress inet) {
                    host = inet.getHostString();
                    port = inet.getPort();
                }
            }
        }

        connectTo(host, port, null);
        if (singleplayer != null) {
            // 单机/局域网：共享主世界缓存目录（同服务器同维度共享 CachedWorld）
            this.serverKey = "singleplayer";
        }
    }

    /**
     * 发起到指定服务器的离线登录（普通连接与 transfer 跟随共用）。
     *
     * @param host          目标服务器地址
     * @param port          目标服务器端口
     * @param transferState 跟随传送时的 cookies（原版一致），普通连接传 null
     */
    public void connectTo(String host, int port, net.minecraft.client.multiplayer.TransferState transferState) {
        // 独立服务器（含 transfer 跟随）：独立缓存目录（同服务器同维度共享）
        this.serverKey = host + ":" + port;
        // 新连接开始时清除删除标记（transfer 跟随重连等合法路径）
        this.disposed = false;
        this.pendingTransfer = transferState;
        Thread thread = new Thread(() -> doConnectTcp(host, port), "Mockplayer Connector #" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** TCP 建连（网络线程，阻塞直到 TCP 建立） */
    private void doConnectTcp(String host, int port) {
        try {
            Optional<InetSocketAddress> resolved = ServerNameResolver.DEFAULT
                    .resolveAddress(new ServerAddress(host, port))
                    .map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isEmpty()) {
                LOG.warn("[{}] 无法解析服务器地址 {}", name, host);
                // DNS 失败同样走完整失败清理（不留 DISCONNECTED 僵尸条目）
                boolean wasReconnecting = this.reconnecting;
                this.reconnecting = false;
                if (wasReconnecting) {
                    com.mockplayer.session.SessionManager.getInstance().removeFakePlayer(name);
                } else {
                    notifyConnectFail("commands.mockplayer.newplayer.connection_failed");
                }
                return;
            }

            InetSocketAddress address = resolved.get();
            Connection conn = new Connection(PacketFlow.CLIENTBOUND);
            conn.setBandwidthLogger(Minecraft.getInstance().getDebugOverlay().getBandwidthLogger());
            this.connection = conn;
            // 必须在赋值后立即置 connected=true：否则登录期间（TCP 已建、connected 尚未置位）渲染线程
            // tick() 会走到 `connection != null && !connected` 分支误把 connection 置 null，导致连接断开。
            connected = true;
            FakeConnectionRegistry.markFake(conn, this);

            Connection.connect(address, net.minecraft.server.network.EventLoopGroupHolder.remote(Minecraft.getInstance().options.useNativeTransport()), conn)
                    .syncUninterruptibly();

            setupLoginAndHello(conn, address.getHostName(), address.getPort());
            FakeSession.LOG.info("[{}] 假人连接已建立，等待登录完成", name);
        } catch (Exception e) {
            LOG.error("[{}] 假人连接失败", name, e);
            disconnect();
            // 重连（transfer 跟随）失败：复位标志 + 就地下线（同 kick）
            boolean wasReconnecting = this.reconnecting;
            this.reconnecting = false;
            if (wasReconnecting) {
                com.mockplayer.session.SessionManager.getInstance().removeFakePlayer(name);
            } else {
                notifyConnectFail("commands.mockplayer.newplayer.connection_failed");
            }
        }
    }

    /** 登录握手公共部分：注册登录 listener + 发离线 hello 包 */
    private void setupLoginAndHello(Connection conn, String host, int port) {
        FakeLoginListener loginListener = new FakeLoginListener(this, conn, name);
        conn.initiateServerboundPlayConnection(
                host,
                port,
                LoginProtocols.SERVERBOUND,
                LoginProtocols.CLIENTBOUND,
                loginListener,
                false
        );

        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        conn.send(new ServerboundHelloPacket(name, offlineUuid));
    }

    /**
     * 驱动连接 tick（必须在渲染线程每 tick 调用），保持 keepalive/收发包。
     * 同时驱动假人 LocalPlayer 物理（重力/移动/碰撞 + 发移动包），反作弊合规。
     */
    public void tick() {
        // 假人专属 Baritone 事件驱动（2026-08-16 激进改造：不依赖 Baritone 全局
        // mixin，FakeSession 手动喂；顺序对齐官方 Minecraft.tick）：
        // Tick IN（PathingBehavior 处理路径命令 + 重建 bsi）→ PlayerUpdate PRE
        // （在假人 LocalPlayer.tick 之前）→ …物理… → PlayerUpdate POST → Tick OUT
        com.mockplayer.baritone.api.IBaritone botBaritone = this.botBaritone;
        // 当前驱动中的假人 baritone 实例（per-bot settings 与日志前缀
        // [baritone-mockplayer-<bot名>] 用；异步线程无上下文回退全局默认）
        com.mockplayer.baritone.api.utils.Helper.CURRENT_BOT.set(botBaritone);
        boolean feedBaritone = botBaritone != null && this.playListener != null && this.fakePlayer != null;
        if (feedBaritone) {
            int count = this.baritoneTickCount++;
            botBaritone.getGameEventHandler().onTick(
                    new com.mockplayer.baritone.api.event.events.TickEvent(
                            com.mockplayer.baritone.api.event.events.type.EventState.PRE,
                            com.mockplayer.baritone.api.event.events.TickEvent.Type.IN, count));
            botBaritone.getGameEventHandler().onPlayerUpdate(
                    new com.mockplayer.baritone.api.event.events.PlayerUpdateEvent(
                            com.mockplayer.baritone.api.event.events.type.EventState.PRE));
        }
        if (connection != null) {
            connection.tick();
        }
        // 被动断开（被踢/断线）后 connection 字段非 null 但 isConnected() 为 false：
        // Connection.tick() 内部检测 `!isConnected && !disconnectionHandled` →
        // handleDisconnection() → listener.onDisconnect()（2026-08-15 修复：
        // 之前只在 isConnected 时 tick，被动断开的 onDisconnect 永不派发，
        // 假人变僵尸直到其他路径清理；本地主动断开由 disconnect() 置 null 不受影响）。

        // 驱动 play listener 的 tick（父类 ClientPacketListener.tick 的收尾逻辑；
        // 假人不用 LevelLoadTracker，chunk 就绪恢复物理由 handleLevelChunkWithLight 处理）
        if (this.playListener != null) {
            try {
                this.playListener.tick();
            } catch (Exception e) {
                LOG.error("[{}] 假人 listener tick 出错", name, e);
            }
        }

        // entityculling 兼容：重置假人实体剔除标记（详见 resetFakePlayerCulling 注释），
        // 必须在假人 level 实体 tick 之前执行，否则 tickEntity 会把假人判定为相机外并跳过完整 tick
        this.resetFakePlayerCulling();

        // 驱动假人 level 实体 tick（复用原版 ClientLevel.tickEntities）：
        // 假人 LocalPlayer 物理 + RemotePlayer/生物插值全部按原版推进，不手写位置同步
        if (this.getFakeLevel() != null) {
            try {
                this.getFakeLevel().tickEntities();
            } catch (Exception e) {
                LOG.error("[{}] 假人 level 实体 tick 出错", name, e);
            }
        }
        // PlayerUpdate POST（官方 MixinMinecraft.postUpdateEntities 等价：实体 tick 完成后）
        if (feedBaritone) {
            botBaritone.getGameEventHandler().onPlayerUpdate(
                    new com.mockplayer.baritone.api.event.events.PlayerUpdateEvent(
                            com.mockplayer.baritone.api.event.events.type.EventState.POST));
        }

        // 同步假人位置/着地状态到 FakePlayerState（铁律：假人状态不丢位置信息；
        // 本地预测位置与服务端权威一致，覆盖移动/传送/重生后的最新值）
        if (this.fakePlayer != null) {
            this.state.setPosition(this.fakePlayer.getX(), this.fakePlayer.getY(), this.fakePlayer.getZ());
            this.state.setOnGround(this.fakePlayer.onGround());
        }

        // 驱动 Bot：应用持续动作输入 + 派发 onTick/onMove 事件
        if (this.bot != null) {
            try {
                this.bot.tick();
            } catch (Exception e) {
                LOG.error("[{}] 假人 bot tick 出错", name, e);
            }
        }
        // Tick OUT（官方 MixinMinecraft.postRunTick 等价：tick 末尾）
        if (feedBaritone) {
            botBaritone.getGameEventHandler().onPostTick(
                    new com.mockplayer.baritone.api.event.events.TickEvent(
                            com.mockplayer.baritone.api.event.events.type.EventState.POST,
                            com.mockplayer.baritone.api.event.events.TickEvent.Type.IN,
                            this.baritoneTickCount - 1));
        }
        com.mockplayer.baritone.api.utils.Helper.CURRENT_BOT.remove();
    }

    /**
     * entityculling 兼容（2026-08-14 实测定位）：
     * entityculling 会把「不在相机内」的实体 cancel 掉原版完整 tick，只跑 basicTick
     * （setOldPosAndRot + tickCount++ + aiStep + hurtTime）。假人实体活在假人自己的 level，
     * 无头设计下永远不会被任何渲染器渲染，剔除标记（outOfCamera/culled）永远不会被渲染侧清除，
     * 于是假人完整 tick（输入/物理/移动/发包）被永久跳过 → 假人不能移动/转头，但挥手/背包
     * 等网络事件驱动功能正常。这里在假人 level 实体 tick 之前把标记重置为 false，
     * 让 entityculling 判定永远走「仅设标记不 cancel」的正常分支。
     *
     * 输入：假人实体（fakePlayer，可能为 null 表示尚未进 play）。
     * 输出：无；副作用是每 tick 反射调用 entityculling 的 Cullable#setOutOfCamera(false)/
     * setCulled(false)（entityculling 未安装时只做一次反射查找，失败后零开销跳过）。
     */
    private static boolean cullingApiChecked;
    private static Class<?> cullableClass;
    private static java.lang.reflect.Method cullingSetOutOfCamera;
    private static java.lang.reflect.Method cullingSetCulled;

    private void resetFakePlayerCulling() {
        net.minecraft.client.player.LocalPlayer player = this.fakePlayer;
        if (player == null) {
            return;
        }
        if (!cullingApiChecked) {
            cullingApiChecked = true;
            try {
                cullableClass = Class.forName("dev.tr7zw.entityculling.versionless.access.Cullable");
                cullingSetOutOfCamera = cullableClass.getMethod("setOutOfCamera", boolean.class);
                cullingSetCulled = cullableClass.getMethod("setCulled", boolean.class);
            } catch (ReflectiveOperationException e) {
                // entityculling 未安装：保留 null，后续检查失败直接跳过，不影响假人
            }
        }
        if (cullableClass == null || cullingSetOutOfCamera == null || cullingSetCulled == null) {
            return;
        }
        try {
            if (cullableClass.isInstance(player)) {
                cullingSetOutOfCamera.invoke(player, false);
                cullingSetCulled.invoke(player, false);
            }
        } catch (ReflectiveOperationException ignored) {
            // 反射失败不影响假人运行，下一 tick 重试
        }
    }

    /**
     * 断开假人连接。
     * 注意：reconnecting 时只断连接，session 由重连成功/失败决定去留（transfer 跟随场景）。
     */
    public void disconnect() {
        // 删除标记：非重连断开 = 会话终结，in-flight 登录/配置必须放弃
        if (!this.reconnecting) {
            this.disposed = true;
        }
        if (connection != null) {
            net.minecraft.client.multiplayer.ClientLevel lvl = this.getFakeLevel();
            com.mockplayer.session.FakeLevelRegistry.unregisterFakeLevel(lvl);
            FakeConnectionRegistry.unmarkFake(connection);
            connection.disconnect(net.minecraft.network.chat.Component.translatable("disconnect.mockplayer.fake_player_removed"));
            // 注意：不再手动调 connection.handleDisconnection()。
            // Connection.tick() 会在 channel 关闭后自动触发一次（disconnectionHandled 幂等），
            // 手动再调会造成 "handleDisconnection() called twice" 噪音警告。
            connection = null;
        }
        connected = false;
        // 删除路径主动释放引用：断开后不再需要 listener/player/transfer 状态，
        // 置空并清空 state，避免 session 被任何残留引用持有时把整个假人世界带活。
        // 注意：不能置空 bot（BotManagerImpl.removeBot 断开后还要 fire onDisconnected），
        // 也不能置空 onConnectFail（连接失败路径先 disconnect 后 notifyConnectFail 派发）。
        if (!this.reconnecting) {
            this.playListener = null;
            this.fakePlayer = null;
            this.profile = null;
            this.pendingTransfer = null;
            this.state.clear();
            // 销毁假人专属 Baritone 实例（重新登录时 handleLogin 重建）
            com.mockplayer.baritone.api.IBaritone toDestroy = this.botBaritone;
            this.botBaritone = null;
            if (toDestroy != null) {
                // 保存并释放世界缓存（无其他实例引用时从静态 map 移除）
                toDestroy.closeWorldCache();
                com.mockplayer.baritone.api.BaritoneAPI.getProvider().destroyBaritone(toDestroy);
            }
            this.navigateOverrides.clear();
        }
        LOG.info("[{}] 假人已移除", name);
    }

    public String getName() {
        return name;
    }

    /** 假人连接的服务器标识（Baritone 缓存目录键；"singleplayer" = 本机单机/局域网）。 */
    public String getServerKey() {
        return this.serverKey;
    }

    public boolean isConnected() {
        return connected && connection != null && connection.isConnected();
    }
}
