package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class FixedBydTelemetryManagerTest {
    private static final int POWER = AvasTelemetryController.POWER_FID;
    private static final int STEERING = TurnSignalTelemetryController.STEERING_FID;

    @Test public void sharedDeviceKeepsAdditiveSupersetUntilLastOwnerCloses()
            throws Exception {
        FakeManager backend = new FakeManager();
        FixedBydTelemetryManager owner =
                new FixedBydTelemetryManager(backend, FakeOnAutoListener.class);
        FixedBydTelemetryManager.Subscription avas = owner.subscribe(request(1001, POWER),
                new RecordingListener());
        FixedBydTelemetryManager.Subscription guard = owner.subscribe(request(1001, STEERING),
                new RecordingListener());

        assertArrayEquals(new int[]{POWER, STEERING}, backend.enabled.get(1001));
        guard.close();
        assertArrayEquals(new int[]{POWER, STEERING}, backend.enabled.get(1001));

        avas.close();
        assertFalse(backend.enabled.containsKey(1001));
    }

    private static FixedBydTelemetryManager.Request[] request(int device, int fid) {
        return new FixedBydTelemetryManager.Request[]{
                new FixedBydTelemetryManager.Request(device, fid)};
    }

    public interface FakeOnAutoListener {
        void onChanged(int device, int fid, int value, Object client);
        void onChanged(int device, int fid, float value, Object client);
        void onChanged(int device, int fid, byte[] value, Object client);
        void onError(int code, String message);
    }

    public static final class FakeManager {
        final Map<Integer, int[]> enabled = new LinkedHashMap<>();
        int unregisterCount;
        FakeOnAutoListener listener;
        public void registerListener(FakeOnAutoListener listener) { this.listener = listener; }
        public void unregisterListener(FakeOnAutoListener listener) { unregisterCount++; }
        public byte[] getBuffer(int device, int fid) { return new byte[0]; }
        public int enableDevice(int device, int[] fids) {
            int[] current = enabled.getOrDefault(device, new int[0]);
            boolean added = false;
            for (int fid : fids) {
                if (contains(current, fid)) continue;
                current = Arrays.copyOf(current, current.length + 1);
                current[current.length - 1] = fid;
                added = true;
            }
            if (!added) {
                return 1;
            }
            enabled.put(device, current);
            return 0;
        }
        public int disableDevice(int device) {
            enabled.remove(device);
            return 0;
        }

        private static boolean contains(int[] values, int fid) {
            for (int value : values) if (value == fid) return true;
            return false;
        }
    }

    private static final class RecordingListener implements FixedBydTelemetryManager.Listener {
        @Override public void onValue(int device, int fid, int value, long receivedMs) { }
        @Override public void onBytes(int device, int fid, byte[] value, long receivedMs) { }
        @Override public void onError(String reason, long receivedMs) { }
    }
}
