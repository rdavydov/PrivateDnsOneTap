package dev.localswitch.privatedns;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Lifecycle-bound UI coordinator for an optional, one-time Shizuku permission grant. */
final class ShizukuPermissionController implements AutoCloseable {
    static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQUEST_PERMISSION = 54231;
    private static final long BIND_TIMEOUT_MS = 10_000;
    private static final long CALL_TIMEOUT_MS = 12_000;

    enum State {
        ABSENT, STOPPED, UNSUPPORTED, READY, AWAITING_PERMISSION, GRANTING,
        DENIED, ERROR, GRANTED
    }

    interface Listener {
        void onStateChanged(State state, String message);
    }

    private final Context context;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "shizuku-permission");
        thread.setDaemon(true);
        return thread;
    });
    private final Shizuku.UserServiceArgs serviceArgs;
    private final Shizuku.OnBinderReceivedListener binderReceived = this::refresh;
    private final Shizuku.OnBinderDeadListener binderDead = this::onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            this::onPermissionResult;

    private State state = State.STOPPED;
    private boolean requestAllowed;
    private boolean managerInstalled;
    private boolean attached;
    private boolean closed;
    private long generation;
    private ServiceConnection connection;
    private Runnable timeout;

    ShizukuPermissionController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, PermissionGrantService.class))
                .tag("permission-grant-user-" + Process.myUserHandle().hashCode())
                .processNameSuffix("permission_grant")
                .version(1)
                .daemon(false);
    }

    void attach() {
        if (closed || attached) return;
        attached = true;
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        refresh();
    }

    boolean canRequest() { return !closed && requestAllowed; }

    boolean canOpenManager() { return !closed && managerInstalled; }

    void refresh() {
        if (closed) return;
        managerInstalled = isManagerInstalled();
        if (DnsSettings.hasPermission(context)) {
            cleanupService();
            show(State.GRANTED, "System permission is granted. Shizuku is no longer needed.", false);
            return;
        }
        if (state == State.AWAITING_PERMISSION || state == State.GRANTING) {
            if (!Shizuku.pingBinder()) onBinderDead();
            return;
        }
        if (!Shizuku.pingBinder()) {
            show(managerInstalled ? State.STOPPED : State.ABSENT,
                    managerInstalled
                            ? "Shizuku is installed but not running. Open Shizuku and start it first."
                            : "Shizuku is not installed. You can still grant permission using ADB below.",
                    false);
            return;
        }
        try {
            // Version 13 supplies the application Context used to validate the remote caller's UID.
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                show(State.UNSUPPORTED, "Update Shizuku to version 13 or newer to use this option.", false);
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                show(State.READY, "Shizuku is authorized. Tap below to grant the system permission once.", true);
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                show(State.DENIED, "Shizuku access was denied. Allow Private DNS Switcher in Shizuku's authorized apps, then return here.", false);
            } else if (state == State.DENIED) {
                show(State.DENIED, "Shizuku access was denied. You can try again or use ADB.", true);
            } else {
                show(State.READY, "Shizuku is running. Tap below and approve its access request.", true);
            }
        } catch (RuntimeException exception) {
            show(State.ERROR, "Could not contact Shizuku. Open it, then return here and try again.", false);
        }
    }

    void request() {
        if (closed || state == State.AWAITING_PERMISSION || state == State.GRANTING) return;
        refresh();
        if (!requestAllowed) return;
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                beginGrant();
            } else {
                show(State.AWAITING_PERMISSION, "Waiting for your approval in the Shizuku permission dialog…", false);
                Shizuku.requestPermission(REQUEST_PERMISSION);
            }
        } catch (RuntimeException exception) {
            show(State.ERROR, "The Shizuku request failed. Check that Shizuku is running, then try again.", Shizuku.pingBinder());
        }
    }

    private void onPermissionResult(int requestCode, int grantResult) {
        if (closed || requestCode != REQUEST_PERMISSION || state != State.AWAITING_PERMISSION) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            beginGrant();
        } else {
            boolean retry = false;
            try { retry = Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale(); }
            catch (RuntimeException ignored) { }
            show(State.DENIED, retry
                    ? "Shizuku access was denied. You can try again or use ADB."
                    : "Shizuku access was denied. Allow this app in Shizuku's authorized apps or use ADB.", retry);
        }
    }

    private void beginGrant() {
        if (closed) return;
        cleanupService();
        final long operation = ++generation;
        show(State.GRANTING, "Granting the system permission through Shizuku…", false);
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                main.post(() -> connected(operation, this, binder));
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                main.post(() -> finishOperation(operation, "The Shizuku permission service disconnected. Try again."));
            }

            @Override public void onBindingDied(ComponentName name) {
                onServiceDisconnected(name);
            }

            @Override public void onNullBinding(ComponentName name) {
                onServiceDisconnected(name);
            }
        };
        scheduleTimeout(operation, BIND_TIMEOUT_MS,
                "Timed out connecting to Shizuku. Open Shizuku and try again, or use ADB.");
        try {
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (RuntimeException exception) {
            finishOperation(operation, "Could not start the Shizuku permission service. Try again or use ADB.");
        }
    }

    private void connected(long operation, ServiceConnection source, IBinder binder) {
        if (closed || operation != generation || source != connection) return;
        if (binder == null || !binder.pingBinder()) {
            finishOperation(operation, "Shizuku returned an unavailable permission service. Try again.");
            return;
        }
        IPermissionGrantService service = IPermissionGrantService.Stub.asInterface(binder);
        scheduleTimeout(operation, CALL_TIMEOUT_MS,
                "The Shizuku permission request timed out. Try again or use ADB.");
        worker.execute(() -> {
            String result;
            try {
                result = service.grantWriteSecureSettings();
            } catch (Exception exception) {
                result = "The Shizuku permission service stopped before completing the request. Try again.";
            }
            final String diagnostic = result;
            main.post(() -> finishOperation(operation, diagnostic));
        });
    }

    private void scheduleTimeout(long operation, long duration, String diagnostic) {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = () -> finishOperation(operation, diagnostic);
        main.postDelayed(timeout, duration);
    }

    private void finishOperation(long operation, String diagnostic) {
        if (closed || operation != generation || state != State.GRANTING) return;
        boolean granted = DnsSettings.hasPermission(context);
        cleanupService();
        if (granted) {
            show(State.GRANTED, "System permission granted. Shizuku can now be stopped.", false);
            DnsWidgetProvider.updateAll(context);
        } else {
            String message = diagnostic == null || diagnostic.isEmpty()
                    ? "Shizuku finished, but Android did not grant the permission. Try the ADB command below."
                    : diagnostic;
            show(State.ERROR, message, Shizuku.pingBinder());
        }
    }

    private void onBinderDead() {
        if (closed) return;
        cleanupService();
        if (DnsSettings.hasPermission(context)) {
            show(State.GRANTED, "System permission is granted. Shizuku is no longer needed.", false);
            DnsWidgetProvider.updateAll(context);
        } else {
            show(State.STOPPED, "Shizuku stopped. Start it again, then retry or use ADB.", false);
        }
    }

    private boolean isManagerInstalled() {
        try {
            context.getPackageManager().getApplicationInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    private void show(State value, String message, boolean canRequest) {
        state = value;
        requestAllowed = canRequest;
        Listener callback = listener;
        if (!closed && callback != null) callback.onStateChanged(value, message);
    }

    private void cleanupService() {
        generation++;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        ServiceConnection old = connection;
        connection = null;
        if (old != null) {
            try { Shizuku.unbindUserService(serviceArgs, old, true); }
            catch (RuntimeException ignored) { /* A dead Shizuku binder cannot be unbound. */ }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        listener = null;
        cleanupService();
        if (attached) {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permissionResult);
            attached = false;
        }
        worker.shutdownNow();
    }
}
