package dev.localswitch.privatedns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

/** Fault injection at the Shizuku transport boundary; it never revokes a real permission. */
@RunWith(AndroidJUnit4.class)
@SuppressLint("RestrictedApi")
public final class ShizukuControllerInstrumentedTest {
    private Instrumentation instrumentation;
    private Context application;
    private IBinder originalBinder;
    private FakeServer server;
    private ShizukuPermissionController controller;
    private final AtomicReference<ShizukuPermissionController.State> state = new AtomicReference<>();
    private final AtomicReference<String> message = new AtomicReference<>("");
    private final AtomicInteger callbackCount = new AtomicInteger();

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        application = instrumentation.getTargetContext().getApplicationContext();
        originalBinder = Shizuku.getBinder();
        server = new FakeServer();
        instrumentation.runOnMainSync(() -> {
            Shizuku.onBinderReceived(null, null);
            Shizuku.onBinderReceived(server, application.getPackageName());
        });
    }

    @After public void tearDown() {
        if (instrumentation == null) return;
        instrumentation.runOnMainSync(() -> {
            if (controller != null) controller.close();
            Shizuku.onBinderReceived(null, null);
            if (originalBinder != null && originalBinder.pingBinder()) {
                Shizuku.onBinderReceived(originalBinder, application.getPackageName());
            }
        });
    }

    @Test public void setupDoesNotPromptAndOrdinaryDenialRemainsRetryable() {
        attach(false);
        assertEquals(ShizukuPermissionController.State.READY, state.get());
        assertEquals(0, server.permissionRequests);
        assertEquals(0, server.bindRequests);
        instrumentation.runOnMainSync(controller::request);
        assertEquals(ShizukuPermissionController.State.AWAITING_PERMISSION, state.get());
        assertEquals(1, server.permissionRequests);
        instrumentation.runOnMainSync(() -> server.replyPermission(false));
        assertEquals(ShizukuPermissionController.State.DENIED, state.get());
        assertTrue(controller.canRequest());
        instrumentation.runOnMainSync(controller::refresh);
        assertEquals(ShizukuPermissionController.State.DENIED, state.get());
        instrumentation.runOnMainSync(controller::request);
        assertEquals(2, server.permissionRequests);
        assertEquals(0, server.bindRequests);
    }

    @Test public void permanentDenialExplainsManagerAndDoesNotRequestAgain() {
        attach(false);
        instrumentation.runOnMainSync(controller::request);
        server.showRationale = true;
        instrumentation.runOnMainSync(() -> server.replyPermission(false));
        assertEquals(ShizukuPermissionController.State.DENIED, state.get());
        assertFalse(controller.canRequest());
        assertTrue(message.get().contains("authorized apps"));
        instrumentation.runOnMainSync(controller::request);
        assertEquals(1, server.permissionRequests);
        assertEquals(0, server.bindRequests);
    }

    @Test public void binderLossDuringAuthorizationReturnsToStoppedWithoutGranting() {
        attach(false);
        instrumentation.runOnMainSync(controller::request);
        instrumentation.runOnMainSync(() -> Shizuku.onBinderReceived(null, null));
        assertEquals(ShizukuPermissionController.State.STOPPED, state.get());
        assertFalse(controller.canRequest());
        assertEquals(0, server.bindRequests);
    }

    @Test public void closedControllerDoesNotReceiveLatePermissionOrBinderCallbacks() {
        attach(false);
        instrumentation.runOnMainSync(controller::request);
        instrumentation.runOnMainSync(controller::close);
        int before = callbackCount.get();
        instrumentation.runOnMainSync(() -> {
            server.replyPermission(false);
            Shizuku.onBinderReceived(null, null);
            Shizuku.onBinderReceived(server, application.getPackageName());
        });
        assertEquals(before, callbackCount.get());
        assertFalse(controller.canRequest());
        assertFalse(controller.canOpenManager());
        assertEquals(0, server.bindRequests);
    }

    @Test public void missingUserServiceTimesOutAndUnbindsRatherThanLeavingUiBusy() {
        server.authorized = true;
        reattachServer();
        attach(false);
        instrumentation.runOnMainSync(controller::request);
        assertEquals(ShizukuPermissionController.State.GRANTING, state.get());
        assertEquals(1, server.bindRequests);
        await(ShizukuPermissionController.State.ERROR, 12_000);
        assertTrue(message.get().contains("Timed out connecting"));
        assertTrue(controller.canRequest());
        assertEquals(1, server.unbindRequests);
    }

    @Test public void successfulRemoteReplyCannotSubstituteForActualAndroidPermission() {
        server.authorized = true;
        server.service = new IPermissionGrantService.Stub() {
            @Override public String grantWriteSecureSettings() { return ""; }
            @Override public void destroy() { }
        };
        reattachServer();
        attach(false);
        instrumentation.runOnMainSync(controller::request);
        await(ShizukuPermissionController.State.ERROR, 3_000);
        assertTrue(message.get().contains("Android did not grant"));
        assertEquals(1, server.unbindRequests);
        assertFalse(state.get() == ShizukuPermissionController.State.GRANTED);
    }

    @Test public void alreadyGrantedAndroidPermissionDoesNotNeedShizukuAuthorization() {
        instrumentation.runOnMainSync(() -> Shizuku.onBinderReceived(null, null));
        attach(true);
        assertEquals(ShizukuPermissionController.State.GRANTED, state.get());
        assertFalse(controller.canRequest());
        instrumentation.runOnMainSync(controller::request);
        assertEquals(0, server.permissionRequests);
        assertEquals(0, server.bindRequests);
    }

    private void attach(boolean actualPermission) {
        // Keep test input isolated from the actual grant installed by the end-to-end UI check.
        Context permissionContext = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return this; }
            @Override public int checkSelfPermission(String permission) {
                if (Manifest.permission.WRITE_SECURE_SETTINGS.equals(permission)) {
                    return actualPermission ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
                }
                return super.checkSelfPermission(permission);
            }
        };
        instrumentation.runOnMainSync(() -> {
            controller = new ShizukuPermissionController(permissionContext, (next, text) -> {
                state.set(next);
                message.set(text);
                callbackCount.incrementAndGet();
            });
            controller.attach();
        });
    }

    private void reattachServer() {
        instrumentation.runOnMainSync(() -> {
            Shizuku.onBinderReceived(null, null);
            Shizuku.onBinderReceived(server, application.getPackageName());
        });
    }

    private void await(ShizukuPermissionController.State expected, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (state.get() != expected && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
        assertEquals("Last diagnostic: " + message.get(), expected, state.get());
    }

    /** Minimal pinned-13.1.5 attach protocol plus generated service-interface operations. */
    private static final class FakeServer extends Binder implements InvocationHandler {
        private IShizukuApplication client;
        private int permissionRequestCode;
        boolean authorized;
        boolean showRationale;
        int permissionRequests;
        int bindRequests;
        int unbindRequests;
        IPermissionGrantService service;

        FakeServer() {
            IShizukuService local = (IShizukuService) Proxy.newProxyInstance(
                    IShizukuService.class.getClassLoader(), new Class<?>[]{IShizukuService.class}, this);
            attachInterface(local, ShizukuApiConstants.BINDER_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != 18) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            client = IShizukuApplication.Stub.asInterface(data.readStrongBinder());
            if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data);
            Bundle attached = new Bundle();
            attached.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION, 13);
            attached.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION, 5);
            attached.putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, 2000);
            attached.putBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, authorized);
            attached.putBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE,
                    showRationale);
            client.bindApplication(attached);
            reply.writeNoException();
            return true;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "asBinder": return this;
                case "getVersion": return 13;
                case "getUid": return 2000;
                case "checkSelfPermission": return authorized;
                case "shouldShowRequestPermissionRationale": return showRationale;
                case "requestPermission":
                    permissionRequests++;
                    permissionRequestCode = (Integer) args[0];
                    return null;
                case "addUserService":
                    bindRequests++;
                    if (service != null) ((IShizukuServiceConnection) args[0]).connected(service.asBinder());
                    return 0;
                case "removeUserService":
                    unbindRequests++;
                    return 0;
                default: throw new UnsupportedOperationException("Unexpected Shizuku call: " + method.getName());
            }
        }

        void replyPermission(boolean allowed) {
            Bundle result = new Bundle();
            result.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
            try {
                client.dispatchRequestPermissionResult(permissionRequestCode, result);
            } catch (RemoteException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
