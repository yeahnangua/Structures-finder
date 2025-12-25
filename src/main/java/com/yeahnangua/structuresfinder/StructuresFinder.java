package com.yeahnangua.structuresfinder;

import com.yeahnangua.structuresfinder.cache.ExplorerMapCache;
import com.yeahnangua.structuresfinder.commands.FindStructureCommand;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class StructuresFinder extends JavaPlugin {

    private static StructuresFinder instance;
    private ExplorerMapCache mapCache;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config if not exists
        saveDefaultConfig();

        getLogger().info("StructuresFinder has been enabled!");

        // Initialize cache system
        mapCache = new ExplorerMapCache(this);
        mapCache.loadFromDisk();

        // Register command
        FindStructureCommand command = new FindStructureCommand();
        getCommand("findstructure").setExecutor(command);
        getCommand("findstructure").setTabCompleter(command);

        // Initialize missing caches after server is fully loaded
        getServer().getScheduler().runTaskLater(this, () -> {
            getLogger().info("Initializing explorer map cache...");
            mapCache.initializeAll();
        }, 40L); // 2 seconds delay
    }

    @Override
    public void onDisable() {
        getLogger().info("StructuresFinder has been disabled!");
    }

    public static StructuresFinder getInstance() {
        return instance;
    }

    public ExplorerMapCache getMapCache() {
        return mapCache;
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

    /**
     * Checks if explorer map style rendering is enabled.
     */
    public boolean isExplorerMapStyleEnabled() {
        return getConfig().getBoolean("explorer-map-style.enabled", true);
    }

    /**
     * Gets the sampling resolution for terrain detection.
     * Higher value = faster but less precise.
     */
    public int getSampleResolution() {
        int resolution = getConfig().getInt("explorer-map-style.sample-resolution", 4);
        // Clamp to reasonable values (1-16)
        return Math.max(1, Math.min(16, resolution));
    }

    /**
     * Gets the list of water biome keywords for fuzzy matching.
     */
    public List<String> getWaterBiomeKeywords() {
        return getConfig().getStringList("explorer-map-style.water-biomes.keywords");
    }

    /**
     * Gets the list of exact water biome names for precise matching.
     */
    public List<String> getWaterBiomeExact() {
        return getConfig().getStringList("explorer-map-style.water-biomes.exact");
    }
}
