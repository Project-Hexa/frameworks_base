/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.dreams;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamService;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.io.PrintWriter;
import java.util.NoSuchElementException;

/**
 * Internal controller for starting and stopping the current dream and managing related state.
 *
 * Assumes all operations are called from the dream handler thread.
 */
final class DreamController {
    private static final String TAG = "DreamController";

    // How long we wait for a newly bound dream to create the service connection
    private static final int DREAM_CONNECTION_TIMEOUT = 5 * 1000;

    // Time to allow the dream to perform an exit transition when waking up.
    private static final int DREAM_FINISH_TIMEOUT = 5 * 1000;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final IWindowManager mIWindowManager;
    private long mDreamStartTime;
    private String mSavedStopReason;

    private final Intent mDreamingStartedIntent = new Intent(Intent.ACTION_DREAMING_STARTED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    private final Intent mDreamingStoppedIntent = new Intent(Intent.ACTION_DREAMING_STOPPED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);

    private final Intent mCloseNotificationShadeIntent;

    private DreamRecord mCurrentDream;

    private final Runnable mStopUnconnectedDreamRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCurrentDream != null && mCurrentDream.mBound && !mCurrentDream.mConnected) {
                Slog.w(TAG, "Bound dream did not connect in the time allotted");
                stopDream(true /*immediate*/, "slow to connect");
            }
        }
    };

    private final Runnable mStopStubbornDreamRunnable = () -> {
        Slog.w(TAG, "Stubborn dream did not finish itself in the time allotted");
        stopDream(true /*immediate*/, "slow to finish");
        mSavedStopReason = null;
    };

    public DreamController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mIWindowManager = WindowManagerGlobal.getWindowManagerService();
        mCloseNotificationShadeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mCloseNotificationShadeIntent.putExtra("reason", "dream");
    }

    public void dump(PrintWriter pw) {
        pw.println("Dreamland:");
        if (mCurrentDream != null) {
            pw.println("  mCurrentDream:");
            pw.println("    mToken=" + mCurrentDream.mToken);
            pw.println("    mName=" + mCurrentDream.mName);
            pw.println("    mIsPreviewMode=" + mCurrentDream.mIsPreviewMode);
            pw.println("    mCanDoze=" + mCurrentDream.mCanDoze);
            pw.println("    mUserId=" + mCurrentDream.mUserId);
            pw.println("    mBound=" + mCurrentDream.mBound);
            pw.println("    mService=" + mCurrentDream.mService);
            pw.println("    mSentStartBroadcast=" + mCurrentDream.mSentStartBroadcast);
            pw.println("    mWakingGently=" + mCurrentDream.mWakingGently);
        } else {
            pw.println("  mCurrentDream: null");
        }
    }

    public void startDream(Binder token, ComponentName name,
            boolean isPreviewMode, boolean canDoze, int userId, PowerManager.WakeLock wakeLock,
            ComponentName overlayComponentName) {
        stopDream(true /*immediate*/, "starting new dream");

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "startDream");
        try {
            // Close the notification shade. No need to send to all, but better to be explicit.
            mContext.sendBroadcastAsUser(mCloseNotificationShadeIntent, UserHandle.ALL);

            Slog.i(TAG, "Starting dream: name=" + name
                    + ", isPreviewMode=" + isPreviewMode + ", canDoze=" + canDoze
                    + ", userId=" + userId);

            mCurrentDream = new DreamRecord(token, name, isPreviewMode, canDoze, userId, wakeLock);

            mDreamStartTime = SystemClock.elapsedRealtime();
            MetricsLogger.visible(mContext,
                    mCurrentDream.mCanDoze ? MetricsEvent.DOZING : MetricsEvent.DREAMING);

            Intent intent = new Intent(DreamService.SERVICE_INTERFACE);
            intent.setComponent(name);
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(DreamService.EXTRA_DREAM_OVERLAY_COMPONENT, overlayComponentName);
            try {
                if (!mContext.bindServiceAsUser(intent, mCurrentDream,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(userId))) {
                    Slog.e(TAG, "Unable to bind dream service: " + intent);
                    stopDream(true /*immediate*/, "bindService failed");
                    return;
                }
            } catch (SecurityException ex) {
                Slog.e(TAG, "Unable to bind dream service: " + intent, ex);
                stopDream(true /*immediate*/, "unable to bind service: SecExp.");
                return;
            }

            mCurrentDream.mBound = true;
            mHandler.postDelayed(mStopUnconnectedDreamRunnable, DREAM_CONNECTION_TIMEOUT);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    public void stopDream(boolean immediate, String reason) {
        if (mCurrentDream == null) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_POWER, "stopDream");
        try {
            if (!immediate) {
                if (mCurrentDream.mWakingGently) {
                    return; // already waking gently
                }

                if (mCurrentDream.mService != null) {
                    // Give the dream a moment to wake up and finish itself gently.
                    mCurrentDream.mWakingGently = true;
                    try {
                        mSavedStopReason = reason;
                        mCurrentDream.mService.wakeUp();
                        mHandler.postDelayed(mStopStubbornDreamRunnable, DREAM_FINISH_TIMEOUT);
                        return;
                    } catch (RemoteException ex) {
                        // oh well, we tried, finish immediately instead
                    }
                }
            }

            final DreamRecord oldDream = mCurrentDream;
            mCurrentDream = null;
            Slog.i(TAG, "Stopping dream: name=" + oldDream.mName
                    + ", isPreviewMode=" + oldDream.mIsPreviewMode
                    + ", canDoze=" + oldDream.mCanDoze
                    + ", userId=" + oldDream.mUserId
                    + ", reason='" + reason + "'"
                    + (mSavedStopReason == null ? "" : "(from '" + mSavedStopReason + "')"));
            MetricsLogger.hidden(mContext,
                    oldDream.mCanDoze ? MetricsEvent.DOZING : MetricsEvent.DREAMING);
            MetricsLogger.histogram(mContext,
                    oldDream.mCanDoze ? "dozing_minutes" : "dreaming_minutes" ,
                    (int) ((SystemClock.elapsedRealtime() - mDreamStartTime) / (1000L * 60L)));

            mHandler.removeCallbacks(mStopUnconnectedDreamRunnable);
            mHandler.removeCallbacks(mStopStubbornDreamRunnable);
            mSavedStopReason = null;

            if (oldDream.mSentStartBroadcast) {
                mContext.sendBroadcastAsUser(mDreamingStoppedIntent, UserHandle.ALL);
            }

            if (oldDream.mService != null) {
                try {
                    oldDream.mService.detach();
                } catch (RemoteException ex) {
                    // we don't care; this thing is on the way out
                }

                try {
                    oldDream.mService.asBinder().unlinkToDeath(oldDream, 0);
                } catch (NoSuchElementException ex) {
                    // don't care
                }
                oldDream.mService = null;
            }

            if (oldDream.mBound) {
                mContext.unbindService(oldDream);
            }
            oldDream.releaseWakeLockIfNeeded();

            mHandler.post(() -> mListener.onDreamStopped(oldDream.mToken));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_POWER);
        }
    }

    private void attach(IDreamService service) {
        try {
            service.asBinder().linkToDeath(mCurrentDream, 0);
            service.attach(mCurrentDream.mToken, mCurrentDream.mCanDoze,
                    mCurrentDream.mDreamingStartedCallback);
        } catch (RemoteException ex) {
            Slog.e(TAG, "The dream service died unexpectedly.", ex);
            stopDream(true /*immediate*/, "attach failed");
            return;
        }

        mCurrentDream.mService = service;

        if (!mCurrentDream.mIsPreviewMode) {
            mContext.sendBroadcastAsUser(mDreamingStartedIntent, UserHandle.ALL);
            mCurrentDream.mSentStartBroadcast = true;
        }
    }

    /**
     * Callback interface to be implemented by the {@link DreamManagerService}.
     */
    public interface Listener {
        void onDreamStopped(Binder token);
    }

    private final class DreamRecord implements DeathRecipient, ServiceConnection {
        public final Binder mToken;
        public final ComponentName mName;
        public final boolean mIsPreviewMode;
        public final boolean mCanDoze;
        public final int mUserId;

        public PowerManager.WakeLock mWakeLock;
        public boolean mBound;
        public boolean mConnected;
        public IDreamService mService;
        public boolean mSentStartBroadcast;

        public boolean mWakingGently;

        DreamRecord(Binder token, ComponentName name, boolean isPreviewMode,
                boolean canDoze, int userId, PowerManager.WakeLock wakeLock) {
            mToken = token;
            mName = name;
            mIsPreviewMode = isPreviewMode;
            mCanDoze = canDoze;
            mUserId  = userId;
            mWakeLock = wakeLock;
            // Hold the lock while we're waiting for the service to connect and start dreaming.
            // Released after the service has started dreaming, we stop dreaming, or it timed out.
            mWakeLock.acquire();
            mHandler.postDelayed(mReleaseWakeLockIfNeeded, 10000);
        }

        // May be called on any thread.
        @Override
        public void binderDied() {
            mHandler.post(() -> {
                mService = null;
                if (mCurrentDream == DreamRecord.this) {
                    stopDream(true /*immediate*/, "binder died");
                }
            });
        }

        // May be called on any thread.
        @Override
        public void onServiceConnected(ComponentName name, final IBinder service) {
            mHandler.post(() -> {
                mConnected = true;
                if (mCurrentDream == DreamRecord.this && mService == null) {
                    attach(IDreamService.Stub.asInterface(service));
                    // Wake lock will be released once dreaming starts.
                } else {
                    releaseWakeLockIfNeeded();
                }
            });
        }

        // May be called on any thread.
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mHandler.post(() -> {
                mService = null;
                if (mCurrentDream == DreamRecord.this) {
                    stopDream(true /*immediate*/, "service disconnected");
                }
            });
        }

        void releaseWakeLockIfNeeded() {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;
                mHandler.removeCallbacks(mReleaseWakeLockIfNeeded);
            }
        }

        final Runnable mReleaseWakeLockIfNeeded = this::releaseWakeLockIfNeeded;

        final IRemoteCallback mDreamingStartedCallback = new IRemoteCallback.Stub() {
            // May be called on any thread.
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                mHandler.post(mReleaseWakeLockIfNeeded);
            }
        };
    }
}
