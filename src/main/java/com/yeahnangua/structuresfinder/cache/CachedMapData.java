package com.yeahnangua.structuresfinder.cache;

import com.yeahnangua.structuresfinder.data.StructureData;

/**
 * 预生成地图的缓存数据。
 */
public record CachedMapData(
        StructureData structure,
        byte[] terrainData,
        int centerX,
        int centerZ
) {
}
