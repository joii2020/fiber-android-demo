package com.example.fiberdemo;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class FiberRuntime {
    private static final String CONFIG_ASSET = "fiber_config.yml";
    private static final String CONFIG_FILE = "fiber_config.yml";
    private static boolean running;
    private static boolean nativeLibrariesLoaded;
    private static String nativeLoadError;

    private FiberRuntime() {
    }

    public static synchronized String start(Context context) {
        String loadError = ensureNativeLibrariesLoaded();
        if (loadError != null) {
            return loadError;
        }

        try {
            File configFile = ensureConfigFile(context.getApplicationContext());
            File dataDir = new File(context.getFilesDir(), "fiber-data");
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                return "Fiber start failed: cannot create data directory";
            }

            String result = nativeStart(
                    configFile.getAbsolutePath(),
                    dataDir.getAbsolutePath(),
                    "info"
            );
            running = result.startsWith("Fiber started") || result.equals("Fiber already running");
            return result;
        } catch (IOException exception) {
            return "Fiber start failed: " + exception.getMessage();
        }
    }

    public static synchronized String stop() {
        String loadError = ensureNativeLibrariesLoaded();
        if (loadError != null) {
            return loadError;
        }

        String result = nativeStop();
        running = !(result.startsWith("Fiber stopped") || result.equals("Fiber already stopped"));
        return result;
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    private static File ensureConfigFile(Context context) throws IOException {
        File configFile = new File(context.getFilesDir(), CONFIG_FILE);

        try (InputStream input = context.getAssets().open(CONFIG_ASSET);
             FileOutputStream output = new FileOutputStream(configFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return configFile;
    }

    private static String ensureNativeLibrariesLoaded() {
        if (nativeLibrariesLoaded) {
            return null;
        }
        if (nativeLoadError != null) {
            return nativeLoadError;
        }

        try {
            System.loadLibrary("fiber_ffi");
            System.loadLibrary("fiber_bridge");
            nativeLibrariesLoaded = true;
            return null;
        } catch (LinkageError error) {
            nativeLoadError = "Fiber start failed: cannot load native library: " + error.getMessage();
            return nativeLoadError;
        }
    }

    private static native String nativeStart(String configPath, String databasePrefix, String logLevel);

    private static native String nativeStop();
}
