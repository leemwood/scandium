package cn.lemwood.client.util;

import java.io.*;
import java.nio.file.*;

public class NativeLoader {
    private static final String LIB_NAME = "scandium_native";

    public static void load() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            String vendor = System.getProperty("java.vendor").toLowerCase();
            String vmVendor = System.getProperty("java.vm.vendor").toLowerCase();
            String runtimeName = System.getProperty("java.runtime.name").toLowerCase();
            
            // Enhanced Android detection
            boolean isAndroid = vendor.contains("android") || 
                               vmVendor.contains("android") ||
                               runtimeName.contains("android") ||
                               System.getProperty("os.name").equals("Linux") && System.getenv("ANDROID_ROOT") != null;

            System.out.println("[Scandium] Loading native library. OS: " + os + ", Arch: " + arch + ", Android: " + isAndroid);

            String extension;
            if (os.contains("win")) {
                extension = ".dll";
            } else if (os.contains("mac")) {
                extension = ".dylib";
            } else {
                extension = ".so";
            }

            // Try multiple possible paths in order of probability
            String[] possiblePaths;
            if (isAndroid) {
                String androidArch = getAndroidArch(arch);
                possiblePaths = new String[]{
                    "/lib/" + androidArch + "/lib" + LIB_NAME + ".so",
                    "/natives/lib" + LIB_NAME + ".so",
                    "/lib" + LIB_NAME + ".so"
                };
            } else {
                if (os.contains("win")) {
                    possiblePaths = new String[]{
                        "/natives/" + LIB_NAME + ".dll",
                        "/" + LIB_NAME + ".dll"
                    };
                } else if (os.contains("mac")) {
                    possiblePaths = new String[]{
                        "/natives/lib" + LIB_NAME + ".dylib",
                        "/lib" + LIB_NAME + ".dylib"
                    };
                } else {
                    possiblePaths = new String[]{
                        "/natives/lib" + LIB_NAME + ".so",
                        "/lib/lib" + LIB_NAME + ".so",
                        "/lib" + LIB_NAME + ".so"
                    };
                }
            }

            boolean loaded = false;
            for (String path : possiblePaths) {
                try {
                    System.out.println("[Scandium] Trying to load from: " + path);
                    loadNative(path, extension);
                    loaded = true;
                    System.out.println("[Scandium] Successfully loaded native library from: " + path);
                    break;
                } catch (FileNotFoundException e) {
                    // Continue to next path
                }
            }

            if (!loaded) {
                throw new FileNotFoundException("Could not find native library " + LIB_NAME + " in any of the expected locations.");
            }
        } catch (Throwable t) {
            System.err.println("[Scandium] Failed to load native library: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private static String getAndroidArch(String arch) {
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64-v8a";
        if (arch.contains("arm")) return "armeabi-v7a";
        if (arch.contains("x86_64") || arch.contains("amd64")) return "x86_64";
        if (arch.contains("x86") || arch.contains("i386") || arch.contains("i686")) return "x86";
        return "arm64-v8a"; // Default to arm64-v8a as it's most common now
    }

    private static void loadNative(String resourcePath, String extension) throws IOException {
        InputStream is = NativeLoader.class.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }

        File tempFile = File.createTempFile(LIB_NAME + "_", extension);
        tempFile.deleteOnExit();

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) {
                fos.write(buffer, 0, n);
            }
        } finally {
            is.close();
        }

        System.load(tempFile.getAbsolutePath());
    }
}
