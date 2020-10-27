/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.util.imetracing;

import android.app.ActivityThread;
import android.content.Context;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.ShellCommand;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.view.IInputMethodManager;

/**
 *
 * An abstract class that declares the methods for ime trace related operations - enable trace,
 * schedule trace and add new trace to buffer. Both the client and server side classes can use
 * it by getting an implementation through {@link ImeTracing#getInstance()}.
 *
 * @hide
 */
public abstract class ImeTracing {

    static final String TAG = "imeTracing";
    public static final String PROTO_ARG = "--proto-com-android-imetracing";

    /* Constants describing the component type that triggered a dump. */
    public static final int IME_TRACING_FROM_CLIENT = 0;
    public static final int IME_TRACING_FROM_IMS = 1;
    public static final int IME_TRACING_FROM_IMMS = 2;

    private static ImeTracing sInstance;
    static boolean sEnabled = false;
    IInputMethodManager mService;

    protected boolean mDumpInProgress;
    protected final Object mDumpInProgressLock = new Object();

    ImeTracing() throws ServiceNotFoundException {
        mService = IInputMethodManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.INPUT_METHOD_SERVICE));
    }

    /**
     * Returns an instance of {@link ImeTracingServerImpl} when called from a server side class
     * and an instance of {@link ImeTracingClientImpl} when called from a client side class.
     * Useful to schedule a dump for next frame or save a dump when certain methods are called.
     *
     * @return Instance of one of the children classes of {@link ImeTracing}
     */
    public static ImeTracing getInstance() {
        if (sInstance == null) {
            try {
                sInstance = isSystemProcess()
                        ? new ImeTracingServerImpl() : new ImeTracingClientImpl();
            } catch (RemoteException | ServiceNotFoundException e) {
                Log.e(TAG, "Exception while creating ImeTracing instance", e);
            }
        }
        return sInstance;
    }

    /**
     * Transmits the information from client or InputMethodService side to the server, in order to
     * be stored persistently to the current IME tracing dump.
     *
     * @param protoDump client or service side information to be stored by the server
     * @param source where the information is coming from, refer to {@see #IME_TRACING_FROM_CLIENT}
     * and {@see #IME_TRACING_FROM_IMS}
     * @param where
     */
    public void sendToService(byte[] protoDump, int source, String where) throws RemoteException {
        mService.startProtoDump(protoDump, source, where);
    }

    /**
     * @param proto dump to be added to the buffer
     */
    public abstract void addToBuffer(ProtoOutputStream proto, int source);

    /**
     * @param shell The shell command to process
     * @return {@code 0} if the command was successfully processed, {@code -1} otherwise
     */
    public abstract int onShellCommand(ShellCommand shell);

    /**
     * Starts a proto dump of the client side information.
     *
     * @param where Place where the trace was triggered.
     */
    public abstract void triggerClientDump(String where);

    /**
     * Starts a proto dump of the currently connected InputMethodService information.
     *
     * @param where Place where the trace was triggered.
     */
    public abstract void triggerServiceDump(String where, AbstractInputMethodService service);

    /**
     * Starts a proto dump of the InputMethodManagerService information.
     *
     * @param where Place where the trace was triggered.
     */
    public abstract void triggerManagerServiceDump(String where);

    /**
     * Sets whether ime tracing is enabled.
     *
     * @param enabled Tells whether ime tracing should be enabled or disabled.
     */
    public void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * @return {@code true} if dumping is enabled, {@code false} otherwise.
     */
    public boolean isEnabled() {
        return sEnabled;
    }

    /**
     * @return {@code true} if tracing is available, {@code false} otherwise.
     */
    public boolean isAvailable() {
        return mService != null;
    }

    private static boolean isSystemProcess() {
        return ActivityThread.isSystem();
    }
}
