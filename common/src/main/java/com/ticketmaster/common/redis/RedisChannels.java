package com.ticketmaster.common.redis;

public final class RedisChannels {
    public static final String EVENT_CHANGES = "events:changes";
    public static final String QUEUE_POSITION_UPDATES = "queue:position-update";
    private RedisChannels() {

    }
}
