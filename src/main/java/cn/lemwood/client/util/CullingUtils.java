package cn.lemwood.client.util;

import cn.lemwood.client.ScandiumClient;
import cn.lemwood.config.ScandiumConfig;
import cn.lemwood.mixin.CameraAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

public class CullingUtils {
    static {
        NativeLoader.load();
    }

    private static native void nativeResetCache();
    private static native int nativeGetCachedHeight(long key);
    private static native void nativePutCachedHeight(long key, int value);
    private static native int nativeGetCachedTransparency(long key);
    private static native void nativePutCachedTransparency(long key, boolean value);
    private static native void nativeCleanupCaches(int playerChunkX, int playerChunkZ);
    private static native void nativeUpdateFrameState(
        double camX, double camY, double camZ,
        double lookX, double lookY, double lookZ,
        double fovCos, boolean isUnderground, boolean isNether, boolean hasCeiling,
        int reservedHeight, boolean fovEnabled, boolean mountainEnabled, boolean verticalEnabled,
        boolean aggressiveVertical, boolean undergroundHorizontal, int undergroundHorizontalDist
    );
    private static native int nativeShouldCull(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int chunkX, int chunkZ
    );
    private static native int[] nativeShouldCullBatch(
        int[] minXS, int[] minYS, int[] minZS,
        int[] maxYS, int[] chunkXS, int[] chunkZS,
        int count
    );

    private static final ThreadLocal<BlockPos.Mutable> MUTABLE_POS = ThreadLocal.withInitial(BlockPos.Mutable::new);
    private static final int BATCH_THRESHOLD = 8;

    private static final ThreadLocal<int[]> BATCH_MIN_X = ThreadLocal.withInitial(() -> new int[64]);
    private static final ThreadLocal<int[]> BATCH_MIN_Y = ThreadLocal.withInitial(() -> new int[64]);
    private static final ThreadLocal<int[]> BATCH_MIN_Z = ThreadLocal.withInitial(() -> new int[64]);
    private static final ThreadLocal<int[]> BATCH_MAX_Y = ThreadLocal.withInitial(() -> new int[64]);
    private static final ThreadLocal<int[]> BATCH_CHUNK_X = ThreadLocal.withInitial(() -> new int[64]);
    private static final ThreadLocal<int[]> BATCH_CHUNK_Z = ThreadLocal.withInitial(() -> new int[64]);

    private static ClientWorld cachedWorld;
    private static long lastCacheTime = -1;
    private static int cachedPlayerSurfaceY;
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
    private static int pendingBatchCount = 0;

    static {
        cachedLookVector = new Vec3d(0, 0, 1);
    }

    public static void resetCache() {
        nativeResetCache();
        cachedWorld = null;
        lastCacheTime = -1;
        cacheValid = false;
        undergroundTransitionCounter = 0;
        pendingBatchCount = 0;
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
            flushBatch();
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
            flushBatch();

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

            nativeUpdateFrameState(
                cameraX, cameraY, cameraZ,
                cachedLookVector.x, cachedLookVector.y, cachedLookVector.z,
                fovCosineThreshold, cachedPlayerUnderground, isNether, hasCeiling,
                cachedReservedHeight, config.fovCullingEnabled, config.aggressiveMountainCulling, 
                config.enabled, config.aggressiveVerticalCulling,
                config.undergroundHorizontalCulling, config.undergroundHorizontalDistance
            );

            if (ScandiumClient.isDebugHudOpen) {
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

        if (ScandiumClient.isDebugHudOpen) {
            ScandiumClient.TOTAL_CHECKED++;
        }

        if (config.transparencyAwareness) {
            if (isChunkTransparentFast(client.world, chunkX, chunkY, chunkZ)) {
                return false;
            }
        }

        int cullResult = nativeShouldCull(
            box.minX, box.minY, box.minZ,
            box.maxX, box.maxY, box.maxZ,
            chunkX, chunkZ
        );

        if (cullResult != 0) {
            if (ScandiumClient.isDebugHudOpen) {
                ScandiumClient.CULLED_COUNT++;
                if (cullResult == 1) ScandiumClient.CULLED_FOV++;
                else if (cullResult == 2) ScandiumClient.CULLED_MOUNTAIN++;
                else if (cullResult == 3) ScandiumClient.CULLED_VERTICAL++;
                else if (cullResult == 4) ScandiumClient.CULLED_HORIZONTAL++;
            }
            return true;
        }

        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        if (nativeGetCachedHeight(key) == -1) {
            int surfaceY = getReliableSurfaceY(client.world, (chunkX << 4) + 8, (chunkZ << 4) + 8);
            nativePutCachedHeight(key, surfaceY);
        }

        return false;
    }

    public static void ensureBatchHeight(int chunkX, int chunkZ, ClientWorld world) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        if (nativeGetCachedHeight(key) == -1) {
            int surfaceY = getReliableSurfaceY(world, (chunkX << 4) + 8, (chunkZ << 4) + 8);
            nativePutCachedHeight(key, surfaceY);
        }
    }

    public static int processBatch(int[] minXS, int[] minYS, int[] minZS, int[] maxYS, int[] chunkXS, int[] chunkZS, int count) {
        if (count == 0) return 0;
        int[] results = nativeShouldCullBatch(minXS, minYS, minZS, maxYS, chunkXS, chunkZS, count);
        int culledCount = 0;
        for (int i = 0; i < count; i++) {
            if (results[i] != 0) {
                culledCount++;
                if (ScandiumClient.isDebugHudOpen) {
                    ScandiumClient.TOTAL_CHECKED++;
                    ScandiumClient.CULLED_COUNT++;
                    int result = results[i];
                    if (result == 1) ScandiumClient.CULLED_FOV++;
                    else if (result == 2) ScandiumClient.CULLED_MOUNTAIN++;
                    else if (result == 3) ScandiumClient.CULLED_VERTICAL++;
                    else if (result == 4) ScandiumClient.CULLED_HORIZONTAL++;
                }
            }
        }
        return culledCount;
    }

    public static boolean isBatchResultCulled(int[] results, int index) {
        return results != null && index < results.length && results[index] != 0;
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

        BlockPos.Mutable pos = MUTABLE_POS.get();
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

    private static void flushBatch() {
        pendingBatchCount = 0;
    }
}
