package com.example.helloworld;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.view.animation.LinearInterpolator;
import java.util.concurrent.ThreadLocalRandom;

/** Draws the ball locally; gaze is transient and never stored. */
public final class EyeBallView extends View {
    private static final float GAZE_TRAVEL = 0.45f;
    private static final long FOLLOW_DURATION_MS = 120;
    private static final long RETURN_DELAY_MS = 1200;
    private static final long RETURN_DURATION_MS = 280;
    private final PathInterpolator followEasing = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    private final PathInterpolator returnEasing = new PathInterpolator(0.77f, 0f, 0.175f, 1f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] location = new int[2];
    private final ValueAnimator gaze = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator blink = ValueAnimator.ofFloat(1f, 0.06f, 1f);
    private float eyeOpenness = 1f;
    private boolean visibleToUser;
    private float eyeX, eyeY, fromX, fromY, targetX, targetY;
    private float radius;
    private Shader surface;
    private final Runnable returnToCenter = () -> animateGaze(0, 0, true);
    private final Runnable blinkOnce = () -> {
        if (!visibleToUser || !isAttachedToWindow()) return;
        if (ValueAnimator.areAnimatorsEnabled()) blink.start();
        else scheduleNextBlink();
    };

    public EyeBallView(Context context) { this(context, null); }

    public EyeBallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription(context.getString(R.string.ball_description));
        gaze.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            eyeX = fromX + (targetX - fromX) * progress;
            eyeY = fromY + (targetY - fromY) * progress;
            invalidate();
        });
        // Blinking changes only eye height, so finger tracking continues uninterrupted.
        blink.setDuration(180);
        blink.setInterpolator(new LinearInterpolator());
        blink.addUpdateListener(animation -> {
            eyeOpenness = (float) animation.getAnimatedValue();
            invalidate();
        });
        blink.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationStart(Animator animation) { cancelled = false; }
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) scheduleNextBlink();
            }
        });
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        radius = Math.min(w, h) * 0.40f;
        surface = new RadialGradient(w * 0.38f, h * 0.32f, radius * 1.8f,
                new int[] {Color.WHITE, 0xFFFFF9E9, 0xFFEDE3CB},
                new float[] {0, 0.70f, 1}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.48f;
        paint.setShader(surface);
        paint.setShadowLayer(radius * 0.13f, 0, radius * 0.10f, 0x250C1524);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.clearShadowLayer();
        paint.setShader(null);
        paint.setColor(0xFF111316);
        float dx = eyeX * radius * GAZE_TRAVEL;
        float dy = eyeY * radius * GAZE_TRAVEL;
        float halfWidth = radius * 0.105f;
        float halfHeight = radius * 0.16f * eyeOpenness;
        for (int side = -1; side <= 1; side += 2) {
            float x = cx + side * radius * 0.27f + dx;
            float y = cy - radius * 0.02f + dy;
            canvas.drawOval(x - halfWidth, y - halfHeight,
                    x + halfWidth, y + halfHeight, paint);
        }
    }

    private void scheduleNextBlink() {
        removeCallbacks(blinkOnce);
        if (visibleToUser && isAttachedToWindow()) {
            postDelayed(blinkOnce, ThreadLocalRandom.current().nextLong(4000, 8001));
        }
    }

    private void stopBlinking() {
        blink.cancel();
        removeCallbacks(blinkOnce);
        eyeOpenness = 1f;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        visibleToUser = isShown() && getWindowVisibility() == VISIBLE;
        scheduleNextBlink();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        visibleToUser = visible;
        // View can dispatch visibility while its constructor is still running.
        if (blink == null) return;
        if (visible) scheduleNextBlink();
        else stopBlinking();
    }

    public void lookAtScreen(float x, float y) {
        removeCallbacks(returnToCenter);
        getLocationOnScreen(location);
        float dx = x - (location[0] + getWidth() * 0.5f);
        float dy = y - (location[1] + getHeight() * 0.48f);
        float distance = (float) Math.hypot(dx, dy);
        float reach = Math.max(radius * 2f, distance);
        if (reach > 0) animateGaze(dx / reach, dy / reach);
    }

    public void releaseGaze() {
        removeCallbacks(returnToCenter);
        postDelayed(returnToCenter, RETURN_DELAY_MS);
    }

    public void resetGaze() {
        removeCallbacks(returnToCenter);
        animateGaze(0, 0);
    }

    private void animateGaze(float x, float y) {
        animateGaze(x, y, false);
    }

    private void animateGaze(float x, float y, boolean returning) {
        gaze.cancel();
        fromX = eyeX;
        fromY = eyeY;
        targetX = x;
        targetY = y;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            eyeX = x;
            eyeY = y;
            invalidate();
        } else {
            // A new touch immediately interrupts the slower return from the current position.
            gaze.setDuration(returning ? RETURN_DURATION_MS : FOLLOW_DURATION_MS);
            gaze.setInterpolator(returning ? returnEasing : followEasing);
            gaze.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        visibleToUser = false;
        stopBlinking();
        gaze.cancel();
        removeCallbacks(returnToCenter);
        super.onDetachedFromWindow();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
