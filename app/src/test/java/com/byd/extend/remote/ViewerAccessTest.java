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

    @Test public void authenticatedReplacementFencesOldRequestsAndDoesNotRenewFromWrongPassword() {
        ViewerAccess access = new ViewerAccess("viewer password", null);
        assertTrue(access.authenticate("viewer password", null));

        String first = access.reserveAdmission(null);
        assertNotNull(first);
        assertTrue(access.commitAdmission(first, 1_000L));
        assertTrue(access.occupied());
        assertFalse(access.authenticate("incorrect", null));
        assertTrue(access.accepts(first, 1_002L));
        assertEquals(first, access.reserveAdmission(first));
        assertTrue(access.commitAdmission(first, 1_003L));
        assertTrue(access.accepts(first, 1_004L));

        assertTrue(access.authenticate("viewer password", null));
        String second = access.reserveAdmission(null);
        assertNotNull(second);
        assertFalse(first.equals(second));
        assertTrue(access.accepts(first, 1_005L));
        assertFalse(access.accepts(second, 1_005L));
        assertTrue(access.commitAdmission(second, 1_005L));
        assertTrue(access.wasReplaced(first));
        assertFalse(access.release(first));
        assertFalse(access.accepts(first, 1_006L));
        assertTrue(access.accepts(second, 1_006L));
        access.supersede(second);
        assertTrue(access.wasReplaced(second));
        assertFalse(access.accepts(second, 1_007L));
    }

    @Test public void idleLeaseExpiresAndRevocationPreventsFutureAdmission() {
        ViewerAccess access = new ViewerAccess("viewer password", null);
        assertTrue(access.authenticate("viewer password", null));
        String session = access.reserveAdmission(null);
        assertNotNull(session);
        assertTrue(access.commitAdmission(session, 5_000L));

        assertFalse(access.expire(34_999L));
        assertTrue(access.expire(35_000L));
        assertFalse(access.occupied());
        assertFalse(access.accepts(session, 35_001L));
        assertTrue(access.authenticate("viewer password", null));
        String renewed = access.reserveAdmission(session);
        assertNotNull(renewed);
        assertFalse(session.equals(renewed));
        assertTrue(access.commitAdmission(renewed, 35_002L));

        access.revoke();
        assertFalse(access.authenticate("viewer password", null));
        assertFalse(access.accepts(renewed, 35_003L));
        assertNull(access.reserveAdmission(null));
        assertFalse(access.occupied());
    }

    @Test public void cancelledOrSupersededCandidateCannotEvictOrRenewActiveViewer() {
        ViewerAccess access = new ViewerAccess("viewer password", null);
        assertTrue(access.authenticate("viewer password", null));
        String first = access.reserveAdmission(null);
        assertTrue(access.commitAdmission(first, 1_000L));

        String cancelled = access.reserveAdmission(null);
        access.cancelAdmission(cancelled);
        assertFalse(access.commitAdmission(cancelled, 2_000L));
        assertEquals(first, access.current());
        assertFalse(access.wasReplaced(first));

        String stale = access.reserveAdmission(null);
        String latest = access.reserveAdmission(null);
        access.cancelAdmission(stale);
        assertFalse(access.commitAdmission(stale, 3_000L));
        assertEquals(first, access.current());
        assertTrue(access.expire(31_000L));
        assertTrue(access.commitAdmission(latest, 31_001L));
        assertFalse(access.release(first));
        assertTrue(access.accepts(latest, 31_002L));
        access.revoke();
        assertFalse(access.commitAdmission(latest, 31_003L));
    }
}
