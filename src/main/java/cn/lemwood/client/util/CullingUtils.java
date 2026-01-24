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

        // Spectator mode support: disable culling for spectators to prevent visual glitches when moving through blocks
        if (client.player.isSpectator()) return false;

        ScandiumConfig config = ScandiumConfig.getInstance();
        if (!config.enabled) return false;
        
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = ((CameraAccessor) camera).getPos();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;

        if (client.world != cachedWorld) {
            cachedWorld = client.world;
            isNether = client.world.getDimensionEntry().value().attributes().containsKey(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY);
            hasCeiling = client.world.getDimensionEntry().value().hasCeiling();
            cachedHeights.clear();
            cachedTransparency.clear();
            // Clear camera cache on world change to force update
            lastCacheTime = -1;
        }
        float currentYaw = camera.getYaw();
        float currentPitch = camera.getPitch();

        double dx = cameraX - cachedCameraX;
        double dy = cameraY - cachedCameraY;
        double dz = cameraZ - cachedCameraZ;
        boolean shouldUpdate = (System.currentTimeMillis() - lastCacheTime) > 1000;
        boolean rotationChanged = currentYaw != lastYaw || currentPitch != lastPitch;

        if (dx * dx + dy * dy + dz * dz > 0.25 || shouldUpdate || rotationChanged) {
            cachedCameraX = cameraX;
            cachedCameraY = cameraY;
            cachedCameraZ = cameraZ;
            cachedLookVector = Vec3d.fromPolar(currentPitch, currentYaw);
            lastCacheTime = System.currentTimeMillis();

            int px = MathHelper.floor(cameraX);
            int pz = MathHelper.floor(cameraZ);
            cachedPlayerSurfaceY = getReliableSurfaceY(client.world, px, pz);

            // 更严格的地下判定：必须在地表 12 格以下，且无法直接看到天空
            boolean skyVisible = client.world.isSkyVisible(new BlockPos(px, MathHelper.floor(cameraY), pz));
            cachedPlayerUnderground = !skyVisible && cameraY < (double) (cachedPlayerSurfaceY - 12);

            if (isNether) {
                cachedPlayerCeilingY = 120;
            }

            int renderDistance = client.options.getClampedViewDistance();
            cachedReservedHeight = Math.min(config.reservedHeight, renderDistance) << 4;

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
            // Add extra margin for fast rotation and chunk size at edges
            double dynamicMargin = Math.min(60.0, smoothedRotationFactor * 4.0);
            double totalFov = baseFov + dynamicMargin + 12.0; // 12 degrees constant safety margin

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
            
            if (distSq > 400) { // Increased minimum distance to 20 blocks to avoid edge issues nearby
                double invDist = 1.0 / Math.sqrt(distSq);
                double dot = (look.x * dx_chunk + look.y * dy_chunk + look.z * dz_chunk) * invDist;
                
                if (dot < fovCosineThreshold) {
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
                    if (dx_mountain * dx_mountain + dz_mountain * dz_mountain > 1024) {
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
            if (box.maxY < (double) (surfaceY - 64) && box.maxY < cameraY - 64) {
                markCulled(config, "vertical");
                return true;
            }
        } else {
            double diffY = Math.abs((box.minY + 8.0) - cameraY);
            if (diffY > (double) cachedReservedHeight) {
                markCulled(config, "vertical");
                return true;
            }
            // 玩家在地下时，剔除地表以上的区块（此时玩家无法通过地层看到天空）
            if (cameraY < (double) (surfaceY - 16) && box.minY > (double) (surfaceY + 4)) {
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
