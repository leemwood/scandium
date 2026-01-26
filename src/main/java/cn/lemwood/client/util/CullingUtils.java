package cn.lemwood.client.util;

import cn.lemwood.client.ScandiumClient;
import cn.lemwood.config.ScandiumConfig;
import cn.lemwood.mixin.CameraAccessor;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

public class CullingUtils {
    private static ClientWorld cachedWorld;
    private static final Long2IntOpenHashMap cachedHeights = new Long2IntOpenHashMap();
    private static final Long2BooleanOpenHashMap cachedTransparency = new Long2BooleanOpenHashMap();
    private static long lastCacheTime = -1;
    private static int cachedPlayerSurfaceY;
    private static int cachedPlayerCeilingY;
    private static boolean cachedPlayerUnderground;
    private static double cachedCameraX, cachedCameraY, cachedCameraZ;
    private static Vec3d cachedLookVector;
    private static boolean isNether;
    private static boolean hasCeiling;
    private static double fovCosineThreshold;
    private static int cachedReservedHeight;
    private static float lastYaw, lastPitch;
    private static double smoothedRotationFactor = 0;

    static {
        cachedHeights.defaultReturnValue(-1);
        cachedLookVector = new Vec3d(0, 0, 1);
    }

    public static void resetCache() {
        cachedHeights.clear();
        cachedTransparency.clear();
        cachedWorld = null;
        lastCacheTime = -1;
    }

    public static boolean shouldCull(Box box, int chunkX, int chunkY, int chunkZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return false;

        ScandiumConfig config = ScandiumConfig.getInstance();
        if (!config.enabled) return false;

        // Spectator mode support: disable culling for spectators to prevent visual glitches when moving through blocks
        if (client.player.isSpectator() && !config.ignoreSpectatorMode) return false;
        
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = ((CameraAccessor) camera).getPos();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;

        long currentTime = client.world.getTime();
        boolean shouldUpdate;
        if (config.syncWithSodium) {
            shouldUpdate = (currentTime != lastCacheTime);
        } else {
            int interval = Math.max(1, 21 - (config.updateSpeed / 5)); // Scaled to game ticks
            shouldUpdate = (currentTime - lastCacheTime >= interval || lastCacheTime == -1);
        }

        if (client.world != cachedWorld) {
            cachedWorld = client.world;
            isNether = client.world.getDimensionEntry().value().attributes().containsKey(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY);
            hasCeiling = client.world.getDimensionEntry().value().hasCeiling();
            cachedHeights.clear();
            cachedTransparency.clear();
            lastCacheTime = -1;
        }

        float currentYaw = camera.getYaw();
        float currentPitch = camera.getPitch();

        double dx = cameraX - cachedCameraX;
        double dy = cameraY - cachedCameraY;
        double dz = cameraZ - cachedCameraZ;
        boolean rotationChanged = currentYaw != lastYaw || currentPitch != lastPitch;

        if (dx * dx + dy * dy + dz * dz > 0.25 || shouldUpdate || rotationChanged) {
            cachedCameraX = cameraX;
            cachedCameraY = cameraY;
            cachedCameraZ = cameraZ;
            cachedLookVector = Vec3d.fromPolar(currentPitch, currentYaw);
            lastCacheTime = currentTime;

            int px = MathHelper.floor(cameraX);
            int pz = MathHelper.floor(cameraZ);
            cachedPlayerSurfaceY = getReliableSurfaceY(client.world, px, pz);
            
            // 更灵活的地下判定：光照等级低或处于地表以下且无天空光
            BlockPos playerPos = new BlockPos(px, MathHelper.floor(cameraY), pz);
            int skyLight = client.world.getLightLevel(net.minecraft.world.LightType.SKY, playerPos);
            cachedPlayerUnderground = (skyLight < 8 && cameraY < (double) (cachedPlayerSurfaceY - 4)) || cameraY < (double) (cachedPlayerSurfaceY - 16);
            
            if (isNether) {
                cachedPlayerUnderground = true; // 下界始终视为地下
                cachedPlayerCeilingY = 120;
            }

            int renderDistance = client.options.getClampedViewDistance();
            // 每单位 reservedHeight 现在代表 8 格（半个区块高度）
            cachedReservedHeight = Math.min(config.reservedHeight, renderDistance * 2) * 8;

            // Rotation-aware FOV logic
            float dyaw = Math.abs(currentYaw - lastYaw);
            if (dyaw > 180) dyaw = 360 - dyaw;
            float dpitch = Math.abs(currentPitch - lastPitch);
            double rotationSpeed = Math.sqrt(dyaw * dyaw + dpitch * dpitch);

            if (rotationSpeed > smoothedRotationFactor) {
                smoothedRotationFactor = rotationSpeed;
            } else {
                smoothedRotationFactor *= 0.85; // Faster decay for better precision when stable
            }
            lastYaw = currentYaw;
            lastPitch = currentPitch;

            double baseFov = config.fovAngle;
            double dynamicMargin = Math.min(60.0, smoothedRotationFactor * 4.0);
            double safetyMargin = 12.0;
            if (cachedPlayerUnderground) {
                dynamicMargin = Math.min(20.0, smoothedRotationFactor * 2.0);
                safetyMargin = 6.0;
            }
            double totalFov = baseFov + dynamicMargin + safetyMargin;
            
            double halfFovRad = Math.toRadians(totalFov / 2.0);
            fovCosineThreshold = Math.cos(halfFovRad);

            if (config.debugMode) {
                ScandiumClient.debugCachedSurfaceY = cachedPlayerSurfaceY;
                ScandiumClient.debugCachedUnderground = cachedPlayerUnderground;
            }
        }

        if (config.debugMode) {
            ScandiumClient.TOTAL_CHECKED++;
            ScandiumClient.HUD_TOTAL_CHECKED++;
        }

        // 1. Transparency Awareness
        if (config.transparencyAwareness) {
            if (isChunkTransparent(client.world, chunkX, chunkY, chunkZ)) {
                return false; 
            }
        }

        // 2. FOV Culling
        if (config.fovCullingEnabled) {
            Vec3d look = cachedLookVector;
            if (look == null) {
                look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
            }
            double centerX = box.minX + 8.0;
            double centerY = box.minY + 8.0;
            double centerZ = box.minZ + 8.0;
            
            double dx_chunk = centerX - cameraX;
            double dy_chunk = centerY - cameraY;
            double dz_chunk = centerZ - cameraZ;
            double distSq = dx_chunk*dx_chunk + dy_chunk*dy_chunk + dz_chunk*dz_chunk;
            
            // 矿洞环境下 FOV 剔除更激进：减小最小生效距离
            double minFovDistSq = cachedPlayerUnderground ? 64 : 400; // 地底 8 格外即生效，地表 20 格
            
            if (distSq > minFovDistSq) { 
                double invDist = 1.0 / Math.sqrt(distSq);
                double dot = (look.x * dx_chunk + look.y * dy_chunk + look.z * dz_chunk) * invDist;
                
                // 地底使用更严格的阈值（减少冗余）
                double currentThreshold = fovCosineThreshold;
                if (cachedPlayerUnderground && config.aggressiveVerticalCulling) {
                    // 在地底且开启激进模式时，进一步收紧 FOV 判定
                    double tightenedFov = config.fovAngle + 8.0; // 仅保留 8 度固定冗余
                    currentThreshold = Math.cos(Math.toRadians(tightenedFov / 2.0));
                }
                
                if (dot < currentThreshold) {
                    if (config.debugMode) {
                        ScandiumClient.CULLED_COUNT++;
                        ScandiumClient.CULLED_FOV++;
                        ScandiumClient.HUD_CULLED_COUNT++;
                        ScandiumClient.HUD_CULLED_FOV++;
                    }
                    return true;
                }
            }
        }

        // Pre-calculate surfaceY for culling logic
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        int surfaceY = cachedHeights.get(key);
        if (surfaceY == -1) {
            surfaceY = getReliableSurfaceY(client.world, (chunkX << 4) + 8, (chunkZ << 4) + 8);
            cachedHeights.put(key, surfaceY);
        }

        // 3. Aggressive Mountain Culling / Nether Ceiling Culling
        if (config.aggressiveMountainCulling) {
            if (isNether || (hasCeiling && cameraY < 120)) {
                if (cameraY < 110 && box.minY > 128) {
                    markCulled(config, "mountain");
                    return true;
                }
            } else if (cachedPlayerUnderground) {
                // 使用区块自身的表面高度判定，防止在山脚下误剔除山顶
                if (box.minY > (double) (surfaceY + 8)) {
                    double dx_mountain = (box.minX + 8.0) - cameraX;
                    double dz_mountain = (box.minZ + 8.0) - cameraZ;
                    double dy_mountain = (box.minY + 8.0) - cameraY;
                    if (dx_mountain * dx_mountain + dz_mountain * dz_mountain > 1024) {
                        // 视角保护：即使判定为“山脉以上”，如果在视角内也必须渲染
                        Vec3d look = cachedLookVector;
                        if (look == null) look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
                        double dist = Math.sqrt(dx_mountain * dx_mountain + dy_mountain * dy_mountain + dz_mountain * dz_mountain);
                        if (dist > 0.1) {
                            double dot = (look.x * dx_mountain + look.y * dy_mountain + look.z * dz_mountain) / dist;
                            if (dot >= fovCosineThreshold) return false;
                        }
                        
                        markCulled(config, "mountain");
                        return true;
                    }
                }
            }
        }

        // 4. Standard Vertical Culling
        if (isNether) {
            double diffY = Math.abs((box.minY + 8.0) - cameraY);
            if (diffY > (double) (cachedReservedHeight + 32)) {
                markCulled(config, "vertical");
                return true;
            }
            return false;
        }

        if (!cachedPlayerUnderground) {
            // 玩家在户外时，垂直剔除应极其保守，防止剔除山体中的矿洞入口
            int verticalOffset = config.aggressiveVerticalCulling ? 32 : 64;
            if (box.maxY < (double) (surfaceY - verticalOffset) && box.maxY < cameraY - verticalOffset) {
                markCulled(config, "vertical");
                return true;
            }
        } else {
            // 地底横向剔除逻辑：性能优先 + 视角绝对保护
            if (config.undergroundHorizontalCulling) {
                double dx_u = (box.minX + 8.0) - cameraX;
                double dz_u = (box.minZ + 8.0) - cameraZ;
                double horizontalDistSq = dx_u * dx_u + dz_u * dz_u;
                double maxHorizontalDist = config.undergroundHorizontalDistance << 4; // 转换区块为格数
                
                if (horizontalDistSq > maxHorizontalDist * maxHorizontalDist) {
                    // 计算区块相对于视角的点积，用于判定是否在视角内
                    Vec3d look = cachedLookVector;
                    if (look == null) look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
                    
                    double dy_u = (box.minY + 8.0) - cameraY;
                    double dist = Math.sqrt(horizontalDistSq + dy_u * dy_u);
                    double dot = 0;
                    if (dist > 0.1) {
                        dot = (look.x * dx_u + look.y * dy_u + look.z * dz_u) / dist;
                    }

                    // 核心逻辑：如果区块在视角范围内（使用 fovCosineThreshold，包含旋转补偿和安全余量），则必须渲染
                    // 这确保了玩家看到的任何东西都不会被横向剔除切掉
                    if (dot >= fovCosineThreshold) {
                        // 在视角内，即使超过了地下横向距离限制，也允许渲染
                    } else {
                        // 只有在视角外（或边缘外）且超过横向距离时才剔除
                        markCulled(config, "mountain");
                        return true;
                    }
                }
            }

            double diffY = Math.abs((box.minY + 8.0) - cameraY);
            int undergroundOffset = config.aggressiveVerticalCulling ? (cachedReservedHeight / 2) : cachedReservedHeight;
            // 移除最小 16 格限制，完全遵循设置
            if (diffY > (double) undergroundOffset) {
                // 视角保护：即使超过垂直距离，如果在视角内也必须渲染
                double dx_v = (box.minX + 8.0) - cameraX;
                double dz_v = (box.minZ + 8.0) - cameraZ;
                double dy_v = (box.minY + 8.0) - cameraY;
                Vec3d look = cachedLookVector;
                if (look == null) look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
                double dist = Math.sqrt(dx_v * dx_v + dy_v * dy_v + dz_v * dz_v);
                if (dist > 0.1) {
                    double dot = (look.x * dx_v + look.y * dy_v + look.z * dz_v) / dist;
                    if (dot >= fovCosineThreshold) {
                        // 在视角内，即使超过垂直距离也放宽限制（或者直接不剔除）
                        // 这里我们选择不剔除，以完全遵循“视角可见必须渲染”
                    } else {
                        markCulled(config, "vertical");
                        return true;
                    }
                }
            }
            // 玩家在地下时，剔除地表以上的区块（此时玩家无法通过地层看到天空）
            // 激进模式下直接从 surfaceY 开始剔除，非激进模式保留少量偏移
            int surfaceOffset = config.aggressiveVerticalCulling ? -8 : 0; 
            if (cameraY < (double) (surfaceY - 16) && box.minY > (double) (surfaceY + surfaceOffset)) {
                markCulled(config, "vertical");
                return true;
            }
        }

        return false;
    }

    private static void markCulled(ScandiumConfig config, String type) {
        if (config.debugMode) {
            ScandiumClient.CULLED_COUNT++;
            ScandiumClient.HUD_CULLED_COUNT++;
            if ("mountain".equals(type)) {
                ScandiumClient.CULLED_MOUNTAIN++;
                ScandiumClient.HUD_CULLED_MOUNTAIN++;
            } else if ("vertical".equals(type)) {
                ScandiumClient.CULLED_VERTICAL++;
                ScandiumClient.HUD_CULLED_VERTICAL++;
            }
        }
    }

    private static boolean isChunkTransparent(ClientWorld world, int cx, int cy, int cz) {
        long key = ((long) cx & 0xFFFFFFL) | (((long) cy & 0xFFFFFFL) << 24) | (((long) cz & 0xFFFFFFL) << 48);
        if (cachedTransparency.containsKey(key)) return cachedTransparency.get(key);

        Chunk chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return false;

        boolean transparent = false;
        int startX = cx << 4;
        int startY = cy << 4;
        int startZ = cz << 4;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        // Sampling strategy: center, corners, and face centers
        int[] samples = {0, 7, 15}; 
        outer:
        for (int y : samples) {
            for (int x : samples) {
                for (int z : samples) {
                    pos.set(startX + x, startY + y, startZ + z);
                    BlockState state = chunk.getBlockState(pos);
                    // Fast path for air and common transparent blocks
                    if (state.isAir()) continue;
                    if (!state.isOpaque() || state.isOf(Blocks.WATER) || state.isOf(Blocks.GLASS)) {
                        transparent = true;
                        break outer;
                    }
                }
            }
        }

        cachedTransparency.put(key, transparent);
        return transparent;
    }

    public static int getReliableSurfaceY(ClientWorld world, int x, int z) {
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
             return world.getBottomY();
        }

        // Use MOTION_BLOCKING heightmap for efficiency
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
    }
}
