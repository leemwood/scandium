package cn.lemwood.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ScandiumConfig {
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "scandium.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public int reservedHeight = 2;
    public int fovAngle = 130;
    public boolean debugMode = false;
    public int updateSpeed = 20;
    public boolean syncWithSodium = true;
    public boolean fovCullingEnabled = true;
    public boolean aggressiveMountainCulling = true;
    public boolean transparencyAwareness = true;
    public boolean ignoreSpectatorMode = true;
    public boolean aggressiveVerticalCulling = false;
    public boolean undergroundHorizontalCulling = true;
    public int undergroundHorizontalDistance = 4;

    private static ScandiumConfig instance;
    private static long lastConfigTimestamp = -1;

    public static ScandiumConfig getInstance() {
        if (instance == null) {
            load();
        } else {
            reloadIfChanged();
        }
        return instance;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                instance = GSON.fromJson(reader, ScandiumConfig.class);
            } catch (IOException e) {
                instance = new ScandiumConfig();
            }
        } else {
            instance = new ScandiumConfig();
            instance.save();
        }
        lastConfigTimestamp = CONFIG_FILE.exists() ? CONFIG_FILE.lastModified() : -1;
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        lastConfigTimestamp = CONFIG_FILE.exists() ? CONFIG_FILE.lastModified() : -1;
    }

    public static void saveStatic() {
        if (instance != null) {
            instance.save();
        }
    }

    public static void reloadIfChanged() {
        if (!CONFIG_FILE.exists()) return;
        long current = CONFIG_FILE.lastModified();
        if (current != lastConfigTimestamp) {
            load();
        }
    }
}
