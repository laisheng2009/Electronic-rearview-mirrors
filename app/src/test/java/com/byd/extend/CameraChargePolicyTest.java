package com.byd.extend;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class CameraChargePolicyTest {
    @Test public void unavailableIsNotZeroAndWarnsOnlyWhenMonitoringWithoutALowLatch()
            throws Exception {
        CameraChargePolicy policy = newPolicy();
        policy.setMonitoring(true);
        CameraChargePolicy.State missing = policy.markUnavailable(
                CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE);
        assertNull(missing.socPercent());
        assertNull(missing.voltageVolts());
        assertFalse(missing.isBlocked());
        assertTrue(missing.shouldWarnUnavailable());

        CameraChargePolicy.State zeroVoltage = policy.updateVoltage(0.0f);
        assertNull(zeroVoltage.voltageVolts());
        assertFalse(zeroVoltage.isBlocked());
        assertTrue(zeroVoltage.isVoltageUnavailable());

        CameraChargePolicy.State validLowSoc = policy.updateSoc(0);
        assertTrue(validLowSoc.isBlocked());
        assertEquals(CameraChargePolicy.CAUSE_SOC, validLowSoc.lowCauses());
        assertFalse(validLowSoc.shouldWarnUnavailable());
    }

    @Test public void lowUnknownSafeClearsOnlyItsOwnCauseAndLatchSurvivesPolicyReopen()
            throws Exception {
        File root = Files.createTempDirectory("camera-charge-latch-").toFile();
        CameraChargePolicy policy = new CameraChargePolicy(root, 20, 124);
        policy.setMonitoring(true);
        assertTrue(policy.updateSoc(20).isBlocked());
        assertEquals(CameraChargePolicy.CAUSE_SOC, policy.snapshot().lowCauses());
        assertTrue(policy.updateVoltage(12.4f).isBlocked());
        assertEquals(CameraChargePolicy.CAUSE_SOC | CameraChargePolicy.CAUSE_VOLTAGE,
                policy.snapshot().lowCauses());

        CameraChargePolicy reopened = new CameraChargePolicy(root, 20, 124);
        reopened.setMonitoring(true);
        CameraChargePolicy.State unknown = reopened.markUnavailable(
                CameraChargePolicy.MISSING_SOC | CameraChargePolicy.MISSING_VOLTAGE);
        assertTrue(unknown.isBlocked());
        assertFalse(unknown.shouldWarnUnavailable());
        assertEquals(CameraChargePolicy.CAUSE_SOC | CameraChargePolicy.CAUSE_VOLTAGE,
                unknown.lowCauses());

        assertEquals(CameraChargePolicy.CAUSE_VOLTAGE,
                reopened.updateSoc(21).lowCauses());
        assertTrue(reopened.snapshot().isBlocked());
        assertEquals(0, reopened.updateVoltage(12.5f).lowCauses());
        assertFalse(reopened.snapshot().isBlocked());
    }

    @Test public void corruptedLatchFailsClosedUntilFreshSafeValuesClearBothCauses()
            throws Exception {
        File root = Files.createTempDirectory("camera-charge-corrupt-").toFile();
        Files.write(new File(root, "camera-charge.json").toPath(), new byte[]{'x'});
        CameraChargePolicy policy = new CameraChargePolicy(root, 20, 124);
        policy.setMonitoring(true);
        assertEquals(3, policy.snapshot().lowCauses());
        assertFalse(policy.snapshot().isLatchPersistenceHealthy());
        assertEquals(CameraChargePolicy.CAUSE_VOLTAGE, policy.updateSoc(25).lowCauses());
        assertEquals(0, policy.updateVoltage(12.8f).lowCauses());
    }

    @Test public void fixedBuffersDecodeIntegerSocAndFloatBitsWithoutIntegerCoercion() {
        byte[] soc = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(57).array();
        byte[] volts = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                .putInt(Float.floatToRawIntBits(12.7f)).array();
        assertEquals(57, CameraChargeTelemetry.Owner.decodeSoc(soc));
        assertEquals(12.7f, CameraChargeTelemetry.Owner.decodeVoltage(volts), 0.0001f);
        assertEquals(124, CameraChargeSettings.voltageTenths(12.4f));
        assertTrue(CameraChargeSettings.isValidSocThreshold(20));
        assertFalse(CameraChargeSettings.isValidSocThreshold(9));
    }

    private static CameraChargePolicy newPolicy() throws Exception {
        return new CameraChargePolicy(
                Files.createTempDirectory("camera-charge-state-").toFile(), 20, 124);
    }
}
