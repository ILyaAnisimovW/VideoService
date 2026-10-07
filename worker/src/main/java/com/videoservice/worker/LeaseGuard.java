package com.videoservice.worker;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class LeaseGuard implements AutoCloseable {
    private final WorkerApi api;
    private final WorkerMessages.Lease lease;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile Instant expiresAt;
    private volatile String terminalState;

    LeaseGuard(WorkerApi api, WorkerMessages.Lease lease) {
        this.api = api;
        this.lease = lease;
        this.expiresAt = lease.expiresAt();
        scheduler.scheduleAtFixedRate(this::renew, 20, 20, TimeUnit.SECONDS);
    }

    boolean active() {
        return active.get() && Instant.now().isBefore(expiresAt) && Instant.now().isBefore(lease.absoluteDeadline());
    }

    String terminalState() { return terminalState; }

    private void renew() {
        if (!active()) { active.set(false); return; }
        try {
            WorkerMessages.LeaseState state = api.heartbeat(lease);
            if (!state.jobState().equals("RUNNING")) {
                terminalState = state.jobState();
                active.set(false);
            } else {
                expiresAt = state.expiresAt();
            }
        } catch (WorkerApi.LeaseLostException ex) {
            active.set(false);
        } catch (RuntimeException ex) {
            if (!Instant.now().isBefore(expiresAt)) active.set(false);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
