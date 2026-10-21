package com.byd.extend.remote;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** One viewer lease; public authentication and session renewal happen before camera acquisition. */
public final class ViewerAccess {
    private static final long IDLE_MS = 30_000;
    private final SecureRandom random = new SecureRandom();
    private final byte[] salt = new byte[16];
    private final byte[] passwordHash;
    private final String privateProxyProof;
    private String session;
    private long lastSeen;
    private boolean revoked;

    public ViewerAccess(String password, String privateProxyProof) {
        random.nextBytes(salt);
        this.privateProxyProof = privateProxyProof;
        if (privateProxyProof == null && (password == null || password.isEmpty()))
            throw new IllegalArgumentException("A public viewer password is required");
        passwordHash = password == null || password.isEmpty() ? null : hash(password);
    }

    public synchronized boolean authenticate(String password, String proxyProof) {
        if (revoked) return false;
        if (privateProxyProof != null) return equal(privateProxyProof, proxyProof);
        return password != null && password.length() <= 1024
                && MessageDigest.isEqual(passwordHash, hash(password));
    }

    /** Null means another viewer owns the stream. Call only after authentication. */
    public synchronized String admit(String previousSession, long now) {
        if (revoked) return null;
        if (session != null) {
            if (!equal(session, previousSession)) return null;
        } else {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            session = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        lastSeen = now;
        return session;
    }

    public synchronized boolean accepts(String candidate, long now) {
        if (revoked || session == null || !equal(session, candidate)) return false;
        lastSeen = now;
        return true;
    }

    /** Missing HTTP requests mean an abandoned viewer, not permission to keep parked cameras alive. */
    public synchronized boolean expire(long now) {
        if (session == null || now - lastSeen < IDLE_MS) return false;
        session = null;
        return true;
    }

    public synchronized boolean release(String candidate) {
        if (session == null || !equal(session, candidate)) return false;
        session = null;
        return true;
    }

    public synchronized void revoke() { revoked = true; session = null; }
    public synchronized boolean occupied() { return session != null; }

    private byte[] hash(String password) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Password hashing unavailable", e); }
        finally { spec.clearPassword(); }
    }

    private static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
