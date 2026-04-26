package cn.lemwood.client;

import cn.lemwood.config.ScandiumConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class ScandiumClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("Scandium");
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    public static int CULLED_COUNT = 0;
    public static int CULLED_VERTICAL = 0;
    public static int CULLED_BACK = 0;
    public static int CULLED_FOV = 0;
    public static int CULLED_MOUNTAIN = 0;
    public static int CULLED_HORIZONTAL = 0;
    public static int TOTAL_CHECKED = 0;

    public static boolean debugCachedUnderground = false;
    public static int debugCachedSurfaceY = 0;
    public static boolean isDebugHudOpen = false;

    private static boolean irisApiResolved = false;
    private static MethodHandle irisIsShadowPassHandle;
    private static int cachedShadowFrameIndex = -1;
    private static boolean cachedShadowPass = false;

    public static boolean isIrisShadowPass(int frameIndex) {
        if (!IRIS_LOADED) return false;
        if (cachedShadowFrameIndex == frameIndex) {
            return cachedShadowPass;
        }

        cachedShadowFrameIndex = frameIndex;
        cachedShadowPass = queryIrisShadowPass();
        return cachedShadowPass;
    }

    public static boolean isRenderingShadowPass() {
        if (!IRIS_LOADED) return false;
        // 直接查询 Iris API，MethodHandle 性能接近原生调用
        return queryIrisShadowPass();
    }

    private static boolean queryIrisShadowPass() {
        try {
            if (!irisApiResolved) {
                resolveIrisApi();
            }
            if (irisIsShadowPassHandle == null) return false;
            return (boolean) irisIsShadowPassHandle.invokeExact();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void resolveIrisApi() {
        irisApiResolved = true;
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle getInstance = lookup.findStatic(irisApiClass, "getInstance", MethodType.methodType(irisApiClass));
            Object apiInstance = getInstance.invoke();
            irisIsShadowPassHandle = lookup.findVirtual(irisApiClass, "isRenderingShadowPass", MethodType.methodType(boolean.class))
                                           .bindTo(apiInstance);
        } catch (Throwable e) {
            LOGGER.error("Failed to resolve Iris API handles", e);
        }
    }

    @Override
    public void onInitializeClient() {
        ScandiumConfig.load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isDebugHudOpen || client.player == null) {
                TOTAL_CHECKED = 0;
                CULLED_FOV = 0;
                CULLED_VERTICAL = 0;
                CULLED_MOUNTAIN = 0;
                CULLED_HORIZONTAL = 0;
                CULLED_BACK = 0;
                CULLED_COUNT = 0;
            }
            isDebugHudOpen = false;
        });
    }
}
