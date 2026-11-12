package dev.localswitch.privatedns;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A short-lived Shizuku UserService, not an Android background service. */
public final class PermissionGrantService extends IPermissionGrantService.Stub {
    private static final String APPLICATION_ID = "dev.localswitch.privatedns";
    private static final int OUTPUT_LIMIT = 4096;
    static final String ERROR_UNSUPPORTED = "unsupported";
    static final String ERROR_APP_MISSING = "app_missing";
    static final String ERROR_BUSY = "busy";
    static final String ERROR_TIMEOUT = "command_timeout";
    static final String ERROR_REFUSED = "refused";
    static final String ERROR_EXECUTE = "execute";
    static final String ERROR_INTERRUPTED = "interrupted";
    private final PackageManager packageManager;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile java.lang.Process command;

    /** Required for UserService instantiation. Shizuku 13+ uses the Context constructor. */
    public PermissionGrantService() {
        packageManager = null;
    }

    /** Shizuku creates this context for the application user requesting the service. */
    public PermissionGrantService(Context context) {
        packageManager = context.getPackageManager();
    }

    @Override
    public String grantWriteSecureSettings() {
        if (packageManager == null) return ERROR_UNSUPPORTED;

        // Capture Binder identity before dispatching or clearing it: this process itself is shell/root.
        int callingUid = Binder.getCallingUid();
        try {
            if (callingUid != packageManager.getPackageUid(APPLICATION_ID, 0)) {
                throw new SecurityException("Permission grant caller UID mismatch");
            }
        } catch (PackageManager.NameNotFoundException exception) {
            return ERROR_APP_MISSING;
        }
        int userId = UserHandle.getUserHandleForUid(callingUid).hashCode();
        if (!running.compareAndSet(false, true)) return ERROR_BUSY;

        ExecutorService outputReader = Executors.newSingleThreadExecutor();
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/pm", "grant", "--user",
                    Integer.toString(userId), APPLICATION_ID,
                    Manifest.permission.WRITE_SECURE_SETTINGS)
                    .redirectErrorStream(true)
                    .start();
            command = process;
            final InputStream stream = process.getInputStream();
            Future<String> output = outputReader.submit(() -> readOutput(stream));
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return ERROR_TIMEOUT;
            }
            String detail;
            try {
                detail = output.get(1, TimeUnit.SECONDS).trim();
            } catch (Exception ignored) {
                detail = "";
            }
            if (process.exitValue() != 0) {
                return ERROR_REFUSED + (detail.isEmpty() ? "" : "\n" + detail);
            }
            return "";
        } catch (IOException exception) {
            return ERROR_EXECUTE + "\n" + exception.getMessage();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ERROR_INTERRUPTED;
        } finally {
            command = null;
            if (process != null) {
                process.destroy();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
            outputReader.shutdownNow();
            running.set(false);
        }
    }

    private static String readOutput(InputStream stream) throws IOException {
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int size;
        while ((size = stream.read(buffer)) != -1) {
            // Continue draining after the limit so a verbose command cannot block or exhaust memory.
            int remaining = OUTPUT_LIMIT - saved.size();
            if (remaining > 0) saved.write(buffer, 0, Math.min(size, remaining));
        }
        return saved.toString(StandardCharsets.UTF_8.name());
    }

    @Override
    public void destroy() {
        java.lang.Process process = command;
        if (process != null) process.destroyForcibly();
        System.exit(0);
    }
}
