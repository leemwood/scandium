package cn.lemwood.client;

import cn.lemwood.config.ScandiumConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public class ScandiumClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("Scandium");
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    public static int CULLED_COUNT = 0;
    public static int CULLED_VERTICAL = 0;
    public static int CULLED_BACK = 0;
    public static int CULLED_FOV = 0;
    public static int CULLED_MOUNTAIN = 0;
    public static int TOTAL_CHECKED = 0;

    // HUD specific counters
    public static int HUD_CULLED_COUNT = 0;
    public static int HUD_CULLED_VERTICAL = 0;
    public static int HUD_CULLED_BACK = 0;
    public static int HUD_CULLED_FOV = 0;
    public static int HUD_CULLED_MOUNTAIN = 0;
    public static int HUD_TOTAL_CHECKED = 0;

    public static boolean debugCachedUnderground = false;
    public static int debugCachedSurfaceY = 0;

    private int tickCounter = 0;

    private static boolean irisApiResolved = false;
    private static Object irisApiInstance;
    private static Method irisIsShadowPassMethod;
    private static int cachedShadowFrameIndex = Integer.MIN_VALUE;
    private static boolean cachedShadowPass = false;
    private static long lastShadowQueryTime = -1;

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
        long time = System.currentTimeMillis();
        if (time == lastShadowQueryTime) {
            return cachedShadowPass;
        }
        lastShadowQueryTime = time;
        cachedShadowPass = queryIrisShadowPass();
        return cachedShadowPass;
    }

    private static boolean queryIrisShadowPass() {
        try {
            if (!irisApiResolved) {
                irisApiResolved = true;
                Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Method getInstance = irisApiClass.getMethod("getInstance");
                irisApiInstance = getInstance.invoke(null);
                irisIsShadowPassMethod = irisApiClass.getMethod("isRenderingShadowPass");
            }
            if (irisApiInstance == null || irisIsShadowPassMethod == null) return false;
            Object result = irisIsShadowPassMethod.invoke(irisApiInstance);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            irisApiResolved = true;
            irisApiInstance = null;
            irisIsShadowPassMethod = null;
            return false;
        }
    }

    @Override
    public void onInitializeClient() {
        ScandiumConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ScandiumConfig.getInstance().debugMode && client.player != null) {
                tickCounter++;
                if (tickCounter >= 20) {
                    String text = "Scandium | " + 
                        "Total: " + TOTAL_CHECKED + " | " +
                        "FOV: " + CULLED_FOV + " | " +
                        "Vert: " + CULLED_VERTICAL + " | " +
                        "Mtn: " + CULLED_MOUNTAIN + " | " +
                        "Y: " + (int)client.player.getY() + " (S:" + debugCachedSurfaceY + ") | " +
                        "UG: " + debugCachedUnderground;
                    client.player.sendMessage(Text.literal(text), true);
                    
                    tickCounter = 0;
                    TOTAL_CHECKED = 0;
                    CULLED_FOV = 0;
                    CULLED_VERTICAL = 0;
                    CULLED_MOUNTAIN = 0;
                    CULLED_BACK = 0;
                    CULLED_COUNT = 0;
                }
            } else {
                TOTAL_CHECKED = 0;
                CULLED_FOV = 0;
                CULLED_VERTICAL = 0;
                CULLED_MOUNTAIN = 0;
                CULLED_BACK = 0;
                CULLED_COUNT = 0;
                HUD_TOTAL_CHECKED = 0;
                HUD_CULLED_FOV = 0;
                HUD_CULLED_VERTICAL = 0;
                HUD_CULLED_MOUNTAIN = 0;
                HUD_CULLED_BACK = 0;
                HUD_CULLED_COUNT = 0;
            }
        });
    }
}
