package com.mockplayer.api.navigate;

/**
 * 当前寻路任务类型（命令/API 查询用）。
 *
 * i18n key：commands.mockplayer.navigate.task.&lt;name&gt;
 */
public enum NavigatorTask {
    /** 无任务。 */
    NONE,
    /** 走到指定方块格。 */
    GO_TO,
    /** 靠近指定坐标半径内。 */
    GO_NEAR,
    /** 跟随实体。 */
    FOLLOW,
    /** 挖矿（Baritone MineProcess）。 */
    MINE,
    /** 鞘翅飞行。 */
    ELYTRA
}
