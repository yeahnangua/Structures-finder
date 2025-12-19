package com.yeahnangua.structuresfinder.map;

import com.yeahnangua.structuresfinder.StructuresFinder;
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
     * Computes terrain data asynchronously.
     */
    private static byte[] computeTerrainData(World world, int centerX, int centerZ, int scale) {
        long methodStart = System.currentTimeMillis();
        DebugLogger.log("--- computeTerrainData START ---");

        // Reset debug counters
        debugBiomeCount = 0;
        debugWaterFound = 0;

        StructuresFinder plugin = StructuresFinder.getInstance();

        long configStart = System.currentTimeMillis();
        int sampleRes = plugin.getSampleResolution();
        DebugLogger.logTiming("Reading config", configStart);
        DebugLogger.log("sampleRes: " + sampleRes);

        byte[] terrain = new byte[128 * 128];

        // Calculate expected iterations
        int expectedSamples = (128 / sampleRes) * (128 / sampleRes);
        DebugLogger.log("Expected sample count: " + expectedSamples + " (128/" + sampleRes + " = " + (128/sampleRes) + " per axis)");

        int sampleCount = 0;
        long biomeTime = 0;
        long fillTime = 0;

        // Use fixed Y level for biome detection (sea level = 63)
        final int SAMPLE_Y = 63;
        DebugLogger.log("Using fixed Y level for biome sampling: " + SAMPLE_Y);

        // Biome type counters
        int[] typeCounts = new int[BiomeType.values().length];

        long loopStart = System.currentTimeMillis();

        // Iterate through sampled pixels
        for (int sampleX = 0; sampleX < 128; sampleX += sampleRes) {
            for (int sampleZ = 0; sampleZ < 128; sampleZ += sampleRes) {
                sampleCount++;

                // Convert pixel to world coordinates
                int worldX = centerX + (sampleX - 64) * scale;
                int worldZ = centerZ + (sampleZ - 64) * scale;

                // Get biome type at fixed Y level
                long bStart = System.currentTimeMillis();
                BiomeType biomeType = getBiomeType(world, worldX, SAMPLE_Y, worldZ);
                biomeTime += System.currentTimeMillis() - bStart;

                typeCounts[biomeType.ordinal()]++;

                // Fill sample block area with appropriate color
                long fStart = System.currentTimeMillis();
                for (int dx = 0; dx < sampleRes && (sampleX + dx) < 128; dx++) {
                    for (int dz = 0; dz < sampleRes && (sampleZ + dz) < 128; dz++) {
                        int pixelX = sampleX + dx;
                        int pixelZ = sampleZ + dz;
                        int index = pixelZ * 128 + pixelX;
                        terrain[index] = getColorForBiome(biomeType, pixelX, pixelZ);
                    }
                }
                fillTime += System.currentTimeMillis() - fStart;
            }
        }

        DebugLogger.logTiming("Main loop total", loopStart);
        DebugLogger.log("Actual sample count: " + sampleCount);
        DebugLogger.log("  - WATER: " + typeCounts[BiomeType.WATER.ordinal()] +
                       ", FOREST: " + typeCounts[BiomeType.FOREST.ordinal()] +
                       ", PLAINS: " + typeCounts[BiomeType.PLAINS.ordinal()] +
                       ", SNOWY: " + typeCounts[BiomeType.SNOWY.ordinal()] +
                       ", OTHER: " + typeCounts[BiomeType.OTHER.ordinal()]);
        DebugLogger.log("  - getBiome total: " + biomeTime + " ms (avg: " + (biomeTime / Math.max(1, sampleCount)) + " ms/sample)");
        DebugLogger.log("  - fill pixels total: " + fillTime + " ms");
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

        // Log first few biome checks for debugging
        if (debugBiomeCount < 10) {
            DebugLogger.log("  [DEBUG] Biome at (" + x + "," + y + "," + z + "): " + biomeName);
            debugBiomeCount++;
        }

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

    // Debug counters (reset each map creation)
    private static int debugBiomeCount = 0;
    private static int debugWaterFound = 0;

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
