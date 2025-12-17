package com.yeahnangua.structuresfinder.data;

/**
 * Data class representing a structure location from BetterStructures.
 */
public record StructureData(
        String worldName,
        int x,
        int y,
        int z,
        String schematicName,
        String structureType
) {
    /**
     * Returns formatted coordinates as "x, y, z" string.
     */
    public String getFormattedCoordinates() {
        return x + ", " + y + ", " + z;
    }
}
