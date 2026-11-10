package com.example.helloworld;

import android.os.IBinder;
import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shizuku UserService: runs as shell, reads only the direct touch-screen device. */
public final class TouchReaderService extends ITouchReader.Stub {
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Process process;
    private IGazeListener client;
    private final IBinder.DeathRecipient clientDied = this::stop;

    public TouchReaderService() { }

    @Override public synchronized void start(IGazeListener listener) throws RemoteException {
        stop();
        client = listener;
        listener.asBinder().linkToDeath(clientDied, 0);
        int current = generation.incrementAndGet();
        new Thread(() -> readTouches(listener, current), "touch-screen-reader").start();
    }

    private void readTouches(IGazeListener listener, int current) {
        Process owned = null;
        try {
            String[] device = findTouchScreen();
            if (device == null) throw new IllegalStateException("No supported touch screen");
            if (current != generation.get()) return;
            synchronized (this) {
                if (current != generation.get()) return;
                owned = new ProcessBuilder("/system/bin/getevent", "-t", device[0])
                        .redirectErrorStream(true).start();
                process = owned;
            }
            final long[] lastSent = {0};
            final boolean[] wasDown = {false};
            TouchDecoder decoder = new TouchDecoder(Integer.parseInt(device[1]), Integer.parseInt(device[2]),
                    Integer.parseInt(device[3]), Integer.parseInt(device[4]), (x, y, down) -> {
                if (current != generation.get()) return;
                long now = System.nanoTime();
                if (down == wasDown[0] && down && now - lastSent[0] < 16_000_000L) return;
                try {
                    listener.onTouch(x, y, down);
                    lastSent[0] = now;
                    wasDown[0] = down;
                } catch (RemoteException exception) { stop(); }
            });
            listener.onStatus(1);
            Pattern event = Pattern.compile("([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*$");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(owned.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (current == generation.get() && (line = reader.readLine()) != null) {
                    Matcher match = event.matcher(line);
                    if (match.find()) decoder.accept(Integer.parseInt(match.group(1), 16),
                            Integer.parseInt(match.group(2), 16), (int) Long.parseLong(match.group(3), 16));
                }
            }
            if (current == generation.get()) listener.onStatus(2);
        } catch (Exception exception) {
            if (current == generation.get()) {
                try { listener.onStatus(2); } catch (RemoteException ignored) { }
            }
        } finally {
            if (owned != null) owned.destroy();
        }
    }

    private String[] findTouchScreen() throws Exception {
        Process probe = new ProcessBuilder("/system/bin/getevent", "-pl").redirectErrorStream(true).start();
        String text;
        try (java.io.InputStream input = probe.getInputStream()) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            text = output.toString("UTF-8");
        } finally { probe.destroy(); }
        Pattern axis = Pattern.compile("ABS_MT_POSITION_([XY])[^\\n]*min (-?\\d+), max (-?\\d+)");
        for (String device : text.split("add device")) {
            if (!device.contains("INPUT_PROP_DIRECT")) continue;
            Matcher path = Pattern.compile("/dev/input/event\\d+").matcher(device);
            if (!path.find()) continue;
            String[] result = {path.group(), null, null, null, null};
            Matcher range = axis.matcher(device);
            while (range.find()) {
                int i = range.group(1).equals("X") ? 1 : 3;
                result[i] = range.group(2); result[i + 1] = range.group(3);
            }
            if (result[1] != null && result[3] != null) return result;
        }
        return null;
    }

    @Override public synchronized void stop() {
        generation.incrementAndGet();
        if (process != null) { process.destroy(); process = null; }
        if (client != null) {
            client.asBinder().unlinkToDeath(clientDied, 0);
            client = null;
        }
    }

    @Override public void destroy() {
        stop();
        System.exit(0);
    }
}
