package com.byd.extend.remote;

import org.junit.Test;

import static org.junit.Assert.*;

public class ViewerAccessTest {
    @Test public void publicAccessRequiresPasswordAndPrivateAccessRequiresProxyProof() {
        assertThrows(IllegalArgumentException.class, () -> new ViewerAccess("", null));
        ViewerAccess privateAccess = new ViewerAccess("optional password", "random-proxy-proof");
        assertFalse(privateAccess.authenticate("optional password", null));
        assertFalse(privateAccess.authenticate("optional password", "forged-proof"));
        assertTrue(privateAccess.authenticate("", "random-proxy-proof"));
        privateAccess.revoke();
        assertFalse(privateAccess.authenticate("", "random-proxy-proof"));
    }

    @Test public void passwordAuthenticationAcceptsOnlyTheConfiguredPassword() {
        ViewerAccess access = new ViewerAccess("1", null);

        assertFalse(access.authenticate("incorrect", null));
        assertTrue(access.authenticate("1", null));
        assertFalse(access.occupied());
    }

    @Test public void oneViewerRenewsItsLeaseAndReleaseAllowsTheNextViewer() {
        ViewerAccess access = new ViewerAccess("viewer password", null);
        assertTrue(access.authenticate("viewer password", null));

        String first = access.admit(null, 1_000L);
        assertNotNull(first);
        assertTrue(access.occupied());
        assertNull(access.admit(null, 1_001L));
        assertNull(access.admit("different-session", 1_002L));
        assertEquals(first, access.admit(first, 1_003L));
        assertTrue(access.accepts(first, 1_004L));

        assertTrue(access.release(first));
        assertFalse(access.occupied());
        assertFalse(access.release(first));
        assertTrue(access.authenticate("viewer password", null));
        String second = access.admit(null, 1_005L);
        assertNotNull(second);
        assertFalse(first.equals(second));
    }

    @Test public void idleLeaseExpiresAndRevocationPreventsFutureAdmission() {
        ViewerAccess access = new ViewerAccess("viewer password", null);
        assertTrue(access.authenticate("viewer password", null));
        String session = access.admit(null, 5_000L);
        assertNotNull(session);

        assertFalse(access.expire(34_999L));
        assertTrue(access.expire(35_000L));
        assertFalse(access.occupied());
        assertFalse(access.accepts(session, 35_001L));
        assertTrue(access.authenticate("viewer password", null));
        String renewed = access.admit(session, 35_002L);
        assertNotNull(renewed);
        assertFalse(session.equals(renewed));

        access.revoke();
        assertFalse(access.authenticate("viewer password", null));
        assertFalse(access.accepts(renewed, 35_003L));
        assertNull(access.admit(null, 35_004L));
        assertFalse(access.occupied());
    }
}
