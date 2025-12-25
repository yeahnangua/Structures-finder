package com.yeahnangua.structuresfinder.map;

import com.yeahnangua.structuresfinder.StructuresFinder;
import com.yeahnangua.structuresfinder.cache.CachedMapData;
import com.yeahnangua.structuresfinder.data.StructureData;
import com.yeahnangua.structuresfinder.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Creates explorer maps that point to structure locations.
 */
public class ExplorerMapCreator {

    private static final Random random = new Random();

    /**
     * Creates an explorer map pointing to a structure and gives it to the player.
     * Uses async computation to avoid blocking the main thread.
     */
    public static boolean createAndGiveMap(Player player, StructureData structure, MapView.Scale scale) {
        long totalStart = System.currentTimeMillis();
        DebugLogger.log("========== START createAndGiveMap ==========");
        DebugLogger.log("Player: " + player.getName() + ", Structure: " + structure.schematicName() + ", Scale: " + scale);

        World world = Bukkit.getWorld(structure.worldName());
        if (world == null) {
            DebugLogger.log("ERROR: World not found: " + structure.worldName());
            return false;
        }

        StructuresFinder plugin = StructuresFinder.getInstance();

        // Calculate random offset for map center
        int scaleValue = getScaleValue(scale);
        int maxOffset = 60 * scaleValue;

        int offsetX = random.nextInt(maxOffset * 2 + 1) - maxOffset;
        int offsetZ = random.nextInt(maxOffset * 2 + 1) - maxOffset;

        int centerX = structure.x() - offsetX;
        int centerZ = structure.z() - offsetZ;

        DebugLogger.log("Map center: (" + centerX + ", " + centerZ + "), scaleValue: " + scaleValue);

        // Check if explorer map style is enabled
        boolean styleEnabled = plugin.isExplorerMapStyleEnabled();
        int sampleRes = plugin.getSampleResolution();
        DebugLogger.log("Explorer style enabled: " + styleEnabled + ", sample-resolution: " + sampleRes);

        if (styleEnabled) {
            // Async compute terrain data, then create map on main thread
            DebugLogger.log("Starting async terrain computation...");
            long asyncStart = System.currentTimeMillis();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                DebugLogger.logTiming("Time to start async task", asyncStart);

                long computeStart = System.currentTimeMillis();
                byte[] terrainData = computeTerrainData(world, centerX, centerZ, scaleValue);
                DebugLogger.logTiming("computeTerrainData (async)", computeStart);

                // Switch back to main thread to create map and give to player
                long syncStart = System.currentTimeMillis();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    DebugLogger.logTiming("Time to switch back to main thread", syncStart);

                    long createStart = System.currentTimeMillis();
                    createMapWithTerrain(player, structure, scale, world, centerX, centerZ, terrainData);
                    DebugLogger.logTiming("createMapWithTerrain (main thread)", createStart);
                    DebugLogger.logTiming("TOTAL TIME (from command to map given)", totalStart);
                    DebugLogger.log("========== END createAndGiveMap ==========\n");
                });
            });
        } else {
            // No explorer style, create map directly
            DebugLogger.log("Explorer style disabled, creating map directly...");
            long createStart = System.currentTimeMillis();
            createMapWithTerrain(player, structure, scale, world, centerX, centerZ, null);
            DebugLogger.logTiming("createMapWithTerrain (no terrain)", createStart);
            DebugLogger.logTiming("TOTAL TIME", totalStart);
            DebugLogger.log("========== END createAndGiveMap ==========\n");
        }

        return true;
    }

    /**
     * Creates and gives a map from cached data. Used by the cache system.
     * Must be called from the main thread.
     */
    public static void createAndGiveMapFromCache(Player player, CachedMapData cachedData) {
        DebugLogger.log("========== 从缓存创建地图 ==========");
        DebugLogger.log("玩家: " + player.getName());
        DebugLogger.log("结构: " + cachedData.structure().schematicName() + " 坐标(" + cachedData.structure().x() + ", " + cachedData.structure().z() + ")");
        DebugLogger.log("地图中心: (" + cachedData.centerX() + ", " + cachedData.centerZ() + ")");

        World world = Bukkit.getWorld(cachedData.structure().worldName());
        if (world == null) {
            DebugLogger.log("错误: 世界未找到: " + cachedData.structure().worldName());
            return;
        }

        long startTime = System.currentTimeMillis();
        createMapWithTerrain(player, cachedData.structure(), MapView.Scale.FAR,
                world, cachedData.centerX(), cachedData.centerZ(), cachedData.terrainData());
        DebugLogger.log("从缓存创建地图完成, 耗时 " + (System.currentTimeMillis() - startTime) + "ms");
    }

    /**
     * Computes terrain data using parallel processing for better performance.
     * Public for cache system to use.
     */
    public static byte[] computeTerrainData(World world, int centerX, int centerZ, int scale) {
        long methodStart = System.currentTimeMillis();
        DebugLogger.log("--- computeTerrainData START (PARALLEL) ---");

        StructuresFinder plugin = StructuresFinder.getInstance();
        int sampleRes = plugin.getSampleResolution();
        DebugLogger.log("sampleRes: " + sampleRes);

        byte[] terrain = new byte[128 * 128];
        final int SAMPLE_Y = 63;

        // Number of rows to process (128 / sampleRes)
        int rowCount = 128 / sampleRes;
        DebugLogger.log("Parallel processing " + rowCount + " rows with " + rowCount + " samples each = " + (rowCount * rowCount) + " total samples");

        // Atomic counters for thread-safe statistics
        AtomicInteger[] typeCounts = new AtomicInteger[BiomeType.values().length];
        for (int i = 0; i < typeCounts.length; i++) {
            typeCounts[i] = new AtomicInteger(0);
        }

        long loopStart = System.currentTimeMillis();

        // Process rows in parallel
        IntStream.range(0, rowCount).parallel().forEach(rowIndex -> {
            int sampleX = rowIndex * sampleRes;

            for (int sampleZ = 0; sampleZ < 128; sampleZ += sampleRes) {
                // Convert pixel to world coordinates
                int worldX = centerX + (sampleX - 64) * scale;
                int worldZ = centerZ + (sampleZ - 64) * scale;

                // Get biome type at fixed Y level
                BiomeType biomeType = getBiomeType(world, worldX, SAMPLE_Y, worldZ);
                typeCounts[biomeType.ordinal()].incrementAndGet();

                // Fill sample block area with appropriate color
                for (int dx = 0; dx < sampleRes && (sampleX + dx) < 128; dx++) {
                    for (int dz = 0; dz < sampleRes && (sampleZ + dz) < 128; dz++) {
                        int pixelX = sampleX + dx;
                        int pixelZ = sampleZ + dz;
                        int index = pixelZ * 128 + pixelX;
                        terrain[index] = getColorForBiome(biomeType, pixelX, pixelZ);
                    }
                }
            }
        });

        long loopTime = System.currentTimeMillis() - loopStart;
        int totalSamples = rowCount * rowCount;

        DebugLogger.logTiming("Parallel loop total", loopStart);
        DebugLogger.log("Total samples: " + totalSamples);
        DebugLogger.log("  - WATER: " + typeCounts[BiomeType.WATER.ordinal()].get() +
                       ", FOREST: " + typeCounts[BiomeType.FOREST.ordinal()].get() +
                       ", PLAINS: " + typeCounts[BiomeType.PLAINS.ordinal()].get() +
                       ", SNOWY: " + typeCounts[BiomeType.SNOWY.ordinal()].get() +
                       ", OTHER: " + typeCounts[BiomeType.OTHER.ordinal()].get());
        DebugLogger.log("  - Avg time per sample: " + (loopTime / Math.max(1, totalSamples)) + " ms");
        DebugLogger.logTiming("--- computeTerrainData END ---", methodStart);

        return terrain;
    }

    /**
     * Creates the map with pre-computed terrain and gives it to the player.
     */
    private static void createMapWithTerrain(Player player, StructureData structure, MapView.Scale scale,
                                             World world, int centerX, int centerZ, byte[] terrainData) {
        long methodStart = System.currentTimeMillis();
        DebugLogger.log("--- createMapWithTerrain START ---");

        // Create the map item
        long itemStart = System.currentTimeMillis();
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta == null) {
            DebugLogger.log("ERROR: MapMeta is null!");
            return;
        }
        DebugLogger.logTiming("Create ItemStack and get meta", itemStart);

        // Create map view
        long viewStart = System.currentTimeMillis();
        MapView view = Bukkit.createMap(world);
        view.setCenterX(centerX);
        view.setCenterZ(centerZ);
        view.setScale(scale);
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);
        DebugLogger.logTiming("Create and configure MapView", viewStart);

        // Handle renderers
        long rendererStart = System.currentTimeMillis();
        DebugLogger.log("Default renderer count: " + view.getRenderers().size());

        // Use NMS reflection to directly fill the worldMap.colors array (like vanilla explorer maps)
        if (terrainData != null) {
            boolean filled = fillMapColorsViaNMS(view, terrainData);
            DebugLogger.log("NMS colors fill result: " + (filled ? "SUCCESS" : "FAILED"));
        }

        // Only add structure marker renderer for RED_X
        view.addRenderer(new StructureMarkerRenderer(structure));
        DebugLogger.logTiming("Configure renderers", rendererStart);
        DebugLogger.log("Final renderer count: " + view.getRenderers().size());

        // Set metadata
        long metaStart = System.currentTimeMillis();
        meta.setMapView(view);
        meta.setColor(Color.fromRGB(139, 69, 19));

        StructuresFinder plugin = StructuresFinder.getInstance();
        String displayName = plugin.getConfigString("map.display-name");
        displayName = replacePlaceholders(displayName, structure, scale);
        meta.setDisplayName(displayName);

        List<String> configLore = plugin.getConfigStringList("map.lore");
        List<String> lore = new ArrayList<>();
        for (String line : configLore) {
            lore.add(replacePlaceholders(line, structure, scale));
        }
        meta.setLore(lore);
        mapItem.setItemMeta(meta);
        DebugLogger.logTiming("Set item metadata", metaStart);

        // Give map to player
        long giveStart = System.currentTimeMillis();
        if (player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(mapItem);
            DebugLogger.log("Map added to inventory");
        } else {
            player.getWorld().dropItem(player.getLocation(), mapItem);
            DebugLogger.log("Map dropped at player location (inventory full)");
        }
        DebugLogger.logTiming("Give map to player", giveStart);

        DebugLogger.logTiming("--- createMapWithTerrain END ---", methodStart);
    }

    /**
     * Fills the map's internal colors array via NMS reflection.
     * This is how vanilla explorer maps work - they pre-fill the colors array
     * with the land/water pattern, and exploration updates it with real terrain.
     */
    private static boolean fillMapColorsViaNMS(MapView view, byte[] terrainData) {
        try {
            // Get the worldMap field from CraftMapView
            Field worldMapField = view.getClass().getDeclaredField("worldMap");
            worldMapField.setAccessible(true);
            Object worldMap = worldMapField.get(view);

            if (worldMap == null) {
                DebugLogger.log("ERROR: worldMap is null");
                return false;
            }

            DebugLogger.log("WorldMap class: " + worldMap.getClass().getName());

            // Try to find the colors field (name may vary by version)
            Field colorsField = null;
            String[] possibleNames = {"colors", "g", "f", "e", "h"}; // Common obfuscated names

            for (String name : possibleNames) {
                try {
                    colorsField = worldMap.getClass().getDeclaredField(name);
                    colorsField.setAccessible(true);
                    Object value = colorsField.get(worldMap);
                    if (value instanceof byte[] arr && arr.length == 16384) {
                        DebugLogger.log("Found colors field: '" + name + "' (length: " + arr.length + ")");
                        break;
                    }
                    colorsField = null; // Not the right field
                } catch (NoSuchFieldException ignored) {
                    // Try next name
                }
            }

            // If not found by name, search all fields for byte[16384]
            if (colorsField == null) {
                DebugLogger.log("Searching all fields for byte[16384]...");
                for (Field field : worldMap.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    try {
                        Object value = field.get(worldMap);
                        if (value instanceof byte[] arr && arr.length == 16384) {
                            colorsField = field;
                            DebugLogger.log("Found colors field by type: '" + field.getName() + "'");
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (colorsField == null) {
                DebugLogger.log("ERROR: Could not find colors field in WorldMap");
                // Log all fields for debugging
                DebugLogger.log("Available fields in " + worldMap.getClass().getName() + ":");
                for (Field field : worldMap.getClass().getDeclaredFields()) {
                    DebugLogger.log("  - " + field.getName() + " : " + field.getType().getSimpleName());
                }
                return false;
            }

            // Get and fill the colors array
            byte[] colors = (byte[]) colorsField.get(worldMap);
            System.arraycopy(terrainData, 0, colors, 0, Math.min(terrainData.length, colors.length));

            DebugLogger.log("Successfully filled " + colors.length + " bytes in worldMap.colors");
            return true;

        } catch (Exception e) {
            DebugLogger.log("ERROR in fillMapColorsViaNMS: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Determines the biome type for coloring purposes.
     */
    private static BiomeType getBiomeType(World world, int x, int y, int z) {
        Biome biome = world.getBiome(x, y, z);
        String biomeName = biome.getKey().toString().toLowerCase();

        // Check for water biomes
        if (containsAny(biomeName, "ocean", "river", "swamp", "beach")) {
            return BiomeType.WATER;
        }

        // Check for snowy biomes (check before forest because snowy_taiga contains both)
        if (containsAny(biomeName, "snowy", "frozen", "ice", "cold")) {
            return BiomeType.SNOWY;
        }

        // Check for forest biomes
        if (containsAny(biomeName, "forest", "taiga", "jungle", "grove", "cherry")) {
            return BiomeType.FOREST;
        }

        // Check for plains/desert biomes
        if (containsAny(biomeName, "plains", "savanna", "desert", "badlands", "meadow")) {
            return BiomeType.PLAINS;
        }

        // Default for other biomes (mountains, caves, etc.)
        return BiomeType.OTHER;
    }

    /**
     * Helper method to check if a string contains any of the given keywords.
     */
    private static boolean containsAny(String str, String... keywords) {
        for (String keyword : keywords) {
            if (str.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the color for a biome type at a specific pixel position.
     * Water biomes use striped pattern, others use solid color.
     */
    private static byte getColorForBiome(BiomeType type, int pixelX, int pixelZ) {
        return switch (type) {
            case WATER -> {
                boolean isStripe = ((pixelX + pixelZ) % 4) < 2;
                yield isStripe ? WATER_LIGHT : WATER_DARK;
            }
            case FOREST -> FOREST_COLOR;
            case PLAINS -> PLAINS_COLOR;
            case SNOWY -> SNOWY_COLOR;
            case OTHER -> DEFAULT_COLOR;
        };
    }

    // Biome type enumeration for multi-color support
    private enum BiomeType {
        WATER, FOREST, PLAINS, SNOWY, OTHER
    }

    // Explorer map colors - MapPalette color indexes (not RGB!)
    // See: https://minecraft.wiki/w/Map_item_format#Color_table
    private static final byte WATER_LIGHT = 48;   // Light blue (water color)
    private static final byte WATER_DARK = 50;    // Dark blue (water color dark)
    private static final byte FOREST_COLOR = 28;  // Green (grass color)
    private static final byte PLAINS_COLOR = 4;  // Yellow/sand color
    private static final byte SNOWY_COLOR = 34;    // White (snow color)
    private static final byte DEFAULT_COLOR = 0;  // Light beige/cream

    // Keep old constant for compatibility
    private static final byte LAND_COLOR = DEFAULT_COLOR;

    public static String replacePlaceholders(String text, StructureData structure, MapView.Scale scale) {
        if (text == null) return "";

        return text
                .replace("%world%", getTranslatedWorld(structure.worldName()))
                .replace("%world_raw%", structure.worldName())
                .replace("%type%", getTranslatedType(structure.structureType()))
                .replace("%type_raw%", structure.structureType())
                .replace("%type_formatted%", formatStructureType(structure.structureType()))
                .replace("%scale%", scale.name().toLowerCase())
                .replace("%schematic%", structure.schematicName())
                .replace("%x%", String.valueOf(structure.x()))
                .replace("%y%", String.valueOf(structure.y()))
                .replace("%z%", String.valueOf(structure.z()))
                .replace("%coords%", structure.getFormattedCoordinates());
    }

    private static String getTranslatedWorld(String worldName) {
        StructuresFinder plugin = StructuresFinder.getInstance();
        String translated = plugin.getConfig().getString("world-names." + worldName);
        if (translated != null && !translated.isEmpty()) {
            return translated;
        }
        return worldName;
    }

    private static String getTranslatedType(String rawType) {
        StructuresFinder plugin = StructuresFinder.getInstance();
        String translated = plugin.getConfig().getString("structure-types." + rawType);
        if (translated != null && !translated.isEmpty()) {
            return translated;
        }
        return formatStructureType(rawType);
    }

    private static int getScaleValue(MapView.Scale scale) {
        return switch (scale) {
            case CLOSEST -> 1;
            case CLOSE -> 2;
            case NORMAL -> 4;
            case FAR -> 8;
            case FARTHEST -> 16;
        };
    }

    public static String formatStructureType(String type) {
        if (type == null || type.isEmpty()) {
            return "Unknown";
        }
        String[] parts = type.toLowerCase().split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1))
                      .append(" ");
            }
        }
        return result.toString().trim();
    }

    /**
     * Renderer that adds a RED_X marker at the structure location.
     */
    private static class StructureMarkerRenderer extends MapRenderer {
        private final StructureData structure;

        public StructureMarkerRenderer(StructureData structure) {
            super(false);
            this.structure = structure;
        }

        @Override
        public void render(MapView view, org.bukkit.map.MapCanvas canvas, Player player) {
            int centerX = view.getCenterX();
            int centerZ = view.getCenterZ();
            int scale = getScaleValue(view.getScale());

            int pixelOffsetX = (structure.x() - centerX) / scale;
            int pixelOffsetZ = (structure.z() - centerZ) / scale;

            int structureRelX = Math.max(-128, Math.min(127, pixelOffsetX * 2));
            int structureRelZ = Math.max(-128, Math.min(127, pixelOffsetZ * 2));

            org.bukkit.map.MapCursorCollection cursors = canvas.getCursors();
            boolean hasMarker = false;
            for (int i = 0; i < cursors.size(); i++) {
                if (cursors.getCursor(i).getType() == MapCursor.Type.RED_X) {
                    hasMarker = true;
                    break;
                }
            }

            if (!hasMarker) {
                cursors.addCursor(new MapCursor(
                        (byte) structureRelX,
                        (byte) structureRelZ,
                        (byte) 0,
                        MapCursor.Type.RED_X,
                        true
                ));
            }
        }

        private int getScaleValue(MapView.Scale scale) {
            return switch (scale) {
                case CLOSEST -> 1;
                case CLOSE -> 2;
                case NORMAL -> 4;
                case FAR -> 8;
                case FARTHEST -> 16;
            };
        }
    }
}
