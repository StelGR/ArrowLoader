package me.arrow.command;

import lombok.NonNull;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import me.arrow.Core;


public class UpdateCommand implements CommandExecutor {
    private final Core loader;

    public UpdateCommand(Core loader) {
        this.loader = loader;
    }

    @Override
    public boolean onCommand(CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        if (!sender.hasPermission("arrow.admin")) {
            sender.sendMessage(ChatColor.RED + "You lack permission to use this command.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Starting Arrow core reload…");
        loader.reloadCore(sender);
        sender.sendMessage(ChatColor.GREEN + "Arrow core reload complete.");

        return true;
    }
}



