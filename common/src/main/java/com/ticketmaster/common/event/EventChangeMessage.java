package com.ticketmaster.common.event;

import java.time.Instant;

/**
 * The message published on the {@code events:changes} pub/sub channel whenever
 * the event catalog changes (serialized to JSON on the wire).
 *
 * <p>Publisher: event-service. Subscriber: search-service's indexer, which
 * reacts by re-syncing that event into ElasticSearch.
 *
 * <p>Deliberately a "thin event": it carries only the id, not the event data.
 * The indexer fetches current state from event-service on receipt, so a
 * duplicated or out-of-order message can never index stale data — the fetch
 * always returns the latest version.
 *
 * <p>A {@code record} = immutable data carrier; the compiler generates the
 * constructor, accessors ({@code msg.eventId()}), equals/hashCode and toString.
 */
public record EventChangeMessage(long eventId, ChangeType type, Instant occurredAt) {

    public enum ChangeType {
        CREATED, UPDATED, DELETED
    }
}
