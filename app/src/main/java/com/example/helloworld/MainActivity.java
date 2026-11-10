package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EyeBallView preview;
    private Button toggle;
    private TextView status;
    private boolean awaitingOverlayPermission;
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (request, result) -> {
        if (request != 2) return;
        if (result == PackageManager.PERMISSION_GRANTED && OverlayService.isRunning()) {
            startForegroundService(new Intent(this, OverlayService.class));
        }
        updateStatus();
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        awaitingOverlayPermission = state != null && state.getBoolean("awaiting_overlay");
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        preview = findViewById(R.id.preview_ball);
        toggle = findViewById(R.id.toggle_ball);
        status = findViewById(R.id.status);
        findViewById(R.id.connect_shizuku).setOnClickListener(view -> {
            if (!Shizuku.pingBinder()) {
                Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (manager != null) startActivity(manager);
                else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
                Toast.makeText(this, R.string.start_shizuku_hint, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    if (OverlayService.isRunning()) startForegroundService(new Intent(this, OverlayService.class));
                    else Toast.makeText(this, R.string.authorized_hint, Toast.LENGTH_SHORT).show();
                } else Shizuku.requestPermission(2);
            } catch (RuntimeException exception) {
                Toast.makeText(this, R.string.start_shizuku_hint, Toast.LENGTH_LONG).show();
            }
        });
        toggle.setOnClickListener(view -> {
            if (OverlayService.isRunning()) {
                stopService(new Intent(this, OverlayService.class));
                handler.postDelayed(this::updateStatus, 150);
            } else if (!Settings.canDrawOverlays(this)) {
                awaitingOverlayPermission = true;
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                enableBall();
            }
        });
        findViewById(R.id.go_home).setOnClickListener(view -> {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            startActivity(home);
        });
    }

    private void enableBall() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startForegroundService(new Intent(this, OverlayService.class));
        handler.postDelayed(this::updateStatus, 200);
    }

    private void updateStatus() {
        boolean running = OverlayService.isRunning();
        toggle.setText(running ? R.string.stop_ball : R.string.start_ball);
        status.setText(!running ? R.string.status_preview : OverlayService.inputStatus() == 1
                ? R.string.status_global : OverlayService.inputStatus() == 2
                ? R.string.status_input_error : R.string.status_floating);
        Button connect = findViewById(R.id.connect_shizuku);
        boolean granted = false;
        try { granted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException ignored) { }
        connect.setText(granted ? R.string.shizuku_authorized : R.string.connect_shizuku);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (preview != null) {
            int action = event.getActionMasked();
            boolean touching = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
            if (touching) preview.lookAtScreen(event.getRawX(), event.getRawY());
            else preview.releaseGaze();
            OverlayService.follow(event.getRawX(), event.getRawY(), touching);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) enableBall();
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaiting_overlay", awaitingOverlayPermission);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        super.onDestroy();
    }
}
