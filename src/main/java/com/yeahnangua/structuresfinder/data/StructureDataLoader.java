package com.yeahnangua.structuresfinder.data;

import com.yeahnangua.structuresfinder.StructuresFinder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Loads structure data from BetterStructures plugin files.
 */
public class StructureDataLoader {

    private static final String BS_DATA_PATH = "plugins/BetterStructures/structure_locations";

    /**
     * Gets all available worlds that have structure data.
     */
    public static List<String> getAvailableWorlds() {
        File dataFolder = new File(BS_DATA_PATH);
        if (!dataFolder.exists() || !dataFolder.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return Collections.emptyList();
        }

        List<String> worlds = new ArrayList<>();
        for (File file : files) {
            worlds.add(file.getName().replace(".yml", ""));
        }
        return worlds;
    }

    /**
     * Loads all structures from a specific world.
     */
    public static List<StructureData> loadStructures(String worldName) {
        File worldFile = new File(BS_DATA_PATH, worldName + ".yml");
        if (!worldFile.exists()) {
            return Collections.emptyList();
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(worldFile);
        ConfigurationSection structuresSection = config.getConfigurationSection("structures");

        if (structuresSection == null) {
            return Collections.emptyList();
        }

        List<StructureData> structures = new ArrayList<>();

        for (String key : structuresSection.getKeys(false)) {
            ConfigurationSection locationSection = structuresSection.getConfigurationSection(key);
            if (locationSection == null) continue;

            int x = locationSection.getInt("x");
            int y = locationSection.getInt("y");
            int z = locationSection.getInt("z");
            String schematic = locationSection.getString("schematic", "unknown");
            String type = locationSection.getString("type", "UNDEFINED");
            boolean cleared = locationSection.getBoolean("cleared", false);

            structures.add(new StructureData(worldName, x, y, z, schematic, type, cleared));
        }

        return structures;
    }

    /**
     * Loads structures of a specific type from a world.
     */
    public static List<StructureData> loadStructuresByType(String worldName, String structureType) {
        List<StructureData> all = loadStructures(worldName);
        List<StructureData> filtered = new ArrayList<>();

        for (StructureData data : all) {
            if (data.structureType().equalsIgnoreCase(structureType)) {
                filtered.add(data);
            }
        }

        return filtered;
    }

    /**
     * Gets a random structure from a world.
     */
    public static StructureData getRandomStructure(String worldName) {
        return getRandomStructure(worldName, false);
    }

    /**
     * Gets a random structure from a world, optionally filtering out cleared structures.
     */
    public static StructureData getRandomStructure(String worldName, boolean notCleared) {
        List<StructureData> structures = loadStructures(worldName);
        if (notCleared) {
            structures = structures.stream().filter(s -> !s.cleared()).toList();
        }
        if (structures.isEmpty()) {
            return null;
        }
        return structures.get(new Random().nextInt(structures.size()));
    }

    /**
     * Gets a random structure of a specific type from a world.
     */
    public static StructureData getRandomStructureByType(String worldName, String structureType) {
        return getRandomStructureByType(worldName, structureType, false);
    }

    /**
     * Gets a random structure of a specific type from a world, optionally filtering out cleared structures.
     */
    public static StructureData getRandomStructureByType(String worldName, String structureType, boolean notCleared) {
        List<StructureData> structures = loadStructuresByType(worldName, structureType);
        if (notCleared) {
            structures = structures.stream().filter(s -> !s.cleared()).toList();
        }
        if (structures.isEmpty()) {
            return null;
        }
        return structures.get(new Random().nextInt(structures.size()));
    }

    /**
     * Gets all available structure types in a world.
     */
    public static Set<String> getAvailableTypes(String worldName) {
        List<StructureData> structures = loadStructures(worldName);
        Set<String> types = new HashSet<>();
        for (StructureData data : structures) {
            types.add(data.structureType());
        }
        return types;
    }
}
