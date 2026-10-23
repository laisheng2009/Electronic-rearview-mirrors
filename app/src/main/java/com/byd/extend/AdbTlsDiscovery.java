package com.byd.extend;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/** Discovers only Android's local ADB TLS connect endpoint. */
final class AdbTlsDiscovery implements AutoCloseable, NsdManager.DiscoveryListener {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    private final NsdManager manager;
    private final Consumer<Integer> candidate;
    private final AdbRecoveryRuntime.LogSink log;
    private final Queue<NsdServiceInfo> pending = new ArrayDeque<>();
    private final StartGate startGate = new StartGate();
    private boolean resolving;
    private volatile boolean closed;

    AdbTlsDiscovery(Context context, Consumer<Integer> candidate, AdbRecoveryRuntime.LogSink log) {
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.candidate = candidate;
        this.log = log;
    }

    synchronized void start() {
        int propertyPort = propertyPort();
        if (propertyPort > 0) candidate.accept(propertyPort);
        if (manager == null) {
            log.log("adb_tls_mdns_unavailable", "reason", "nsd_service_null");
            return;
        }
        if (!startGate.begin(closed)) return;
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);
        } catch (RuntimeException error) {
            startGate.failed();
            AdbRecoveryDiagnostics.failure(log::log, "adb_tls_mdns_start_failed",
                    "discoverServices", error);
        }
    }

    private int propertyPort() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Method getInt = type.getDeclaredMethod("getInt", String.class, int.class);
            getInt.setAccessible(true);
            int value = (Integer) getInt.invoke(null, "service.adb.tls.port", -1);
            return value > 0 && value <= 65535 ? value : -1;
        } catch (Throwable error) {
            AdbRecoveryDiagnostics.failure(log::log, "adb_tls_property_failed",
                    "SystemProperties.getInt", error, "key", "service.adb.tls.port");
            return -1;
        }
    }

    @Override public synchronized void onServiceFound(NsdServiceInfo service) {
        if (closed) return;
        pending.offer(service);
        resolveNext();
    }

    private synchronized void resolveNext() {
        if (closed || resolving || manager == null) return;
        NsdServiceInfo service = pending.poll();
        if (service == null) return;
        resolving = true;
        try {
            manager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    log.log("adb_tls_mdns_resolve_failed", "operation", "resolveService",
                            "error_code", errorCode);
                    finishResolve();
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    try {
                        int port = info.getPort();
                        if (!closed && port > 0 && port <= 65535 && info.getHost() != null
                                && (info.getHost().isLoopbackAddress()
                                || NetworkInterface.getByInetAddress(info.getHost()) != null)) {
                            candidate.accept(port);
                        }
                    } catch (Throwable error) {
                        AdbRecoveryDiagnostics.failure(log::log, "adb_tls_candidate_failed",
                                "validate_local_endpoint", error);
                    } finally {
                        finishResolve();
                    }
                }
            });
        } catch (RuntimeException error) {
            resolving = false;
            AdbRecoveryDiagnostics.failure(log::log, "adb_tls_mdns_resolve_failed",
                    "resolveService", error);
            resolveNext();
        }
    }

    private synchronized void finishResolve() {
        resolving = false;
        resolveNext();
    }

    @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
    @Override public void onDiscoveryStarted(String serviceType) { }
    @Override public void onDiscoveryStopped(String serviceType) { startGate.failed(); }
    @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        log.log("adb_tls_mdns_start_failed", "operation", "discoverServices", "error_code", errorCode);
        startGate.failed();
        stopDiscovery();
    }
    @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        log.log("adb_tls_mdns_stop_failed", "operation", "stopServiceDiscovery", "error_code", errorCode);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        if (startGate.started() && manager != null) {
            stopDiscovery();
        }
        startGate.failed();
    }

    private void stopDiscovery() {
        try { manager.stopServiceDiscovery(this); }
        catch (RuntimeException error) {
            AdbRecoveryDiagnostics.failure(log::log, "adb_tls_mdns_cleanup_failed",
                    "stopServiceDiscovery", error);
        }
    }

    /** Small JVM-testable lifecycle gate; a failed start remains retryable until close. */
    static final class StartGate {
        private boolean started;

        synchronized boolean begin(boolean closed) {
            if (closed || started) return false;
            started = true;
            return true;
        }

        synchronized void failed() { started = false; }
        synchronized boolean started() { return started; }
    }
}
