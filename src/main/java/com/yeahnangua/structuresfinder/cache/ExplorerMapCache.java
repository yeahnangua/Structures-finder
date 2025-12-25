package com.yeahnangua.structuresfinder.cache;

import com.yeahnangua.structuresfinder.StructuresFinder;
import com.yeahnangua.structuresfinder.data.StructureData;
import com.yeahnangua.structuresfinder.data.StructureDataLoader;
import com.yeahnangua.structuresfinder.map.ExplorerMapCreator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 管理预生成地图的缓存系统。
 */
public class ExplorerMapCache {

    private static final int SCALE_VALUE = 8; // FAR scale
    private static final Random random = new Random();

    private final StructuresFinder plugin;
    private final File cacheFolder;
    private final ConcurrentHashMap<String, CachedMapData> cache = new ConcurrentHashMap<>();
    private final Set<String> regenerating = ConcurrentHashMap.newKeySet();

    public ExplorerMapCache(StructuresFinder plugin) {
        this.plugin = plugin;
        this.cacheFolder = new File(plugin.getDataFolder(), "cache");
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs();
        }
    }

    private String getCacheKey(String worldName, String structureType) {
        return worldName + "_" + structureType;
    }

    /**
     * 从文件加载所有缓存。
     */
    public void loadFromDisk() {
        plugin.getLogger().info("[缓存] 从磁盘加载缓存...");
        File[] files = cacheFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            plugin.getLogger().info("[缓存] 未找到缓存文件");
            return;
        }

        plugin.getLogger().info("[缓存] 发现 " + files.length + " 个缓存文件");
        for (File file : files) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                String key = file.getName().replace(".yml", "");

                String worldName = yaml.getString("worldName");
                String structureType = yaml.getString("structureType");
                String schematicName = yaml.getString("schematicName");
                int x = yaml.getInt("x");
                int y = yaml.getInt("y");
                int z = yaml.getInt("z");
                boolean cleared = yaml.getBoolean("cleared", false);
                int centerX = yaml.getInt("centerX");
                int centerZ = yaml.getInt("centerZ");
                String terrainBase64 = yaml.getString("terrainData");

                if (worldName == null || terrainBase64 == null) {
                    plugin.getLogger().warning("[缓存] 无效的缓存文件: " + file.getName());
                    continue;
                }

                byte[] terrainData = Base64.getDecoder().decode(terrainBase64);
                StructureData structure = new StructureData(worldName, x, y, z, schematicName, structureType, cleared);
                CachedMapData cachedMap = new CachedMapData(structure, terrainData, centerX, centerZ);

                cache.put(key, cachedMap);
                plugin.getLogger().info("[缓存] 已加载: " + key + " -> " + schematicName + " 坐标(" + x + ", " + z + ")");

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[缓存] 加载失败: " + file.getName(), e);
            }
        }
    }

    /**
     * 保存单个缓存到文件。
     */
    private void saveToDisk(String key, CachedMapData data) {
        File file = new File(cacheFolder, key + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();

        yaml.set("worldName", data.structure().worldName());
        yaml.set("structureType", data.structure().structureType());
        yaml.set("schematicName", data.structure().schematicName());
        yaml.set("x", data.structure().x());
        yaml.set("y", data.structure().y());
        yaml.set("z", data.structure().z());
        yaml.set("cleared", data.structure().cleared());
        yaml.set("centerX", data.centerX());
        yaml.set("centerZ", data.centerZ());
        yaml.set("terrainData", Base64.getEncoder().encodeToString(data.terrainData()));

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[缓存] 保存失败: " + key, e);
        }
    }

    /**
     * 获取缓存的地图数据。
     */
    public CachedMapData get(String worldName, String structureType) {
        String key = getCacheKey(worldName, structureType);
        CachedMapData data = cache.get(key);
        if (data != null) {
            plugin.getLogger().info("[缓存] 命中: " + key + " -> " + data.structure().schematicName());
        } else {
            plugin.getLogger().warning("[缓存] 未命中: " + key);
        }
        return data;
    }

    /**
     * 异步生成新的缓存并补充。
     */
    public void regenerateAsync(String worldName, String structureType) {
        String key = getCacheKey(worldName, structureType);

        // 防止重复生成
        if (!regenerating.add(key)) {
            plugin.getLogger().info("[缓存] 跳过 (正在生成中): " + key);
            return;
        }

        plugin.getLogger().info("[缓存] 已加入生成队列: " + key);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long startTime = System.currentTimeMillis();
                plugin.getLogger().info("[缓存] 开始生成: " + key);

                // 随机选择一个该类型的结构
                StructureData structure = StructureDataLoader.getRandomStructureByType(worldName, structureType, false);
                if (structure == null) {
                    plugin.getLogger().warning("[缓存] 未找到结构: " + key);
                    return;
                }
                plugin.getLogger().info("[缓存] 选中结构: " + structure.schematicName() + " 坐标(" + structure.x() + ", " + structure.z() + ")");

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("[缓存] 世界未加载: " + worldName);
                    return;
                }

                // 计算随机偏移的地图中心
                int maxOffset = 60 * SCALE_VALUE;
                int offsetX = random.nextInt(maxOffset * 2 + 1) - maxOffset;
                int offsetZ = random.nextInt(maxOffset * 2 + 1) - maxOffset;
                int centerX = structure.x() - offsetX;
                int centerZ = structure.z() - offsetZ;
                plugin.getLogger().info("[缓存] 地图中心: (" + centerX + ", " + centerZ + ") 偏移: (" + offsetX + ", " + offsetZ + ")");

                // 计算地形数据
                plugin.getLogger().info("[缓存] 正在计算地形数据...");
                byte[] terrainData = ExplorerMapCreator.computeTerrainData(world, centerX, centerZ, SCALE_VALUE);
                if (terrainData == null) {
                    plugin.getLogger().warning("[缓存] 地形计算失败: " + key);
                    return;
                }

                // 存入缓存
                CachedMapData cachedMap = new CachedMapData(structure, terrainData, centerX, centerZ);
                cache.put(key, cachedMap);
                saveToDisk(key, cachedMap);

                long elapsed = System.currentTimeMillis() - startTime;
                plugin.getLogger().info("[缓存] 生成完成: " + key + " 耗时 " + elapsed + "ms");
            } finally {
                regenerating.remove(key);
            }
        });
    }

    /**
     * 初始化所有缓存。服务器启动时调用。
     */
    public void initializeAll() {
        List<String> worlds = StructureDataLoader.getAvailableWorlds();
        plugin.getLogger().info("[缓存] ========== 初始化缓存 ==========");
        plugin.getLogger().info("[缓存] 可用世界: " + worlds);
        plugin.getLogger().info("[缓存] 当前缓存数量: " + cache.size());

        int missing = 0;
        for (String worldName : worlds) {
            Set<String> types = StructureDataLoader.getAvailableTypes(worldName);
            plugin.getLogger().info("[缓存] 世界 '" + worldName + "' 的结构类型: " + types);

            for (String type : types) {
                String key = getCacheKey(worldName, type);
                if (!cache.containsKey(key)) {
                    plugin.getLogger().info("[缓存] 缺失: " + key + " -> 加入生成队列");
                    regenerateAsync(worldName, type);
                    missing++;
                } else {
                    plugin.getLogger().info("[缓存] 已存在: " + key);
                }
            }
        }

        plugin.getLogger().info("[缓存] ========== 初始化完成 ==========");
        plugin.getLogger().info("[缓存] 共 " + missing + " 个缓存待生成");
    }

    /**
     * 检查某个组合是否有缓存。
     */
    public boolean has(String worldName, String structureType) {
        return cache.containsKey(getCacheKey(worldName, structureType));
    }

    /**
     * 获取某个世界所有已缓存的类型。
     */
    public List<String> getCachedTypes(String worldName) {
        List<String> types = new java.util.ArrayList<>();
        String prefix = worldName + "_";
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                types.add(key.substring(prefix.length()));
            }
        }
        return types;
    }

    /**
     * 随机获取某个世界的一个缓存。
     */
    public CachedMapData getRandomCached(String worldName) {
        List<String> types = getCachedTypes(worldName);
        if (types.isEmpty()) {
            plugin.getLogger().warning("[缓存] 世界 '" + worldName + "' 没有任何缓存");
            return null;
        }
        String randomType = types.get(random.nextInt(types.size()));
        plugin.getLogger().info("[缓存] 随机选择类型: " + randomType);
        return get(worldName, randomType);
    }
}
