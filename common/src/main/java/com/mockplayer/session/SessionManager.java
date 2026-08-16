package com.mockplayer.session;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;

/**
 * 会话管理器（向后兼容的薄委托层）。
 *
 * 生命周期/权限逻辑全部在 {@link BotManagerImpl}（即 {@link MockplayerApi#bots()}）。
 * 这里保留平台/Mixin/命令需要的最小门面（tick/clearAll/getSession/removeFakePlayer），
 * 并完成 MockplayerApi 初始化；假人 level 隔离注册表已独立到 {@link FakeLevelRegistry}。
 */
public class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private final BotManagerImpl manager = new BotManagerImpl();

    private SessionManager() {
        MockplayerApi.init(this.manager);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * 移除假人（命令入口，特权）。
     *
     * @param name 假人名字
     * @return true 移除成功，false 不存在
     */
    public boolean removeFakePlayer(String name) {
        return this.manager.removeBot(name, BotManagerImpl.COMMAND_OWNER) == RemoveResult.REMOVED;
    }

    /**
     * 驱动所有假人连接 tick（渲染线程每 tick 调用）。
     */
    public void tick() {
        this.manager.tick();
        BatchCommands.tick(); // 批量创建队列（tick 驱动）
        // 渲染闸门注册（幂等一次）：PathRenderer 每帧实时判定三态，无需每 tick 同步
        NavigateSupport.ensureRenderGate();
    }

    /**
     * 全部假人下线（主玩家退出服务器时调用）。
     */
    public void clearAll() {
        // 批量创建任务必须一并停止：否则回菜单后 tick 仍在驱动队列继续建假人
        BatchCommands.cancel();
        this.manager.clearAll();
    }

    /**
     * 按名字取底层会话（内部使用）。
     */
    public FakeSession getSession(String name) {
        return this.manager.getSession(name);
    }

    /** 当前在线假人名字（寻路配置批量应用/渲染同步用）。 */
    public java.util.Collection<String> getSessionNames() {
        return this.manager.getFakePlayerNames();
    }

    /** 假人粒子：记录到对应假人 state，不加入主玩家 particleEngine。 */
    public static void recordFakeParticle(net.minecraft.client.multiplayer.ClientLevel level,
                                          net.minecraft.core.particles.ParticleOptions particle,
                                          double x, double y, double z) {
        for (String name : INSTANCE.manager.getFakePlayerNames()) {
            FakeSession session = INSTANCE.getSession(name);
            if (session != null && session.getFakeLevel() == level) {
                // ParticleType 不重写 toString()，直接 toString 是类名垃圾；用注册表 key（minecraft:block）
                net.minecraft.resources.Identifier key =
                        net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType());
                session.getState().recordParticle(key != null ? key.toString() : particle.getType().toString(), x, y, z);
                return;
            }
        }
    }

    /** 假人音效：记录到对应假人 state，不播放到主玩家 SoundManager（2001 等 level 事件链路）。 */
    public static void recordFakeSound(net.minecraft.client.multiplayer.ClientLevel level,
                                       String description, double x, double y, double z) {
        for (String name : INSTANCE.manager.getFakePlayerNames()) {
            FakeSession session = INSTANCE.getSession(name);
            if (session != null && session.getFakeLevel() == level) {
                session.getState().recordSound(description, x, y, z);
                return;
            }
        }
    }
}
