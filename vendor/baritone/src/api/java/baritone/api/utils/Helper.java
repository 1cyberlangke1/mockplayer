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

package com.mockplayer.baritone.api.utils;

import com.mockplayer.baritone.api.BaritoneAPI;
import com.mockplayer.baritone.api.Settings;
import com.mockplayer.baritone.api.IBaritone;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Arrays;
import java.util.Calendar;
import java.util.stream.Stream;

/**
 * An ease-of-access interface to provide the {@link Minecraft} game instance,
 * chat and console logging mechanisms, and the Baritone chat prefix.
 *
 * @author Brady
 * @since 8/1/2018
 */
public interface Helper {

    /** 当前正在驱动 baritone 的假人实例（FakeSession.tick 设置/清除；per-bot settings 与日志前缀用）。 */
    java.lang.ThreadLocal<IBaritone> CURRENT_BOT = new java.lang.ThreadLocal<>();

    /** baritone 日志统一走 mockplayer 通道（玩家聊天不打扰，信息进 mod 日志可查）。 */
    org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mockplayer-baritone");

    /**
     * Instance of {@link Helper}. Used for static-context reference.
     */
    Helper HELPER = new Helper() {};

    /**
     * The main game instance returned by {@link Minecraft#getInstance()}.
     * Deprecated since {@link IPlayerContext#minecraft()} should be used instead (In the majority of cases).
     */
    @Deprecated
    Minecraft mc = Minecraft.getInstance();

    /**
     * The tag to assign to chat messages when {@link Settings#useMessageTag} is {@code true}.
     */
    GuiMessageTag MESSAGE_TAG = new GuiMessageTag(0xFF55FF, null, Component.literal("Baritone message."), "Baritone");

    static Component getPrefix() {
        // Inner text component
        final Calendar now = Calendar.getInstance();
        final boolean xd = now.get(Calendar.MONTH) == Calendar.APRIL && now.get(Calendar.DAY_OF_MONTH) <= 3;
        MutableComponent baritone = Component.literal(xd ? "Baritoe" : BaritoneAPI.getSettings().shortBaritonePrefix.value ? "B" : "Baritone");
        baritone.setStyle(baritone.getStyle().withColor(ChatFormatting.LIGHT_PURPLE));

        // Outer brackets
        MutableComponent prefix = Component.literal("");
        prefix.setStyle(baritone.getStyle().withColor(ChatFormatting.DARK_PURPLE));
        prefix.append("[");
        prefix.append(baritone);
        prefix.append("]");

        return prefix;
    }

    /** 当前驱动中假人的 baritone settings（per-instance；异步线程无上下文时回退全局默认）。 */
    default Settings currentSettings() {
        IBaritone b = Helper.CURRENT_BOT.get();
        return b != null ? b.settings() : BaritoneAPI.getSettings();
    }

    /** 当前驱动中假人的玩家名（baritone 实例 player 派生；无上下文时 null）。 */
    default String currentBotName() {
        IBaritone b = Helper.CURRENT_BOT.get();
        if (b == null || b.getPlayerContext() == null || b.getPlayerContext().player() == null) {
            return null;
        }
        return b.getPlayerContext().player().getName().getString();
    }

    /**
     * Send a message to display as a toast popup
     *
     * @param title   The title to display in the popup
     * @param message The message to display in the popup
     */
    default void logToast(Component title, Component message) {
        LOGGER.debug("[baritone-toast] {}", message.getString());
    }

    /**
     * Send a message to display as a toast popup
     *
     * @param title   The title to display in the popup
     * @param message The message to display in the popup
     */
    default void logToast(String title, String message) {
        logToast(Component.literal(title), Component.literal(message));
    }

    /**
     * Send a message to display as a toast popup
     *
     * @param message The message to display in the popup
     */
    default void logToast(String message) {
        logToast(Helper.getPrefix(), Component.literal(message));
    }

    /**
     * Send a message as a desktop notification
     *
     * @param message The message to display in the notification
     */
    default void logNotification(String message) {
        logNotification(message, false);
    }

    /**
     * Send a message as a desktop notification
     *
     * @param message The message to display in the notification
     * @param error   Whether to log as an error
     */
    default void logNotification(String message, boolean error) {
        if (currentSettings().desktopNotifications.value) {
            logNotificationDirect(message, error);
        }
    }

    /**
     * Send a message as a desktop notification regardless of desktopNotifications
     * (should only be used for critically important messages)
     *
     * @param message The message to display in the notification
     */
    default void logNotificationDirect(String message) {
        logNotificationDirect(message, false);
    }

    /**
     * Send a message as a desktop notification regardless of desktopNotifications
     * (should only be used for critically important messages)
     *
     * @param message The message to display in the notification
     * @param error   Whether to log as an error
     */
    default void logNotificationDirect(String message, boolean error) {
        LOGGER.debug("[baritone-notification] {}", message);
    }

    /**
     * Send a message to chat only if chatDebug is on
     *
     * @param message The message to display in chat
     */
    default void logDebug(String message) {
        if (!currentSettings().chatDebug.value) {
            //System.out.println("Suppressed debug message:");
            //System.out.println(message);
            return;
        }
        // We won't log debug chat into toasts
        // Because only a madman would want that extreme spam -_-
        logDirect(message, false);
    }

    /**
     * Send components to chat with the [Baritone] prefix
     *
     * @param logAsToast Whether to log as a toast notification
     * @param components The components to send
     */
    default void logDirect(boolean logAsToast, Component... components) {
        // 状态消息：logToChat 开关（默认关）；开 → 聊天（前缀+颜色），关 → debug 日志
        if (currentSettings().logToChat.value) {
            pushToChat(components);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Component component : components) {
            sb.append(component.getString());
        }
        LOGGER.debug("[baritone] {}", sb);
    }

    /**
     * 状态消息 + 指定颜色（如异常红色）。
     */
    default void logDirect(Component component, ChatFormatting color) {
        logDirect(false, component.copy().withStyle(color));
    }

    /**
     * Send components to chat with the [Baritone] prefix
     *
     * @param components The components to send
     */
    default void logDirect(Component... components) {
        logDirect(currentSettings().logAsToast.value, components);
    }

    /**
     * 调试消息（Component 版）：chatDebug 开 + logDebugToChat 开 → 聊天；否则 debug 日志。
     */
    default void logDebug(Component... components) {
        if (!currentSettings().chatDebug.value) {
            return;
        }
        if (currentSettings().logDebugToChat.value) {
            pushToChat(components);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Component component : components) {
            sb.append(component.getString());
        }
        LOGGER.debug("[baritone-debug] {}", sb);
    }

    /** verbose 级（鞘翅细节）：并入调试通道。 */
    default void logVerbose(Component... components) {
        logDebug(components);
    }

    /** 通知（Component 版）：原桌面通知保持；logNotificationToChat 开 → 同时进聊天。 */
    default void logNotification(Component message) {
        logNotification(message, false);
    }

    default void logNotification(Component message, boolean error) {
        if (currentSettings().desktopNotifications.value) {
            logNotificationDirect(message.getString(), error);
        }
        if (currentSettings().logNotificationToChat.value) {
            pushToChat(new Component[]{message});
        }
    }

    /** Toast（Component 版）：原 toast 保持；logToastToChat 开 → 同时进聊天。 */
    default void logToast(Component message) {
        logToast(Helper.getPrefix(), message);
        if (currentSettings().logToastToChat.value) {
            pushToChat(new Component[]{message});
        }
    }

    /**
     * 推送到主玩家聊天：前缀 [baritone-mockplayer-<bot名>] + 按消息性质着色。
     */
    default void pushToChat(Component... components) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        String bot = currentBotName();
        MutableComponent prefix = Component.literal(
                "[baritone-mockplayer-" + (bot != null ? bot : "?") + "] ")
                .withStyle(ChatFormatting.AQUA);
        MutableComponent message = Component.literal("");
        for (Component component : components) {
            message.append(component.copy());
        }
        message.setStyle(message.getStyle().withColor(colorFor(message)));
        minecraft.player.sendSystemMessage(prefix.append(message));
    }

    /** 按 translatable key（fallback 文本）推断颜色：失败/取消/异常红，完成/到达绿，其余黄。 */
    default ChatFormatting colorFor(Component message) {
        String key = null;
        if (message.getContents() instanceof TranslatableContents tc) {
            key = tc.getKey();
        }
        String hay = (key != null ? key : message.getString()).toLowerCase(java.util.Locale.ROOT);
        if (hay.contains("no_") || hay.contains("failed") || hay.contains("cancel")
                || hay.contains("exception") || hay.contains("timeout") || hay.contains("illegal")
                || hay.contains("unable") || hay.contains("失败") || hay.contains("取消")
                || hay.contains("异常") || hay.contains("无法")) {
            return ChatFormatting.RED;
        }
        if (hay.contains("done") || hay.contains("complete") || hay.contains("arrived")
                || hay.contains("landed") || hay.contains("完成") || hay.contains("到达")) {
            return ChatFormatting.GREEN;
        }
        return ChatFormatting.YELLOW;
    }

    /**
     * Send a message to chat regardless of chatDebug (should only be used for critically important messages, or as a
     * direct response to a chat command)
     *
     * @param message    The message to display in chat
     * @param color      The color to print that message in
     * @param logAsToast Whether to log as a toast notification
     */
    default void logDirect(String message, ChatFormatting color, boolean logAsToast) {
        Stream.of(message.split("\n")).forEach(line -> {
            MutableComponent component = Component.literal(line.replace("\t", "    "));
            component.setStyle(component.getStyle().withColor(color));
            logDirect(logAsToast, component);
        });
    }

    /**
     * Send a message to chat regardless of chatDebug (should only be used for critically important messages, or as a
     * direct response to a chat command)
     *
     * @param message The message to display in chat
     * @param color   The color to print that message in
     */
    default void logDirect(String message, ChatFormatting color) {
        logDirect(message, color, currentSettings().logAsToast.value);
    }

    /**
     * Send a message to chat regardless of chatDebug (should only be used for critically important messages, or as a
     * direct response to a chat command)
     *
     * @param message    The message to display in chat
     * @param logAsToast Whether to log as a toast notification
     */
    default void logDirect(String message, boolean logAsToast) {
        logDirect(message, ChatFormatting.GRAY, logAsToast);
    }

    /**
     * Send a message to chat regardless of chatDebug (should only be used for critically important messages, or as a
     * direct response to a chat command)
     *
     * @param message The message to display in chat
     */
    default void logDirect(String message) {
        logDirect(message, currentSettings().logAsToast.value);
    }

    default void logUnhandledException(final Throwable exception) {
        HELPER.logDirect(Component.translatableEscape("baritone.log.misc.unhandled_exception"), ChatFormatting.RED);
        exception.printStackTrace();
    }
}
