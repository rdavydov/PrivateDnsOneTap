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
                .version(2)
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
            show(State.GRANTED, text(R.string.shizuku_state_granted), false);
            return;
        }
        if (state == State.AWAITING_PERMISSION || state == State.GRANTING) {
            if (!Shizuku.pingBinder()) onBinderDead();
            return;
        }
        if (!Shizuku.pingBinder()) {
            show(managerInstalled ? State.STOPPED : State.ABSENT,
                    managerInstalled
                            ? text(R.string.shizuku_state_stopped)
                            : text(R.string.shizuku_state_absent),
                    false);
            return;
        }
        try {
            // Version 13 supplies the application Context used to validate the remote caller's UID.
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                show(State.UNSUPPORTED, text(R.string.shizuku_state_unsupported), false);
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                show(State.READY, text(R.string.shizuku_state_authorized), true);
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                show(State.DENIED, text(R.string.shizuku_state_denied_manager), false);
            } else if (state == State.DENIED) {
                show(State.DENIED, text(R.string.shizuku_state_denied_retry), true);
            } else {
                show(State.READY, text(R.string.shizuku_state_ready), true);
            }
        } catch (RuntimeException exception) {
            show(State.ERROR, text(R.string.shizuku_error_contact), false);
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
                show(State.AWAITING_PERMISSION, text(R.string.shizuku_state_awaiting), false);
                Shizuku.requestPermission(REQUEST_PERMISSION);
            }
        } catch (RuntimeException exception) {
            show(State.ERROR, text(R.string.shizuku_error_request), Shizuku.pingBinder());
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
                    ? text(R.string.shizuku_state_denied_retry)
                    : text(R.string.shizuku_state_denied_settings), retry);
        }
    }

    private void beginGrant() {
        if (closed) return;
        cleanupService();
        final long operation = ++generation;
        show(State.GRANTING, text(R.string.shizuku_state_granting), false);
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                main.post(() -> connected(operation, this, binder));
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                main.post(() -> finishOperation(operation, text(R.string.shizuku_error_disconnected)));
            }

            @Override public void onBindingDied(ComponentName name) {
                onServiceDisconnected(name);
            }

            @Override public void onNullBinding(ComponentName name) {
                onServiceDisconnected(name);
            }
        };
        scheduleTimeout(operation, BIND_TIMEOUT_MS,
                text(R.string.shizuku_error_bind_timeout));
        try {
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (RuntimeException exception) {
            finishOperation(operation, text(R.string.shizuku_error_start));
        }
    }

    private void connected(long operation, ServiceConnection source, IBinder binder) {
        if (closed || operation != generation || source != connection) return;
        if (binder == null || !binder.pingBinder()) {
            finishOperation(operation, text(R.string.shizuku_error_unavailable));
            return;
        }
        IPermissionGrantService service = IPermissionGrantService.Stub.asInterface(binder);
        scheduleTimeout(operation, CALL_TIMEOUT_MS,
                text(R.string.shizuku_error_call_timeout));
        worker.execute(() -> {
            String result;
            try {
                result = grantDiagnostic(service.grantWriteSecureSettings());
            } catch (Exception exception) {
                result = text(R.string.shizuku_error_service_stopped);
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
            show(State.GRANTED, text(R.string.shizuku_state_grant_complete), false);
            DnsWidgetProvider.updateAll(context);
        } else {
            String message = diagnostic == null || diagnostic.isEmpty()
                    ? text(R.string.shizuku_error_not_granted)
                    : diagnostic;
            show(State.ERROR, message, Shizuku.pingBinder());
        }
    }

    private void onBinderDead() {
        if (closed) return;
        cleanupService();
        if (DnsSettings.hasPermission(context)) {
            show(State.GRANTED, text(R.string.shizuku_state_granted), false);
            DnsWidgetProvider.updateAll(context);
        } else {
            show(State.STOPPED, text(R.string.shizuku_state_connection_lost), false);
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

    private String text(int resource, Object... arguments) {
        return LanguageSettings.context(context).getString(resource, arguments);
    }

    /** The remote process returns codes, not UI text or access to application preferences. */
    private String grantDiagnostic(String result) {
        if (result == null || result.isEmpty()) return result;
        int separator = result.indexOf('\n');
        String code = separator < 0 ? result : result.substring(0, separator);
        String detail = separator < 0 ? "" : result.substring(separator + 1).trim();
        int resource;
        switch (code) {
            case PermissionGrantService.ERROR_UNSUPPORTED: resource = R.string.grant_error_unsupported; break;
            case PermissionGrantService.ERROR_APP_MISSING: resource = R.string.grant_error_app_missing; break;
            case PermissionGrantService.ERROR_BUSY: resource = R.string.grant_error_busy; break;
            case PermissionGrantService.ERROR_TIMEOUT: resource = R.string.grant_error_command_timeout; break;
            case PermissionGrantService.ERROR_REFUSED: resource = R.string.grant_error_refused; break;
            case PermissionGrantService.ERROR_EXECUTE: resource = R.string.grant_error_execute; break;
            case PermissionGrantService.ERROR_INTERRUPTED: resource = R.string.grant_error_interrupted; break;
            default:
                resource = R.string.grant_error_refused;
                detail = result;
                break;
        }
        String summary = text(resource);
        return detail.isEmpty() ? summary : text(R.string.grant_error_detail, summary, detail);
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
