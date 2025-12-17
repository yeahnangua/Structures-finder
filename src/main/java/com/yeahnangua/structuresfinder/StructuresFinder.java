package com.yeahnangua.structuresfinder;

import com.yeahnangua.structuresfinder.commands.FindStructureCommand;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class StructuresFinder extends JavaPlugin {

    private static StructuresFinder instance;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config if not exists
        saveDefaultConfig();

        getLogger().info("StructuresFinder has been enabled!");

        // Register command
        FindStructureCommand command = new FindStructureCommand();
        getCommand("findstructure").setExecutor(command);
        getCommand("findstructure").setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        getLogger().info("StructuresFinder has been disabled!");
    }

    public static StructuresFinder getInstance() {
        return instance;
    }

    /**
     * Gets a config string with color codes translated.
     */
    public String getConfigString(String path) {
        String value = getConfig().getString(path, "");
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    /**
     * Gets a config string list with color codes translated.
     */
    public List<String> getConfigStringList(String path) {
        return getConfig().getStringList(path).stream()
                .map(s -> ChatColor.translateAlternateColorCodes('&', s))
                .toList();
    }

    /**
     * Reloads the plugin configuration.
     */
    public void reloadPluginConfig() {
        reloadConfig();
    }
}
