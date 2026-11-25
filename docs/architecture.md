# Soleil architecture

```mermaid
flowchart LR
    Desktop[Windows client / Electron] -->|HTTPS and WebSocket| Gateway[NGINX]
    Gateway -->|Pages and assets| Next[Next.js]
    Gateway -->|Versioned REST and WebSocket| Spring[Spring Boot]
    Spring -->|JDBC and Flyway| PostgreSQL
    Spring -->|Presence, rate limits and pub/sub| Redis
    Spring -->|Scheduled outbox worker| SMTP
    Spring -->|Optional private object storage| S3
```

Each domain has an HTTP controller and business service. The Spring Boot monolith shares a JDBC repository and PostgreSQL transaction manager across domains. PostgreSQL enforces relationships and uniqueness. The event RSVP transaction locks the event row before counting attendees. Chat and notification changes publish Redis events after commit. Reconnecting clients reload persisted history and notifications.

Community membership changes lock the community row before modifying roles or removing members. Private event RSVP claims and creation of community posts and events acquire a shared lock first. This keeps permission checks consistent with membership changes and releases upcoming private RSVP places when members leave or are removed. Unread notification counts use a partial PostgreSQL index scoped by recipient.

The API uses `/api/v1` and an OpenAPI schema. Access JWTs live in memory in the browser and expire after ten minutes. Refresh tokens rotate, are stored as digests in PostgreSQL, and are sent in an HttpOnly cookie. Resetting a password increments the account token version and revokes refresh tokens. Resource access checks enforce profile visibility, conversation membership, private group membership and ownership. The WebSocket authenticates with an initial frame and is restricted to the configured browser origin.

Redis stores expiring presence and request counters. For S3 uploads, it caches immutable thumbnails behind the same authorization checks as originals. PostgreSQL is authoritative for history and notifications. Pub/sub events can be missed by disconnected clients. Structured application logs go to stdout. The administrator status endpoint reports mail jobs that exhausted their retries.

## Relationships

```mermaid
erDiagram
    ACCOUNTS ||--o{ ACCOUNT_TOKENS : owns
    ACCOUNTS ||--o{ CONNECTIONS : sends
    ACCOUNTS ||--o{ CONNECTIONS : receives
    ACCOUNTS ||--o{ MEMBERSHIPS : joins
    COMMUNITIES ||--o{ MEMBERSHIPS : includes
    ACCOUNTS ||--o{ POSTS : authors
    COMMUNITIES o|--o{ POSTS : contains
    POSTS ||--o{ COMMENTS : receives
    POSTS ||--o{ REACTIONS : receives
    ACCOUNTS ||--o{ COMMENTS : writes
    ACCOUNTS ||--o{ REACTIONS : adds
    ACCOUNTS ||--o{ PARTICIPANTS : joins
    CONVERSATIONS ||--|{ PARTICIPANTS : includes
    CONVERSATIONS ||--o{ MESSAGES : contains
    ACCOUNTS ||--o{ MESSAGES : sends
    MESSAGES ||--o{ RECEIPTS : receives
    ACCOUNTS ||--o{ RECEIPTS : acknowledges
    ACCOUNTS ||--o{ NOTIFICATIONS : receives
    COMMUNITIES o|--o{ EVENTS : hosts
    ACCOUNTS ||--o{ EVENTS : organizes
    EVENTS ||--o{ RSVPS : receives
    ACCOUNTS ||--o{ RSVPS : creates
    ACCOUNTS ||--o{ MEDIA : uploads
```

Search uses trigram GIN indexes on user names, group names/descriptions and post/message bodies. Feed, chat history and notifications use bounded pages and deterministic ordering. Messages use a text representation of their bigint key at the JSON boundary.
