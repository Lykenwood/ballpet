package com.example.helloworld;

import java.util.ArrayList;
import java.util.List;

public final class TouchDecoderTest {
    private static final List<float[]> frames = new ArrayList<>();
    private static TouchDecoder decoder;

    private static void event(int type, int code, int value) { decoder.accept(type, code, value); }
    private static void sync() { event(0, 0, 0); }
    private static void assertLast(float x, float y, boolean down) {
        float[] last = frames.get(frames.size() - 1);
        if (Math.abs(last[0] - x) > 0.001 || Math.abs(last[1] - y) > 0.001 || last[2] != (down ? 1 : 0)) {
            throw new AssertionError(java.util.Arrays.toString(last));
        }
    }

    public static void main(String[] args) {
        decoder = new TouchDecoder(0, 1000, 0, 2000,
                (x, y, down) -> frames.add(new float[] {x, y, down ? 1 : 0}));
        event(3, 0x39, 10); event(3, 0x35, 250); event(3, 0x36, 1500); sync();
        assertLast(0.25f, 0.75f, true);
        event(3, 0x35, 1000); sync(); assertLast(1, 0.75f, true);
        event(3, 0x39, -1); sync(); assertLast(0, 0, false);

        // A repeat tap at the exact same coordinates may omit both axis updates.
        event(3, 0x39, 11); sync(); assertLast(1, 0.75f, true);
        // The first finger remains the gaze target until it lifts.
        event(3, 0x2f, 1); event(3, 0x39, 12); event(3, 0x35, 500); event(3, 0x36, 500);
        sync(); assertLast(1, 0.75f, true);
        event(3, 0x2f, 0); event(3, 0x39, -1); sync(); assertLast(0.5f, 0.25f, true);
        event(3, 0x2f, 1); event(3, 0x39, -1); sync(); assertLast(0, 0, false);

        // Invalid slots cannot corrupt the primary touch; queue overflow resets the gaze.
        int before = frames.size();
        event(3, 0x2f, 1000); event(3, 0x39, 42); sync();
        if (frames.size() != before) throw new AssertionError("Invalid slot emitted a frame");
        event(3, 0x2f, 0); event(3, 0x39, 13); sync();
        event(0, 3, 0); assertLast(0, 0, false); sync();
        float[][] expected = {{0.2f, 0.7f}, {0.7f, 0.8f}, {0.8f, 0.3f}, {0.3f, 0.2f}};
        for (int i = 0; i < 4; i++) {
            float[] point = TouchDecoder.rotate(0.2f, 0.7f, i);
            if (Math.abs(point[0] - expected[i][0]) > 0.001 || Math.abs(point[1] - expected[i][1]) > 0.001)
                throw new AssertionError("Rotation " + i);
        }
        System.out.println("PASS: taps, unchanged coordinates, movement, multi-touch handoff, invalid slots, overflow and 4 rotations");
    }
}
