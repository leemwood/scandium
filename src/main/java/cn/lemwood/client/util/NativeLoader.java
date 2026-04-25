package cn.lemwood.client.util;

import java.io.*;
import java.nio.file.*;

public class NativeLoader {
    private static final String LIB_NAME = "scandium_native";

    public static void load() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            boolean isAndroid = System.getProperty("java.vendor").toLowerCase().contains("android") || 
                               System.getProperty("java.vm.vendor").toLowerCase().contains("android") ||
                               System.getProperty("java.runtime.name").toLowerCase().contains("android");

            String resourcePath;
            String extension;

            if (isAndroid) {
                extension = ".so";
                String androidArch = getAndroidArch(arch);
                resourcePath = "/lib/" + androidArch + "/lib" + LIB_NAME + extension;
            } else {
                if (os.contains("win")) {
                    extension = ".dll";
                    resourcePath = "/natives/" + LIB_NAME + extension;
                } else if (os.contains("mac")) {
                    extension = ".dylib";
                    resourcePath = "/natives/lib" + LIB_NAME + extension;
                } else {
                    extension = ".so";
                    resourcePath = "/natives/lib" + LIB_NAME + extension;
                }
            }

            loadNative(resourcePath, extension);
        } catch (Throwable t) {
            System.err.println("Failed to load native library: " + t.getMessage());
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
            // Try alternative path for desktop if not found in /natives/
            if (resourcePath.startsWith("/natives/")) {
                String altPath = resourcePath.replace("/natives/", "/");
                is = NativeLoader.class.getResourceAsStream(altPath);
            }
            if (is == null) {
                throw new FileNotFoundException("Could not find native library in JAR: " + resourcePath);
            }
        }

        File tempFile = File.createTempFile(LIB_NAME, extension);
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
