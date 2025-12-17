package com.yeahnangua.structuresfinder.map;

import com.yeahnangua.structuresfinder.StructuresFinder;
import com.yeahnangua.structuresfinder.data.StructureData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

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
     *
     * @param player    The player to give the map to
     * @param structure The structure to point to
     * @param scale     The map scale level
     * @return true if successful, false otherwise
     */
    public static boolean createAndGiveMap(Player player, StructureData structure, MapView.Scale scale) {
        World world = Bukkit.getWorld(structure.worldName());
        if (world == null) {
            return false;
        }

        // Create the map item
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta == null) {
            return false;
        }

        // Calculate random offset for map center
        // Structure will appear in a random position, but not too close to edges
        // Map is 128x128 pixels, we want structure to be within -60 to +60 range from center
        int scaleValue = getScaleValue(scale);
        int maxOffset = 60 * scaleValue; // blocks offset from structure

        int offsetX = random.nextInt(maxOffset * 2 + 1) - maxOffset;
        int offsetZ = random.nextInt(maxOffset * 2 + 1) - maxOffset;

        // Map center is offset from structure position
        int centerX = structure.x() - offsetX;
        int centerZ = structure.z() - offsetZ;

        // Create a new map view with offset center
        MapView view = Bukkit.createMap(world);
        view.setCenterX(centerX);
        view.setCenterZ(centerZ);
        view.setScale(scale);
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);

        // Keep default renderer for map rendering and player tracking
        // Add our custom renderer only for structure marker
        view.addRenderer(new StructureMapRenderer(structure, view));

        // Set map metadata
        meta.setMapView(view);
        meta.setColor(Color.fromRGB(139, 69, 19)); // Brown color for exploration

        // Get display name from config with placeholders replaced
        StructuresFinder plugin = StructuresFinder.getInstance();
        String displayName = plugin.getConfigString("map.display-name");
        displayName = replacePlaceholders(displayName, structure, scale);
        meta.setDisplayName(displayName);

        // Get lore from config with placeholders replaced
        List<String> configLore = plugin.getConfigStringList("map.lore");
        List<String> lore = new ArrayList<>();
        for (String line : configLore) {
            lore.add(replacePlaceholders(line, structure, scale));
        }
        meta.setLore(lore);

        mapItem.setItemMeta(meta);

        // Give map to player
        if (player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(mapItem);
        } else {
            player.getWorld().dropItem(player.getLocation(), mapItem);
        }

        return true;
    }

    /**
     * Replaces placeholders in a string with actual values.
     */
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

    /**
     * Gets the translated world name from config, or falls back to original name.
     */
    private static String getTranslatedWorld(String worldName) {
        StructuresFinder plugin = StructuresFinder.getInstance();
        String translated = plugin.getConfig().getString("world-names." + worldName);
        if (translated != null && !translated.isEmpty()) {
            return translated;
        }
        // Fallback to original name
        return worldName;
    }

    /**
     * Gets the translated structure type from config, or falls back to formatted English.
     */
    private static String getTranslatedType(String rawType) {
        StructuresFinder plugin = StructuresFinder.getInstance();
        String translated = plugin.getConfig().getString("structure-types." + rawType);
        if (translated != null && !translated.isEmpty()) {
            return translated;
        }
        // Fallback to formatted English name
        return formatStructureType(rawType);
    }

    /**
     * Gets the block multiplier for a map scale.
     */
    private static int getScaleValue(MapView.Scale scale) {
        return switch (scale) {
            case CLOSEST -> 1;
            case CLOSE -> 2;
            case NORMAL -> 4;
            case FAR -> 8;
            case FARTHEST -> 16;
        };
    }

    /**
     * Formats the structure type for display.
     */
    public static String formatStructureType(String type) {
        if (type == null || type.isEmpty()) {
            return "Unknown";
        }
        // Convert UNDERGROUND_DEEP to Underground Deep
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
     * Custom map renderer that only adds a marker at the structure location.
     * Does not interfere with default renderer's player tracking.
     */
    private static class StructureMapRenderer extends MapRenderer {
        private final StructureData structure;
        private final MapView mapView;

        public StructureMapRenderer(StructureData structure, MapView mapView) {
            super(false); // contextual = false, same for all players
            this.structure = structure;
            this.mapView = mapView;
        }

        @Override
        public void render(MapView view, org.bukkit.map.MapCanvas canvas, Player player) {
            // Calculate cursor position relative to map center
            int centerX = view.getCenterX();
            int centerZ = view.getCenterZ();
            int scale = getScaleValue(view.getScale());

            // Structure marker position calculation:
            // 1. Map is 128x128 pixels, covers 128*scale blocks in each direction from center
            // 2. MapCursor coordinates range from -128 to 127 (256 values for 128 pixels)
            // 3. So cursor coordinate = pixel offset * 2
            int pixelOffsetX = (structure.x() - centerX) / scale;
            int pixelOffsetZ = (structure.z() - centerZ) / scale;

            // Convert pixel offset to MapCursor coordinates (multiply by 2)
            int structureRelX = pixelOffsetX * 2;
            int structureRelZ = pixelOffsetZ * 2;

            // Clamp to map bounds (-128 to 127)
            structureRelX = Math.max(-128, Math.min(127, structureRelX));
            structureRelZ = Math.max(-128, Math.min(127, structureRelZ));

            // Get existing cursors (from default renderer) and add structure marker
            org.bukkit.map.MapCursorCollection cursors = canvas.getCursors();

            // Check if we already added the structure cursor (avoid duplicates)
            boolean hasStructureCursor = false;
            for (int i = 0; i < cursors.size(); i++) {
                if (cursors.getCursor(i).getType() == MapCursor.Type.RED_X) {
                    hasStructureCursor = true;
                    break;
                }
            }

            if (!hasStructureCursor) {
                MapCursor structureCursor = new MapCursor(
                        (byte) structureRelX,
                        (byte) structureRelZ,
                        (byte) 0,
                        MapCursor.Type.RED_X,
                        true
                );
                cursors.addCursor(structureCursor);
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
