package com.thepf.sable.android.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;

@Mixin(value = Rapier3D.class, remap = false)
public class SableAndroidLibsMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private static void onLoadLibrary(CallbackInfo ci) {
        String osVersion = System.getProperty("os.version", "");
        if (!osVersion.contains("Android")) return;

        ci.cancel();
        String nativeName = "sable_rapier_aarch64_linux.so";
        String resourcePath = "natives/sable_rapier_android/" + nativeName;

        try {
            InputStream is = findResourceInAnyClassLoader(resourcePath);
            if (is == null) {
                throw new IllegalStateException("Android native not found in any classloader: " + resourcePath);
            }

            // Use launcher-safe tmpdir (Pojav/Zalith configure this correctly)
            String tmpDir = System.getProperty("java.io.tmpdir", System.getProperty("user.home"));
            Path tempFile = Path.of(tmpDir, "sable_rapier_android_" + System.nanoTime() + ".so");
            
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            is.close();

            System.load(tempFile.toAbsolutePath().toString());
            Rapier3D.ENABLED = true;
            System.out.println("[SableAndroidLibs] ✅ Successfully loaded Android native from: " + tempFile);
            tempFile.toFile().deleteOnExit();
            
        } catch (Throwable t) {
            Rapier3D.ENABLED = false;
            System.err.println("[SableAndroidLibs] ❌ Failed to load Android native: " + t.getMessage());
            t.printStackTrace();
        }
    }

    // Bypasses NeoForge/Mixin classloader isolation
    private static InputStream findResourceInAnyClassLoader(String path) throws Exception {
        String pathWithSlash = "/" + path;
        
        // 1. Try the mixin class's own loader
        InputStream is = SableAndroidLibsMixin.class.getResourceAsStream(pathWithSlash);
        if (is != null) return is;

        // 2. Try explicit classloader (no leading slash)
        is = SableAndroidLibsMixin.class.getClassLoader().getResourceAsStream(path);
        if (is != null) return is;

        // 3. Try thread context classloader
        is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (is != null) return is;

        // 4. Fallback: Scan ALL classloaders for the resource
        Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources(path);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            System.out.println("[SableAndroidLibs] Found resource URL: " + url);
            return url.openStream();
        }

        // Debug: print what classloader we're actually in
        System.err.println("[SableAndroidLibs] Mixin loaded by: " + SableAndroidLibsMixin.class.getClassLoader());
        return null;
    }
}
