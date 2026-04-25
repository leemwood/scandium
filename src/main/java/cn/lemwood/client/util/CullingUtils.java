package cn.lemwood.client.util;

import cn.lemwood.client.ScandiumClient;
import cn.lemwood.config.ScandiumConfig;
import cn.lemwood.mixin.CameraAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

public class CullingUtils {
    static {
        try {
            System.loadLibrary("scandium_native");
        } catch (UnsatisfiedLinkError e) {
            // Fallback or log error
            System.err.println("Failed to load native library: " + e.getMessage());
        }
    }

    private static native void nativeResetCache();
    private static native int nativeGetCachedHeight(long key);
    private static native void nativePutCachedHeight(long key, int value);
    private static native int nativeGetCachedTransparency(long key);
    private static native void nativePutCachedTransparency(long key, boolean value);
    private static native void nativeCleanupCaches(int playerChunkX, int playerChunkZ);

    private static ClientWorld cachedWorld;
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
    private static boolean cacheValid = false;
    private static boolean prevCachedPlayerUnderground = false;
    private static int undergroundTransitionCounter = 0;

    private static int cacheCleanCounter = 0;
    private static final int MAX_TRANSPARENCY_CACHE_SIZE = 4096;
    private static final int MAX_HEIGHT_CACHE_SIZE = 1024;

    static {
        cachedLookVector = new Vec3d(0, 0, 1);
    }

    public static void resetCache() {
        nativeResetCache();
        cachedWorld = null;
        lastCacheTime = -1;
        cacheValid = false;
        undergroundTransitionCounter = 0;
    }

    public static boolean shouldCull(Box box, int chunkX, int chunkY, int chunkZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return false;

        ScandiumConfig config = ScandiumConfig.getInstance();
        if (!config.enabled) return false;

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
            int interval = Math.max(1, 21 - (config.updateSpeed / 5));
            shouldUpdate = (currentTime - lastCacheTime >= interval || lastCacheTime == -1);
        }

        if (client.world != cachedWorld) {
            cachedWorld = client.world;
            isNether = client.world.getDimensionEntry().value().attributes().containsKey(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY);
            hasCeiling = client.world.getDimensionEntry().value().hasCeiling();
            nativeResetCache();
            lastCacheTime = -1;
            cacheValid = false;
        }

        float currentYaw = camera.getYaw();
        float currentPitch = camera.getPitch();

        double dx = cameraX - cachedCameraX;
        double dy = cameraY - cachedCameraY;
        double dz = cameraZ - cachedCameraZ;
        double movementDistSq = dx * dx + dy * dy + dz * dz;

        final double MOVE_THRESHOLD = 1.0;
        final float ROTATION_THRESHOLD = 2.0f;
        float dyaw = Math.abs(currentYaw - lastYaw);
        if (dyaw > 180) dyaw = 360 - dyaw;
        float dpitch = Math.abs(currentPitch - lastPitch);
        float rotationSpeed = MathHelper.sqrt(dyaw * dyaw + dpitch * dpitch);
        boolean significantRotation = rotationSpeed > ROTATION_THRESHOLD;
        boolean significantMovement = movementDistSq > (MOVE_THRESHOLD * MOVE_THRESHOLD);

        boolean forceUpdate = movementDistSq > 64.0 || (currentTime - lastCacheTime > 100);

        if (forceUpdate || significantMovement || significantRotation || shouldUpdate) {
            cachedCameraX = cameraX;
            cachedCameraY = cameraY;
            cachedCameraZ = cameraZ;
            cachedLookVector = Vec3d.fromPolar(currentPitch, currentYaw);
            lastCacheTime = currentTime;
            cacheValid = true;

            int px = MathHelper.floor(cameraX);
            int pz = MathHelper.floor(cameraZ);
            cachedPlayerSurfaceY = getReliableSurfaceY(client.world, px, pz);

            BlockPos playerPos = new BlockPos(px, MathHelper.floor(cameraY), pz);
            int skyLight = client.world.getLightLevel(net.minecraft.world.LightType.SKY, playerPos);
            boolean newUnderground = (skyLight < 8 && cameraY < (double) (cachedPlayerSurfaceY - 4)) ||
                                     cameraY < (double) (cachedPlayerSurfaceY - 16);

            if (newUnderground != prevCachedPlayerUnderground) {
                undergroundTransitionCounter++;
                if (undergroundTransitionCounter >= 3) {
                    cachedPlayerUnderground = newUnderground;
                    prevCachedPlayerUnderground = newUnderground;
                    undergroundTransitionCounter = 0;
                }
            } else {
                undergroundTransitionCounter = 0;
                cachedPlayerUnderground = newUnderground;
                prevCachedPlayerUnderground = newUnderground;
            }

            if (isNether) {
                cachedPlayerUnderground = true;
                cachedPlayerCeilingY = 120;
            }

            int renderDistance = client.options.getClampedViewDistance();
            cachedReservedHeight = Math.min(config.reservedHeight, renderDistance * 2) * 8;

            if (rotationSpeed > smoothedRotationFactor) {
                smoothedRotationFactor = rotationSpeed;
            } else {
                smoothedRotationFactor *= 0.92;
            }
            lastYaw = currentYaw;
            lastPitch = currentPitch;

            double baseFov = config.fovAngle;
            double dynamicMargin = cachedPlayerUnderground ?
                Math.min(15.0, smoothedRotationFactor * 1.5) :
                Math.min(60.0, smoothedRotationFactor * 4.0);
            double safetyMargin = cachedPlayerUnderground ? 8.0 : 12.0;
            double totalFov = baseFov + dynamicMargin + safetyMargin;

            double halfFovRad = Math.toRadians(totalFov / 2.0);
            fovCosineThreshold = Math.cos(halfFovRad);

            if (config.debugMode) {
                ScandiumClient.debugCachedSurfaceY = cachedPlayerSurfaceY;
                ScandiumClient.debugCachedUnderground = cachedPlayerUnderground;
            }

            cacheCleanCounter++;
            if (cacheCleanCounter >= 60) {
                int playerChunkX = ((int)client.player.getX()) >> 4;
                int playerChunkZ = ((int)client.player.getZ()) >> 4;
                nativeCleanupCaches(playerChunkX, playerChunkZ);
                cacheCleanCounter = 0;
            }
        }

        if (!cacheValid) {
            return false;
        }

        if (config.debugMode) {
            ScandiumClient.TOTAL_CHECKED++;
            ScandiumClient.HUD_TOTAL_CHECKED++;
        }

        if (config.transparencyAwareness) {
            if (isChunkTransparentFast(client.world, chunkX, chunkY, chunkZ)) {
                return false;
            }
        }

        if (config.fovCullingEnabled) {
            Vec3d look = cachedLookVector;
            double centerX = box.minX + 8.0;
            double centerY = box.minY + 8.0;
            double centerZ = box.minZ + 8.0;

            double dx_chunk = centerX - cameraX;
            double dy_chunk = centerY - cameraY;
            double dz_chunk = centerZ - cameraZ;
            double distSq = dx_chunk*dx_chunk + dy_chunk*dy_chunk + dz_chunk*dz_chunk;

            double minFovDistSq = cachedPlayerUnderground ? 256 : 400;

            if (distSq > minFovDistSq) {
                double invDist = 1.0 / Math.sqrt(distSq);
                double dot = (look.x * dx_chunk + look.y * dy_chunk + look.z * dz_chunk) * invDist;

                double currentThreshold = fovCosineThreshold;
                if (cachedPlayerUnderground && config.aggressiveVerticalCulling) {
                    double tightenedFov = config.fovAngle + 4.0;
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

        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        int surfaceY = nativeGetCachedHeight(key);
        if (surfaceY == -1) {
            surfaceY = getReliableSurfaceY(client.world, (chunkX << 4) + 8, (chunkZ << 4) + 8);
            nativePutCachedHeight(key, surfaceY);
        }

        if (config.aggressiveMountainCulling) {
            if (isNether || (hasCeiling && cameraY < 120)) {
                if (cameraY < 110 && box.minY > 128) {
                    markCulled(config, "mountain");
                    return true;
                }
            } else if (cachedPlayerUnderground) {
                if (box.minY > (double) (surfaceY + 8)) {
                    double dx_mountain = (box.minX + 8.0) - cameraX;
                    double dz_mountain = (box.minZ + 8.0) - cameraZ;
                    double dy_mountain = (box.minY + 8.0) - cameraY;
                    if (dx_mountain * dx_mountain + dz_mountain * dz_mountain > 1024) {
                        Vec3d look = cachedLookVector;
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

        if (isNether) {
            double diffY = Math.abs((box.minY + 8.0) - cameraY);
            if (diffY > (double) (cachedReservedHeight + 32)) {
                markCulled(config, "vertical");
                return true;
            }
            return false;
        }

        if (!cachedPlayerUnderground) {
            int verticalOffset = config.aggressiveVerticalCulling ? 32 : 64;
            if (box.maxY < (double) (surfaceY - verticalOffset) && box.maxY < cameraY - verticalOffset) {
                markCulled(config, "vertical");
                return true;
            }
        } else {
            if (config.undergroundHorizontalCulling) {
                double dx_u = (box.minX + 8.0) - cameraX;
                double dz_u = (box.minZ + 8.0) - cameraZ;
                double horizontalDistSq = dx_u * dx_u + dz_u * dz_u;
                double maxHorizontalDist = config.undergroundHorizontalDistance << 4;

                if (horizontalDistSq > maxHorizontalDist * maxHorizontalDist) {
                    Vec3d look = cachedLookVector;

                    double dy_u = (box.minY + 8.0) - cameraY;
                    double dist = Math.sqrt(horizontalDistSq + dy_u * dy_u);
                    double dot = 0;
                    if (dist > 0.1) {
                        dot = (look.x * dx_u + look.y * dy_u + look.z * dz_u) / dist;
                    }

                    if (dot >= fovCosineThreshold) {
                    } else {
                        markCulled(config, "mountain");
                        return true;
                    }
                }
            }

            double diffY = Math.abs((box.minY + 8.0) - cameraY);
            int undergroundOffset = config.aggressiveVerticalCulling ? (cachedReservedHeight / 2) : cachedReservedHeight;
            if (diffY > (double) undergroundOffset) {
                double dx_v = (box.minX + 8.0) - cameraX;
                double dz_v = (box.minZ + 8.0) - cameraZ;
                double dy_v = (box.minY + 8.0) - cameraY;
                Vec3d look = cachedLookVector;
                double dist = Math.sqrt(dx_v * dx_v + dy_v * dy_v + dz_v * dz_v);
                if (dist > 0.1) {
                    double dot = (look.x * dx_v + look.y * dy_v + look.z * dz_v) / dist;
                    if (dot >= fovCosineThreshold) {
                    } else {
                        markCulled(config, "vertical");
                        return true;
                    }
                }
            }
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

    private static boolean isChunkTransparentFast(ClientWorld world, int cx, int cy, int cz) {
        long key = ((long) cx & 0xFFFFFFL) | (((long) cy & 0xFFFFFFL) << 24) | (((long) cz & 0xFFFFFFL) << 48);
        int cached = nativeGetCachedTransparency(key);
        if (cached != -1) return cached == 1;

        Chunk chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return false;

        boolean transparent = false;
        int startX = cx << 4;
        int startY = cy << 4;
        int startZ = cz << 4;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        int[] samples = {0, 7, 15};
        outer:
        for (int y : samples) {
            for (int x : samples) {
                for (int z : samples) {
                    pos.set(startX + x, startY + y, startZ + z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (!state.isOpaque() || state.isOf(Blocks.WATER) || state.isOf(Blocks.GLASS)) {
                        transparent = true;
                        break outer;
                    }
                }
            }
        }
        nativePutCachedTransparency(key, transparent);
        return transparent;
    }

    public static int getReliableSurfaceY(ClientWorld world, int x, int z) {
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
             return world.getBottomY();
        }

        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
    }
}
