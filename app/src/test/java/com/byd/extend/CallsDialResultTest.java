package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallsDialResultTest {
    @Test
    public void interpretsBooleanAndObjectReturnContracts() {
        assertFalse(CallsRuntime.dialResultAccepted(Boolean.FALSE));
        assertFalse(CallsRuntime.dialResultAccepted(null));
        assertTrue(CallsRuntime.dialResultAccepted(Boolean.TRUE));
        assertTrue(CallsRuntime.dialResultAccepted(new Object()));
    }
}
