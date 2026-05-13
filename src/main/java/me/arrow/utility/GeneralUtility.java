package me.arrow.utility;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public final class GeneralUtility {

    private GeneralUtility() {
    }

    public static void log(String info) {
        Bukkit.getConsoleSender().sendMessage(info == null ? "" : info);
    }

    public static String translate(String source) {
        if (source == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes('&', source);
    }
}