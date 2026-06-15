# common — shared contracts

Plain library jar (no Spring, no `spring-boot-maven-plugin`). Holds the contracts that
two or more services must agree on, so agreement is enforced by the compiler instead
of by hoping nobody typos a string.

## Contents

| Class | Contract | Writer → Reader |
|---|---|---|
| `redis.RedisKeys` | Redis key spellings (waiting queue, admitted marker, seat hold, section sold/reservations counters) | queue-service / booking-service → event-service |
| `redis.RedisChannels` | Pub/sub channel names | see diagram below |
| `event.EventChangeMessage` | JSON payload on the `events:changes` channel | event-service → search-service |

## Notification flows (Redis pub/sub)

```
  publishers                      Redis pub/sub                     subscribers

┌─────────────────┐         ┌───────────────────────┐         ┌──────────────────┐
│  event-service  │ ──────► │    events:changes     │ ──────► │  search-service  │
│ after DB commit │         │ (EventChangeMessage)  │         │ re-indexes to ES │
└─────────────────┘         ├───────────────────────┤         └──────────────────┘
┌─────────────────┐         │                       │         ┌──────────────────┐
│  queue-service  │ ──────► │ queue:position-update │ ──────► │  queue-service   │
│ admission worker│         │ (position payload)    │         │ WS → browsers    │
└─────────────────┘         └───────────────────────┘         └──────────────────┘
```

Notes:

- The database never notifies anyone — event-service's request handler publishes
  *after* the Postgres commit (lost-notification window exists; outbox pattern is the
  phase-2 fix).
- queue-service is on both sides deliberately: the admission worker in one instance
  publishes, the WebSocket layer in *every* instance subscribes — that's how a worker
  on instance A reaches a browser connected to instance B.
- Channels are fixed mailbox names; everything variable (eventId, userId, position)
  rides in the message payload, never in the channel name.

## What belongs here / what doesn't

Belongs: key/channel name constants, cross-service message records, shared error shape
(when the first controller exists).

Does not belong: JPA entities, service-specific DTOs, anything Spring-annotated, and
message types whose producer doesn't exist in code yet — no speculative contracts.
