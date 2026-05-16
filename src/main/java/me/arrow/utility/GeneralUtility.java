package me.arrow.utility;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;

public final class GeneralUtility {

    private GeneralUtility() {
    }

    public static void log(String info) {
        if (info == null) {
            info = "";
        }
        ConsoleCommandSender sender = Bukkit.getConsoleSender();
        if (sender != null) {
            sender.sendMessage(info);
        }
    }

    public static String translate(String source) {
        if (source == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes('&', source);
    }
}