# TicketMaster — Event Ticketing System

A Ticketmaster-style ticket booking system built as a Spring Boot microservices monorepo.
Designed for local development with Docker Compose (phase 1), with a planned upgrade path
to Kubernetes / Azure (phase 2).

## Architecture

```
                          ┌──────────────┐
        Client ◄────────► │  API Gateway │  auth · rate limiting · routing
                          └──────┬───────┘
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
      ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
      │Search Service│   │Event Service │   │Queue Service │  virtual waiting queue
      └──────┬───────┘   └──────┬───────┘   └──────┬───────┘  (Redis sorted set +
             │                  │                  │           pub/sub + admission
             ▼                  │                  │           worker + WebSocket)
      ┌──────────────┐          │                  ▼
      │ElasticSearch │          │           ┌──────────────┐
      └──────────────┘          │           │Booking Service│ seat holds (Redis TTL)
             ▲                  ▼           └──────┬───────┘ + short PG transactions
             │           ┌──────────────┐          │
             └─indexer───│  PostgreSQL  │◄─────────┤
              (pub/sub)  └──────────────┘          ▼
                                              Stripe (test mode)
```

### Services

| Module            | Responsibility                                                        | Key dependencies              |
|-------------------|-----------------------------------------------------------------------|-------------------------------|
| `gateway`         | Single entry point: routing, rate limiting, auth                      | Web, Actuator                 |
| `event-service`   | Event/venue/performer catalog (CRUD), seat map with section grouping; serves dynamic per-section availability; publishes index events | Web, JPA, PostgreSQL, Validation, **Data Redis** (pub/sub publish + read-only counter reads) |
| `search-service`  | Keyword/filter search backed by ElasticSearch; indexer consumes change events via Redis pub/sub | Web, Data Elasticsearch, Data Redis |
| `queue-service`   | Virtual waiting queue: Redis sorted set, admission worker, admitted set with TTL, WebSocket position updates | Web, WebSocket, Data Redis    |
| `booking-service` | Admission-token-gated booking: Redis seat holds (TTL), booking confirmation in short Postgres transactions, payment webhook | Web, JPA, PostgreSQL, Data Redis, Validation |
| `common`          | Shared contracts: Redis key/channel names, cross-service message records | —                             |

### Core design decisions

- **Seat map is static, but section counts are live** — individual seat status is never shown (that view is expensive and just stokes "seats vanishing" anxiety). Seats are grouped by section; per-seat correctness is enforced at booking time, not display time. What *is* shown and updated dynamically is a per-section count — "Section A: 42 left" — computed conservatively (see below).
- **Conservative section availability** — `section_available = capacity − sold − active_reservations`, where a reservation covers both Redis seat holds *and* admitted-but-not-yet-bought queue users. Counting admitted users is deliberate: we assume everyone we let through will complete a purchase, so the queue never admits more people than a section can actually serve. The live pieces live in Redis: a permanent `section:sold` counter (incremented in the confirm transaction) and a `section:reservations` sorted set keyed by expiry timestamp (pruned lazily via `ZREMRANGEBYSCORE` on read — no cron, consistent with the implicit-TTL philosophy below). event-service reads these counters read-only; queue-service and booking-service own the writes.
- **Virtual queue before booking** — users wanting to book are placed in a Redis sorted-set queue (score = enqueue time). An admission worker periodically moves the top-N into an *admitted set* (with TTL) and issues an admission token. Position updates are pushed to the browser over WebSocket via Redis pub/sub.
- **No long-lived DB locks** — seat holds live in Redis with a TTL (~10 min); expiry releases them implicitly (no cron job). Final confirmation uses a short `SELECT ... FOR UPDATE` transaction in Postgres to guarantee no double-sell.
- **Search is decoupled** — ElasticSearch is synced from Postgres via change events over Redis pub/sub (outbox-style), never dual-written by request handlers.
- **REST between services, Redis pub/sub for async** — no extra message broker in phase 1.

## Tech stack & pinned versions

| Component      | Version                          | Notes                                                            |
|----------------|----------------------------------|------------------------------------------------------------------|
| Java           | 21 (Microsoft OpenJDK)           | Spring Boot 4 baseline is 17+                                |
| Spring Boot    | 4.0.7                            | Manages all Java client library versions (see dependency-versions appendix) |
| Maven          | multi-module, via wrapper (`mvnw`) | Parent pom at repo root                          |
| PostgreSQL     | `postgres:17-alpine`             | Source of truth: events, venues, tickets, bookings               |
| Redis          | `redis:8-alpine`                 | Queue, admitted set, seat holds, pub/sub                         |
| ElasticSearch  | `elasticsearch:9.2.8`            | **Pinned to match** Spring Data Elasticsearch 6.0.x client (do not bump independently) |
| Docker Compose | local orchestration              | `--scale booking-service=N` to simulate multiple servers        |

> **Version rule:** never pick Java client library versions manually — Spring Boot's BOM decides.
> Match *server* (container) versions to what the managed clients expect. ElasticSearch is the
> tightly coupled one; Postgres/Redis drivers are wire-protocol-stable across server versions.

## Repository layout

```
TicketMaster/
├── pom.xml              # parent: com.ticketmaster:ticketmaster-parent (packaging=pom)
├── gateway/
├── event-service/
├── search-service/
├── queue-service/
├── booking-service/
├── common/              # shared contracts: RedisKeys, RedisChannels, EventChangeMessage
├── docker-compose.yml   # postgres, redis, elasticsearch (+ services later)
├── volume-data/         # bind-mounted state (gitignored): pgdata/, esdata/
└── README.md
```

## Maven multi-module structure

The repo is one Maven build: a root **parent pom** plus one module per service.
Inheritance chain: `service → ticketmaster-parent → spring-boot-starter-parent`.

**Root pom (`./pom.xml`)** — the aggregator/parent:

- `<parent>` → `spring-boot-starter-parent` 4.0.7, **with** `<relativePath/>` (empty tag =
  "fetch from remote repository", correct because Spring's parent lives in Maven Central).
- Own coordinates: `com.ticketmaster:ticketmaster-parent:0.0.1-SNAPSHOT` with
  `<packaging>pom</packaging>` — produces no jar; exists to aggregate modules and hold shared config.
- `<modules>` lists the service **folder names**.
- Shared `<properties>` (e.g. `<java.version>21</java.version>`) declared once here.

**Each child pom** — only what differs per service:

```xml
<parent>
    <groupId>com.ticketmaster</groupId>
    <artifactId>ticketmaster-parent</artifactId>
    <version>0.0.1-SNAPSHOT</version>   <!-- the PARENT's version, not Spring Boot's -->
</parent>
<artifactId>event-service</artifactId>  <!-- unique per module -->
```

Editing rules learned the hard way:

1. **`groupId`, `version`, `java.version` are inherited** — children should not redeclare them.
   Every module is `com.ticketmaster:<artifact>:0.0.1-SNAPSHOT`; bump the version in one place (root).
2. **`<version>` inside `<parent>` = the parent's own version** (`0.0.1-SNAPSHOT`).
   Writing the Spring Boot version (4.0.7) there makes Maven search for a nonexistent
   `ticketmaster-parent:4.0.7`.
3. **`<relativePath/>` (empty) means "skip local disk, use remote repo"** — keep it only on
   `spring-boot-starter-parent` (root pom). Children must omit it; the default `../pom.xml`
   then resolves to the root pom one folder up.
4. **artifactId must be unique** across modules (it's the module's identity:
   `group:artifact:version`); Group is the shared namespace, Artifact is the individual name.
5. Dependencies and `<build>` stay in children — they differ per service. Never put
   service-specific starters in the root pom or every module inherits them.
6. Metadata tags (`<name>`, `<description>`, `<developers>`) have no build effect —
   optional documentation; required only when publishing to Maven Central.

Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/`) lives at the repo root; build everything from there.

## Local development setup

Prerequisites (Windows):

1. **JDK 21** — `winget install Microsoft.OpenJDK.21`, verify with `java -version`
2. **Docker Desktop** with WSL2 backend
3. **WSL2 memory cap** — `C:\Users\<you>\.wslconfig`:
   ```ini
   [wsl2]
   memory=16GB
   processors=8
   swap=4GB
   ```
   then `wsl --shutdown` and restart Docker Desktop.
4. **IntelliJ IDEA** (Community is sufficient)

Build everything:

```
mvnw clean install        # from repo root, builds all modules
```

Run infrastructure + services (once docker-compose.yml exists):

```
docker compose up --build
docker compose up --scale booking-service=3   # simulate multiple booking servers
```

> Note: `event-service` and `booking-service` will not start without a reachable
> PostgreSQL — that is expected until the Compose file and `application.yml` configs are in place.

## Docker Compose (infrastructure)

`docker-compose.yml` at the repo root brings up the three backing services —
PostgreSQL, Redis, ElasticSearch — that every service depends on. Phase 1 runs
only infrastructure here; the Spring services are added later.

### Everyday commands

```bash
docker compose config        # validate + print the fully-resolved file (catches YAML/typos)
docker compose up -d         # start all services detached (background)
docker compose ps            # list running services + health status
docker compose logs -f       # follow logs of all services (Ctrl-C stops watching, not the containers)
docker compose logs -f elasticsearch   # follow one service
docker compose down          # stop and remove containers (data on disk is kept)
docker compose down -v       # also remove named volumes (N/A here — we use bind mounts)
```

> `docker compose ps` only lists what is already running. Empty output usually just
> means nothing has been started yet — run `up -d` first. It does **not** start anything.

### Data persistence — bind mounts under `volume-data/`

State is persisted with **bind mounts** into a single `volume-data/` folder at the repo root:

```yaml
volumes:
  - ./volume-data/pgdata:/var/lib/postgresql/data    # postgres
  - ./volume-data/esdata:/usr/share/elasticsearch/data   # elasticsearch
```

- The leading `./` is what makes it a *bind mount* (a real host path). Without it,
  Compose treats the left side as a *named volume* and demands a top-level `volumes:`
  block declaring it — a common beginner trip-up.
- Compose auto-creates these subfolders on first `up`; no `mkdir` needed.
- Add `volume-data/` to `.gitignore` so database files are never committed.
- To wipe state, stop the stack and delete the folder (`rm -rf volume-data/`). On
  Windows/WSL2 the files may be owned by the container's internal user — if deletion
  is blocked, remove from inside WSL with `sudo`.

> **Bind mounts vs. named volumes:** named volumes (`docker volume ...`, stored in
> Docker's own area) avoid host permission quirks but hide the data. We chose bind
> mounts so the data is visible and trivially deletable during development.

### Healthchecks — gotchas learned the hard way

Every service has a healthcheck so `docker compose ps` reports real readiness, and so
dependent services can later wait with `depends_on: condition: service_healthy`.

1. **A healthcheck must test that *specific* service's real readiness.** The official
   ElasticSearch tutorial checks for a TLS cert file (`config/certs/.../es01.crt`) — but
   that only exists when security is enabled. With `xpack.security.enabled: false` the
   cert is never created, so that check fails forever and the container sits `unhealthy`.
   We check the HTTP endpoint instead: `curl -fs http://localhost:9200/_cluster/health`.
2. **ElasticSearch is slow to boot** — a generous `start_period` (40s) matters more than
   a huge `retries` count. Failures during `start_period` don't count against the service.
3. **`$$` escapes a variable** so Compose passes it through to the container's shell
   rather than substituting it itself — needed in the Postgres `pg_isready` check.

### Configured services

| Service       | Image                  | Port  | Health probe                       |
|---------------|------------------------|-------|------------------------------------|
| postgres      | `postgres:17-alpine`   | 5432  | `pg_isready -U … -d …`             |
| redis         | `redis:8-alpine`       | 6379  | `redis-cli ping`                   |
| elasticsearch | `elasticsearch:9.2.8`  | 9200  | `curl …/_cluster/health`           |

ElasticSearch runs single-node with security disabled and heap capped at 512m
(`ES_JAVA_OPTS: -Xms512m -Xmx512m`) — fine for local dev, not production.
All three use `restart: unless-stopped` so they survive a host reboot.

- [x] Run - `docker compose up -d` and `docker compose ps` after finishing docker-compose.yml


## Roadmap

- [x] Service skeletons generated (Spring Initializr, Boot 4.0.7, Java 21)
- [x] Parent pom + child pom trimming — `mvn clean install -DskipTests` builds all 6 projects
      (build with `-DskipTests` until Compose infra exists: JPA services cannot boot without Postgres)
- [X] `common` module
- [x] `docker-compose.yml` with Postgres / Redis / ElasticSearch + healthchecks; bind-mounted persistence under `volume-data/`
- [ ] Event service: schema (events, venues, performers, tickets, bookings), seed data, seat map endpoint
- [ ] Search: ES index, pub/sub indexer, search endpoints
- [ ] Virtual queue: sorted set + admission worker + WebSocket updates
- [ ] Booking: admission token validation, Redis seat hold, confirm flow
- [ ] Payment: Stripe test mode + webhook
- [ ] Load test (k6): prove no double-sell under concurrency
- [ ] Phase 2: Kubernetes manifests → Azure (AKS or Container Apps, Azure Database for PostgreSQL, Azure Cache for Redis)
