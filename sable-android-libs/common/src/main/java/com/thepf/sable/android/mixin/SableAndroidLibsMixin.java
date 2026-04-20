package com.thepf.sable.android.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mixin(value = Rapier3D.class, remap = false)
public class SableAndroidLibsMixin {

    // -----------------------------------------------------------------------
    // Path inside your injector jar where the .so lives:
    //   src/main/resources/natives/sable_rapier_android/sable_rapier_aarch64_linux.so
    // -----------------------------------------------------------------------
    private static final String NATIVE_NAME   = "sable_rapier_aarch64_linux.so";
    private static final String RESOURCE_PATH = "natives/sable_rapier_android/" + NATIVE_NAME;

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private static void onLoadLibrary(CallbackInfo ci) {
        if (!isAndroid()) return;

        // Cancel Sable's own desktop library loading; we do it ourselves.
        ci.cancel();

        try {
            InputStream nativeStream = findResource(RESOURCE_PATH);

            if (nativeStream == null) {
                throw new IOException(
                    "[SableAndroidLibs] Could not find '" + RESOURCE_PATH +
                    "' via any classloader strategy. " +
                    "Make sure the .so is bundled inside your mod jar."
                );
            }

            // Extract to a real temp file so System.load() can read it.
            Path tempFile = Files.createTempFile("sable_rapier_android_", ".so");
            tempFile.toFile().deleteOnExit();

            try (nativeStream) {
                Files.copy(nativeStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            System.load(tempFile.toAbsolutePath().toString());
            Rapier3D.ENABLED = true;
            System.out.println("[SableAndroidLibs] Native loaded successfully: " + NATIVE_NAME);

        } catch (Throwable t) {
            Rapier3D.ENABLED = false;
            System.err.println("[SableAndroidLibs] Failed to load Android native: " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    // -----------------------------------------------------------------------
    //  Multi-strategy resource finder
    //  NeoForge uses SecureJar / ModLauncher with "union:" URLs, so normal
    //  getResourceAsStream() often returns null.  We try five strategies.
    // -----------------------------------------------------------------------
    private static InputStream findResource(String path) {

        // ── Strategy 1 ──────────────────────────────────────────────────────
        // Class.getResourceAsStream with leading "/" → searches classpath root.
        InputStream is = SableAndroidLibsMixin.class.getResourceAsStream("/" + path);
        if (is != null) {
            log("Resource found via Class.getResourceAsStream (strategy 1)");
            return is;
        }

        // ── Strategy 2 ──────────────────────────────────────────────────────
        // ClassLoader.getResourceAsStream WITHOUT leading "/" (always absolute
        // for ClassLoader, unlike Class).
        ClassLoader cl = SableAndroidLibsMixin.class.getClassLoader();
        if (cl != null) {
            is = cl.getResourceAsStream(path);
            if (is != null) {
                log("Resource found via own ClassLoader (strategy 2)");
                return is;
            }
        }

        // ── Strategy 3 ──────────────────────────────────────────────────────
        // Thread context classloader — often different from the mod classloader.
        ClassLoader tcl = Thread.currentThread().getContextClassLoader();
        if (tcl != null && tcl != cl) {
            is = tcl.getResourceAsStream(path);
            if (is != null) {
                log("Resource found via Thread context ClassLoader (strategy 3)");
                return is;
            }
        }

        // ── Strategy 4 ──────────────────────────────────────────────────────
        // Locate our own jar via CodeSource → open as ZipFile.
        // Bypasses classloader resource lookup entirely.
        try {
            URL codeSource = SableAndroidLibsMixin.class
                    .getProtectionDomain().getCodeSource().getLocation();
            if (codeSource != null) {
                InputStream zipIs = readFromZip(new File(codeSource.toURI()), path);
                if (zipIs != null) {
                    log("Resource found via CodeSource ZipFile (strategy 4)");
                    return zipIs;
                }
            }
        } catch (Exception e) {
            log("Strategy 4 failed: " + e.getMessage());
        }

        // ── Strategy 5 ──────────────────────────────────────────────────────
        // Parse the class-file URL to locate the containing jar.
        // NeoForge SecureJar produces URLs like:
        //   union:/path/to/mod.jar%23156!/com/thepf/sable/...class
        // Standard JVM produces:
        //   jar:file:/path/to/mod.jar!/com/thepf/sable/...class
        try {
            String className = SableAndroidLibsMixin.class.getName()
                    .replace('.', '/') + ".class";

            // Use own classloader first, fall back to context classloader.
            URL classUrl = cl != null
                    ? cl.getResource(className)
                    : (tcl != null ? tcl.getResource(className) : null);

            if (classUrl != null) {
                log("Class URL for strategy 5: " + classUrl);
                File jarFile = resolveJarFromUrl(classUrl.toString());
                if (jarFile != null && jarFile.exists()) {
                    InputStream zipIs = readFromZip(jarFile, path);
                    if (zipIs != null) {
                        log("Resource found via class-URL ZipFile (strategy 5)");
                        return zipIs;
                    }
                }
            }
        } catch (Exception e) {
            log("Strategy 5 failed: " + e.getMessage());
        }

        return null; // All strategies exhausted
    }

    // -----------------------------------------------------------------------
    //  Reads a ZipEntry from a jar File and returns a self-closing InputStream.
    // -----------------------------------------------------------------------
    private static InputStream readFromZip(File jarFile, String entryPath) {
        if (jarFile == null || !jarFile.isFile()) return null;
        try {
            ZipFile zip  = new ZipFile(jarFile);
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) {
                zip.close();
                return null;
            }
            // Return a FilterInputStream that also closes the ZipFile.
            return new FilterInputStream(zip.getInputStream(entry)) {
                @Override
                public void close() throws IOException {
                    super.close();
                    zip.close();
                }
            };
        } catch (Exception e) {
            log("readFromZip failed for " + jarFile + ": " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Parses a class-file URL to find the .jar File it came from.
    //
    //  Handles:
    //    jar:file:/path/to/mod.jar!/com/...          (standard JVM)
    //    union:/path/to/mod.jar%23156!/com/...       (NeoForge SecureJar)
    // -----------------------------------------------------------------------
    private static File resolveJarFromUrl(String urlStr) {
        try {
            String s = urlStr;

            // Strip known protocol prefixes.
            if (s.startsWith("jar:"))   s = s.substring(4);
            if (s.startsWith("union:")) s = s.substring(6);

            // Everything before the first "!" is the jar path portion.
            int bang = s.indexOf('!');
            if (bang != -1) s = s.substring(0, bang);

            // NeoForge appends %23<number> (encoded "#") before "!" to embed
            // an index.  Strip that too.
            int enc = s.indexOf("%23");
            if (enc != -1) s = s.substring(0, enc);

            // At this point "s" should be "file:/path/to/mod.jar" or
            // "/path/to/mod.jar".
            if (!s.startsWith("file:")) s = "file:" + s;

            return new File(new URI(s));
        } catch (Exception e) {
            log("resolveJarFromUrl failed for '" + urlStr + "': " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    //  Android / PojavLauncher-family detection.
    //  Checks multiple env-vars set by different launcher forks.
    // -----------------------------------------------------------------------
    private static boolean isAndroid() {
        // Zalith / PojavLauncher set os.version to "Android-<API>"
        if (System.getProperty("os.version", "").contains("Android")) return true;
        // All PojavLauncher forks set POJAV_NATIVEDIR
        if (System.getenv("POJAV_NATIVEDIR") != null) return true;
        // Zalith specifically sets ZALITH_VERSION_CODE
        if (System.getenv("ZALITH_VERSION_CODE") != null) return true;
        return false;
    }

    private static void log(String msg) {
        System.out.println("[SableAndroidLibs] " + msg);
    }
}
