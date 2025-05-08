package com.VeloDynamic.backend;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

public class VeloDynamicBackend extends JavaPlugin {

    private FileConfiguration config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        // Register /dynamic only if enabled in config
        if (config.getBoolean("command-enabled", true)) {
            PluginCommand cmd = this.getCommand("dynamic");
            if (cmd != null) {
                cmd.setExecutor(this::onCommand);
            }
        }

        getLogger().info("VeloDynamicBackend enabled.");
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(config.getString("command-permission", "velodynamic.command"))) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        sender.sendMessage("§aThis server is connected to VeloDynamic Proxy!");
        // You could implement remote logic or plugin message responses here
        return true;
    }
}
