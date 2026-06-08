package com.example.fiberdemo;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FiberRuntime {
    private static final String CONFIG_ASSET = "fiber_config.yml";
    private static final String CONFIG_FILE = "fiber_config.yml";
    private static final String CKB_KEY_FILE = "key";
    private static final BigInteger SECP256K1_ORDER =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final List<NativeEventListener> nativeEventListeners = new CopyOnWriteArrayList<>();
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
            ensureCkbKeyFile(dataDir);

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

    public static synchronized NativeResult nodeInfo() {
        String loadError = ensureNativeLibrariesLoaded();
        if (loadError != null) {
            return NativeResult.error(loadError);
        }
        return NativeResult.fromPrefixed(nativeNodeInfo());
    }

    public static synchronized NativeResult listPeers() {
        String loadError = ensureNativeLibrariesLoaded();
        if (loadError != null) {
            return NativeResult.error(loadError);
        }
        return NativeResult.fromPrefixed(nativeListPeers());
    }

    public static synchronized NativeResult connectPeer(
            String address,
            String pubkey,
            String addrType,
            boolean save
    ) {
        String loadError = ensureNativeLibrariesLoaded();
        if (loadError != null) {
            return NativeResult.error(loadError);
        }
        return NativeResult.fromPrefixed(nativeConnectPeer(
                emptyToNull(address),
                emptyToNull(pubkey),
                emptyToNull(addrType),
                save
        ));
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    public static void addNativeEventListener(NativeEventListener listener) {
        nativeEventListeners.add(listener);
    }

    public static void removeNativeEventListener(NativeEventListener listener) {
        nativeEventListeners.remove(listener);
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

    private static void ensureCkbKeyFile(File dataDir) throws IOException {
        File ckbDir = new File(dataDir, "ckb");
        if (!ckbDir.exists() && !ckbDir.mkdirs()) {
            throw new IOException("cannot create ckb data directory");
        }

        File keyFile = new File(ckbDir, CKB_KEY_FILE);
        if (keyFile.exists()) {
            return;
        }

        try (FileOutputStream output = new FileOutputStream(keyFile)) {
            output.write((randomSecretKeyHex() + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    private static String randomSecretKeyHex() {
        byte[] key = new byte[32];
        BigInteger value;
        do {
            SECURE_RANDOM.nextBytes(key);
            value = new BigInteger(1, key);
        } while (value.signum() == 0 || value.compareTo(SECP256K1_ORDER) >= 0);

        char[] hex = new char[key.length * 2];
        for (int i = 0; i < key.length; i++) {
            int unsignedByte = key[i] & 0xff;
            hex[i * 2] = HEX[unsignedByte >>> 4];
            hex[i * 2 + 1] = HEX[unsignedByte & 0x0f];
        }
        return new String(hex);
    }

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static void onNativeEvent(String eventJson) {
        for (NativeEventListener listener : nativeEventListeners) {
            listener.onNativeEvent(eventJson);
        }
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

    private static native String nativeNodeInfo();

    private static native String nativeListPeers();

    private static native String nativeConnectPeer(String address, String pubkey, String addrType, boolean save);

    public interface NativeEventListener {
        void onNativeEvent(String eventJson);
    }

    public static final class NativeResult {
        public final boolean success;
        public final String value;
        public final String error;

        private NativeResult(boolean success, String value, String error) {
            this.success = success;
            this.value = value;
            this.error = error;
        }

        public static NativeResult error(String error) {
            return new NativeResult(false, null, error);
        }

        private static NativeResult fromPrefixed(String value) {
            if (value == null) {
                return error("Fiber call failed: empty native response");
            }
            if (value.startsWith("OK\n")) {
                return new NativeResult(true, value.substring(3), null);
            }
            if (value.startsWith("ERROR\n")) {
                return error(value.substring(6));
            }
            return error(value);
        }
    }
}
