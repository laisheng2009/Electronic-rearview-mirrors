package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraButtonBindingsTest {
    @Test
    public void legacyConstructorRemainsAnAndroidKeyBinding() {
        CameraButtonBindings.Binding binding = new CameraButtonBindings.Binding(
                303, CameraButtonBindings.Press.Double);

        assertEquals(CameraButtonBindings.Source.AndroidKey, binding.source);
        assertEquals(88, binding.keyCode);
        assertEquals(CameraButtonBindings.Press.Double, binding.press);
        assertTrue(binding.isAssigned());
        assertEquals(new CameraButtonBindings.Binding(88, CameraButtonBindings.Press.Double),
                binding);
    }

    @Test
    public void rescueBindingIsAssignedWithoutAnAndroidKeyAndEqualityIncludesSource() {
        CameraButtonBindings.Binding sos = new CameraButtonBindings.Binding(
                CameraButtonBindings.Source.Sos, 88, CameraButtonBindings.Press.Double);
        CameraButtonBindings.Binding sameSos = new CameraButtonBindings.Binding(
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Double);
        CameraButtonBindings.Binding help = new CameraButtonBindings.Binding(
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Double);

        assertEquals(CameraButtonBindings.UNASSIGNED, sos.keyCode);
        assertTrue(sos.isAssigned());
        assertEquals(sameSos, sos);
        assertEquals(sameSos.hashCode(), sos.hashCode());
        assertFalse(sos.equals(help));
        assertFalse(new CameraButtonBindings.Binding(CameraButtonBindings.Source.AndroidKey,
                CameraButtonBindings.UNASSIGNED, CameraButtonBindings.Press.Double).isAssigned());
    }

    @Test
    public void rescueOptionsKeepCallsGesturesSeparateFromLegacyGestures() {
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.AvasMicrophone,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Single));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.AvasMicrophone,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Double));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Double));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Hold));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.AvasEngine,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Single));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.ReverseSource,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Single));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Source.AndroidKey, CameraButtonBindings.Press.Hold));
    }
}
