package com.thepf.sable.android.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Mixin(value = Rapier3D.class, remap = false)
public class SableAndroidLibsMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private static void onLoadLibrary(CallbackInfo ci) {
        String osVersion = System.getProperty("os.version", "");
        if (!osVersion.contains("Android")) return;

        ci.cancel();
        try {
            String nativeName = "sable_rapier_aarch64_linux.so";
            try (InputStream is = SableAndroidLibsMixin.class
                    .getResourceAsStream("/natives/sable_rapier_android/" + nativeName)) {
                if (is == null) throw new Exception("Android native not found in jar: " + nativeName);
                Path tempFile = Files.createTempFile("sable_rapier_android_natives", null);
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                System.load(tempFile.toAbsolutePath().toString());
                Rapier3D.ENABLED = true;
            }
        } catch (Throwable t) {
            Rapier3D.ENABLED = false;
            System.err.println("[SableAndroidLibs] Failed to load Android native: " + t.getMessage());
        }
    }
}
