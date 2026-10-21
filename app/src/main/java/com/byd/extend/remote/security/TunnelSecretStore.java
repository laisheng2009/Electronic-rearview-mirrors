package com.byd.extend.remote.security;

/** Process-owned encrypted storage for native tunnel credentials and identities. */
public interface TunnelSecretStore {
    byte[] getNative(String key);

    void putNative(String key, byte[] value);

    void removeNative(String key);

    void removePrefixNative(String prefix);
}
