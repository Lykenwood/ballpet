package com.example.helloworld;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import rikka.shizuku.Shizuku;

public final class OverlayService extends Service {
    public static final String ACTION_STOP = "com.example.helloworld.STOP";
    private static OverlayService active;
    private WindowManager windows;
    private WindowManager.LayoutParams position;
    private EyeBallView ball;
    private final Rect safeArea = new Rect();
    private float downX, downY;
    private int originX, originY;
    private boolean dragging;
    private boolean screenReceiverRegistered;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ITouchReader reader;
    private boolean binding;
    private int inputStatus;
    private long touchFrames;
    private Shizuku.UserServiceArgs readerArgs;
    private final Shizuku.OnBinderReceivedListener binderReceived = this::connectReader;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        reader = null; binding = false; inputStatus = 0;
        if (ball != null) ball.resetGaze();
    };
    private final IGazeListener listener = new IGazeListener.Stub() {
        @Override public void onTouch(float x, float y, boolean touching) {
            main.post(() -> {
                if (active != OverlayService.this || ball == null || isLocked()) return;
                touchFrames++;
                if (touching) {
                    float[] rotated = TouchDecoder.rotate(x, y, windows.getDefaultDisplay().getRotation());
                    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                    windows.getDefaultDisplay().getRealMetrics(metrics);
                    follow(rotated[0] * metrics.widthPixels, rotated[1] * metrics.heightPixels, true);
                } else follow(0, 0, false);
            });
        }
        @Override public void onStatus(int status) {
            main.post(() -> { if (active == OverlayService.this) inputStatus = status; });
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (active != OverlayService.this) return;
            reader = ITouchReader.Stub.asInterface(binder);
            if (!isLocked()) startReader();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            reader = null; binding = false; inputStatus = 0;
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean hidden = Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || isLocked();
            if (ball != null) {
                ball.setVisibility(hidden ? View.GONE : View.VISIBLE);
                ball.resetGaze();
            }
            if (hidden) pauseReader();
            else if (reader != null) startReader();
            else connectReader();
        }
    };

    public static boolean isRunning() { return active != null && active.ball != null; }
    public static int inputStatus() { return active == null ? 0 : active.inputStatus; }

    public static void follow(float x, float y, boolean touching) {
        if (active == null || active.ball == null || active.isLocked()) return;
        if (touching) active.ball.lookAtScreen(x, y);
        else active.ball.releaseGaze();
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        windows = getSystemService(WindowManager.class);
        readerArgs = new Shizuku.UserServiceArgs(new ComponentName(this, TouchReaderService.class))
                .daemon(false).processNameSuffix("touch_reader").tag("floating-eyes-touch").version(2);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && ACTION_STOP.equals(intent.getAction()))
                || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, createNotification());
        if (ball == null) {
            try {
                showBall();
            } catch (WindowManager.BadTokenException | SecurityException exception) {
                stopSelf();
            }
        }
        if (ball != null) connectReader();
        return START_NOT_STICKY;
    }

    private void connectReader() {
        if (ball == null || binding || isLocked() || !Shizuku.pingBinder()) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            binding = true;
            Shizuku.bindUserService(readerArgs, connection);
        } catch (RuntimeException exception) {
            binding = false; inputStatus = 2;
        }
    }

    private void startReader() {
        try { reader.start(listener); }
        catch (RemoteException | RuntimeException exception) { inputStatus = 2; }
    }

    private void pauseReader() {
        inputStatus = 0;
        if (reader != null) {
            try { reader.stop(); } catch (RemoteException | RuntimeException ignored) { }
        }
    }

    private Notification createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("floating_eyes",
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "floating_eyes")
                .setSmallIcon(R.drawable.ic_eyes_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_body))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.stop_ball), stop).build())
                .setOngoing(true).setShowWhen(false).build();
    }

    private void showBall() {
        int size = dp(96);
        updateSafeArea();
        position = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.setTitle("FloatingEyes");
        if (Build.VERSION.SDK_INT >= 30) position.setFitInsetsTypes(0);
        position.x = safeArea.right - size - dp(12);
        position.y = safeArea.top + (int) (safeArea.height() * 0.30f);
        ball = new EyeBallView(this);
        ball.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    originX = position.x;
                    originY = position.y;
                    dragging = false;
                    ball.lookAtScreen(downX, downY);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) {
                        dragging = true;
                    }
                    if (dragging) {
                        position.x = originX + (int) dx;
                        position.y = originY + (int) dy;
                        clampPosition();
                        windows.updateViewLayout(ball, position);
                    }
                    ball.lookAtScreen(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) view.performClick();
                    ball.releaseGaze();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ball.releaseGaze();
                    return true;
                default:
                    return true;
            }
        });
        if (isLocked()) ball.setVisibility(View.GONE);
        windows.addView(ball, position);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        screenReceiverRegistered = true;
    }

    private boolean isLocked() {
        return getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void updateSafeArea() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windows.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            safeArea.set(bars.left, bars.top, bounds.width() - bars.right, bounds.height() - bars.bottom);
        } else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            safeArea.set(0, dp(28), metrics.widthPixels, metrics.heightPixels - dp(48));
        }
    }

    private void clampPosition() {
        position.x = Math.max(safeArea.left, Math.min(position.x, safeArea.right - position.width));
        position.y = Math.max(safeArea.top, Math.min(position.y, safeArea.bottom - position.height));
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateSafeArea();
        if (ball != null) {
            clampPosition();
            windows.updateViewLayout(ball, position);
            ball.resetGaze();
        }
    }

    @Override public void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        pauseReader();
        if (binding && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(readerArgs, connection, true); }
            catch (RuntimeException ignored) { }
        }
        main.removeCallbacksAndMessages(null);
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver);
        if (ball != null && ball.isAttachedToWindow()) windows.removeView(ball);
        ball = null;
        if (active == this) active = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println("FloatingEyes: running=" + (ball != null) + " inputStatus=" + inputStatus
                + " touchFrames=" + touchFrames);
    }
}
