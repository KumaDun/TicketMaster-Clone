package com.ticketmaster.common.redis;

/**
 * Builds the Redis key strings used across services.
 *
 * <p>Redis is one big shared map: data is addressed only by its key string.
 * These methods are the single source of truth for how keys are spelled, so a
 * writer in one service and a reader in another can never drift apart.
 *
 * <p>Naming scheme: {@code <domain>:<thing>:<ids>} — easy to eyeball with
 * {@code redis-cli --scan --pattern 'queue:*'} while debugging.
 */
public final class RedisKeys {

    /**
     * Sorted set: the waiting queue for one event.
     * Member = userId, score = enqueue timestamp (millis) — so ZRANGE returns FIFO order.
     * Written/read by queue-service only.
     */
    public static String waitingQueue(long eventId) {
        return "queue:waiting:" + eventId;
    }

    /**
     * Plain value with TTL: present = this user is admitted to book for this event.
     * Value holds the admission token. WRITTEN by queue-service's admission worker,
     * READ by booking-service to gate the booking endpoint — the cross-service contract.
     */
    public static String admitted(long eventId, String userId) {
        return "queue:admitted:" + eventId + ":" + userId;
    }

    /**
     * Plain value with TTL (~10 min): a seat hold. Expiry releases the hold
     * implicitly — no cleanup job needed. Owned by booking-service.
     */
    public static String seatHold(long eventId, long seatId) {
        return "booking:hold:" + eventId + ":" + seatId;
    }

    private RedisKeys() {
        // namespace class, never instantiated
    }
}
