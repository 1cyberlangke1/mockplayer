# Mockplayer

纯客户端实现的 Minecraft 假人（Fake Player）模组，支持 Fabric 与 NeoForge 双端。

## 简介

在 offline-mode 服务器上创建与真玩家零区别的假人：每个假人拥有独立的会话、连接与状态，
服务端视角就是普通玩家。支持通过命令遥控假人的移动、攻击、交互、挖掘、容器、骑乘、聊天，
以及查询假人状态和监听事件；内置假人控制台 GUI（Fabric / NeoForge 双端一致）。

## 当前状态

当前可能存在不稳定情况，请按需使用。

## 可选依赖

本模组核心功能不依赖任何第三方模组，不装也能完整使用（配置直接改
`config/mockplayer.json`）。

- [YACL（YetAnotherConfigLib）](https://modrinth.com/mod/yacl)：图形化配置界面。
  装有 YACL 时可在模组列表的「配置」页面修改全部设置并即时生效；
  Fabric 端还需要 [Mod Menu](https://modrinth.com/mod/modmenu) 作为配置入口。

## 功能

- `/newplayer <name>`：创建假人并加入当前服务器
- `/delplayer <name>`：移除假人
- `/newplayer batch <prefix> <count> [--dry]` / `/delplayer batch <prefix|all> [--dry]`：
  批量创建/移除假人（性能测试用，`--dry` 只预览不执行）
- `/connect <name> <host> [port]`：让已存在的假人连接指定服务器
- `/control <player> <action>`：遥控假人
  - 用 `/control <player> help` 查看全部动作列表
  - 动作：移动、跳跃、潜行、疾跑、攻击、交互、使用物品、挖掘/放置方块、容器交互、
    骑乘/解除骑乘、聊天/执行命令、睡觉、重生、写书/告示牌、信标、物品改名、区块加载半径等
- 射线交互（沿假人视线）：`attackLook`/`useLook` 单点；
  `sustainedAttackLook`/`sustainedUseLook` 持续、`stopSustained` 停止（按住键的原版等效，
  主手无使用动画时自动 fallback 副手，如剑 + 副手盾举盾）
- `/query <player> <query>`：查询假人状态（与 `/control` 分离，不改变假人状态）
  - 查询：`list`、`info`、`inventory`、`container`、`near [r]`、`block x y z`、
    `online`、`chatlog`、`memory`、`chunk`（区块加载半径）
  - 监听：`/query <player> listen on/off` 实时推送假人事件，
    `/query <player> events [n]` 查看最近事件缓存
  - `memory`：JVM 堆为真实值，Mod 侧跟踪字节为精确记账，原版世界内部报实体/区块数

## GUI 假人控制台

- 默认按 `G` 打开（可在配置里改键或禁用）
- 左栏：假人滑条选单（假人多时拖动/滚轮滚动）+ 底部名字输入框与新建/删除
- 状态：原版血量/饥饿/经验条（含盔甲、吸收、闪烁）、位置、速度、内存、区块加载半径、
  选中槽、当前容器、自动重生、运行状态
- 背包：假人完整背包（装备/副手图标、物品数量、悬停信息）；左键拿起/放下、右键切换
  快捷栏选中槽，与容器互通；副手右侧红色丢弃格：物品放入即丢弃（原版点背包外等价，
  左键整组/右键 1 个）；开容器时显示容器槽 + 玩家背包，左上 `×` 关闭
- 动作：视线/移动十字方向键（按住连续）、移动开关、左键/右键（默认单击单点、按住持续，
  副手盾可举盾；在按钮上右键可切 长按/连点 模式：长按 = 左键开关持续动作，连点 =
  左键按主手蓄力满攻击一次 / 右键每 1 秒一次，激活时文字变红且不可切换模式）、
  区块加载半径 +/-、重生/自动重生、聊天、附近实体快捷看向
- 界面半透明、多分辨率自适应、当前 Tab 高亮；所有开关按钮开启绿字/关闭红字

## F3 调试标签

开启调试信息（默认 F3）时，每个假人名字下方会显示该假人的内存、血量、饱食度、速度、
区块加载半径、经验与当前容器等信息（可在配置中关闭）。

## 命令参考（部分动作语义）

- `useItemOn <x> <y> <z> <side>`：对着方块指定面使用物品（打开箱子/炉子等容器的底层操作）
- `placeBlock <x> <y> <z> <side>`：手持方块对着指定面放置
- `interact <target>`：右键实体（村民交易、喂食、骑乘等）
- `mount [target|anything]`：`mount` 自动骑最近可骑乘实体；`mount anything` 骑任意实体；
  `mount <target>` 骑指定实体（类型 id 或名字）
- `dismount`：解除骑乘（按住潜行约 0.5 秒的原版等效行为）
- 容器交互：`click <slot> <button> <mode>` 点击槽位、`setSlot <slot>` 写入槽位、
  `button <id>` 点菜单按钮、`trade <index>` 选交易、`close` 关闭容器
- `setBeacon <primary> [secondary]`：需要先 `click` 把支付物品
  （金锭/铁锭/钻石/绿宝石）放入信标菜单槽 0，否则服务端拒绝激活

## 配置

所有根命令名（`control`/`query`/`newplayer`/`delplayer`/`connect`）都可以在
`config/mockplayer.json` 的 `commands` 对象里改名，与其他模组冲突时直接改配置：

```json
{
  "commands": {
    "control": "control",
    "query": "query",
    "newplayer": "newplayer",
    "delplayer": "delplayer",
    "connect": "connect"
  }
}
```

- 命令名留空 = 禁用该命令
- 非法值（空格/超长/非法字符）或启用项之间重名会自动回退默认名
- `guiEnabled`：控制台总开关（默认 true）；`guiKeyName`：打开按键（默认 `key.keyboard.g`，
  留空禁用）
- `fakePlayerChunkRadius`：新建假人的默认区块加载半径（默认 2，范围 1-32；
  每个假人可独立调整，与主玩家完全隔离）
- 其余设置（聊天/音效/粒子日志保留条数、事件缓存与采样参数等）同样在
  `config/mockplayer.json` 或 GUI 中修改
- 改完 JSON 后重进游戏生效

## 环境

- Minecraft 26.2
- Fabric 或 NeoForge 客户端
- offline-mode（离线模式）服务器

## 感谢

- [Carpet](https://github.com/gnembon/fabric-carpet)（假人遥控与事件思路）
- [Baritone](https://github.com/cabaletta/baritone)（寻路引擎，LGPL-3.0）——感谢 leijurv、
  Brady 及所有贡献者的杰出工作；本项目集成了 Baritone 官方源码（`vendor/baritone/`），
  使用与修改均遵循 LGPL-3.0，详见 [NOTICE](NOTICE)。
- [nether-pathfinder](https://github.com/babbaj/nether-pathfinder)（鞘翅寻路原生库，
  babbaj 出品）——本项目 vendor 其 C++/Java 源码（`vendor/nether-pathfinder/`）并修复了
  native 边界崩溃（`findAir` 无解时 `exit(1)` 杀 JVM → 改为返回无解走 Java 侧优雅失败）；
  ⚠️ 上游仓库未声明许可证（默认 All rights reserved），如需分发请联系作者授权。
- [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template)（双端工程模板）
- [Fabric API](https://github.com/FabricMC/fabric)
- [NeoForge](https://github.com/neoforged/NeoForge)
- [Mixin](https://github.com/SpongePowered/Mixin)

## 免责声明

本模组与 Mojang Studios / Microsoft 无关，未经官方认可。
使用本模组请遵守 Minecraft 最终用户许可协议（EULA）。
本模组不包含任何 Minecraft 原版代码或资源。

## 维护约定

- **不简化区**：`FakePlayListener` 的逐包 handler（零丢数据铁律）、`BotActionsImpl` 的原版等价输入链路、
  测试强断言——这三块禁止为减行砍逻辑；只能拆类/抽常量，任何「看起来能省」的改动先列等价性说明。
- **逃生舱纪律**：公共 API 的逃生舱（`Bot.getLocalPlayer/getGameMode/getLevel`、`BotContainer.raw()`）
  只加不改；新增能力优先走 `BotActions`/`BotContainer` 特化；新增逃生舱需在提交说明登记理由。
- **批量默认并发 1**：Fabric 客户端全局配置 addon 是单例，高并发批量创建假人可能触发竞态；
  `concurrency` 参数保留，自行评估后使用。

## 协议

本仓库为**双许可**：

- Mockplayer 自身代码：MIT License（见 [LICENSE](LICENSE)）

  Copyright (c) 2026 1cyberlangke1

- `vendor/baritone/`（Baritone 官方源码及其修改）：GNU Lesser General Public License v3.0
  （见 [NOTICE](NOTICE) 与 `vendor/baritone/LICENSE`）
- `vendor/nether-pathfinder/`（babbaj/nether-pathfinder 源码及其修改）：上游未声明许可证
  （默认保留所有权利；分发前需取得作者授权），修改点见上方「感谢」说明

Baritone / nether-pathfinder 部分与 Mockplayer 的 MIT 代码在目录上严格分离，互不传染。

## AI 生成声明

本项目的代码由大语言模型（LLM）辅助生成。
