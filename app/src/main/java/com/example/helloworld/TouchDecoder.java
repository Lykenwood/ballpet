package com.example.helloworld;

/** Linux type-B multi-touch decoder. No Android dependencies, logging or storage. */
public final class TouchDecoder {
    public interface Listener { void onTouch(float x, float y, boolean touching); }
    private final boolean[] down = new boolean[32];
    private final boolean[] hasX = new boolean[32], hasY = new boolean[32];
    private final int[] x = new int[32], y = new int[32];
    private final int minX, maxX, minY, maxY;
    private final Listener listener;
    private int slot;
    private boolean wasTouching, dropped;

    public TouchDecoder(int minX, int maxX, int minY, int maxY, Listener listener) {
        if (maxX <= minX || maxY <= minY) throw new IllegalArgumentException("Invalid axis range");
        this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY;
        this.listener = listener;
    }

    public void accept(int type, int code, int value) {
        // Do not emit stale coordinates after a kernel queue overflow. Wait for new contacts.
        if (type == 0 && code == 3) {
            java.util.Arrays.fill(down, false);
            dropped = true;
            if (wasTouching) listener.onTouch(0, 0, false);
            wasTouching = false;
            return;
        }
        if (dropped) { if (type == 0 && code == 0) dropped = false; return; }
        if (type == 3) {
            if (code == 0x2f) { slot = value; return; }
            if (slot < 0 || slot >= down.length) return;
            if (code == 0x39) {
                down[slot] = value != -1;
                // Slots retain axis values: Linux can omit unchanged coordinates on a new contact.
            } else if (code == 0x35) { x[slot] = value; hasX[slot] = true; }
            else if (code == 0x36) { y[slot] = value; hasY[slot] = true; }
        }
        if (type == 0 && code == 0) {
            for (int i = 0; i < down.length; i++) {
                if (down[i] && hasX[i] && hasY[i]) {
                    listener.onTouch(normalize(x[i], minX, maxX), normalize(y[i], minY, maxY), true);
                    wasTouching = true;
                    return;
                }
            }
            if (wasTouching) listener.onTouch(0, 0, false);
            wasTouching = false;
        }
    }

    private static float normalize(int value, int min, int max) {
        return Math.max(0, Math.min(1, (value - min) / (float) (max - min)));
    }

    public static float[] rotate(float x, float y, int rotation) {
        switch (rotation) {
            case 1: return new float[] {y, 1 - x};
            case 2: return new float[] {1 - x, 1 - y};
            case 3: return new float[] {1 - y, x};
            default: return new float[] {x, y};
        }
    }
}
