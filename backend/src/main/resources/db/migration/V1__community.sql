create extension if not exists pg_trgm;

create table accounts (
    username varchar(30) primary key check (username ~ '^[a-z0-9_]{3,30}$'),
    email varchar(254) unique not null,
    password_hash text not null,
    display_name varchar(80) not null,
    bio varchar(500) not null default '',
    interests varchar(200) not null default '',
    location varchar(100) not null default '',
    avatar_url varchar(500),
    cover_url varchar(500),
    private_profile boolean not null default false,
    verified boolean not null default false,
    role text not null default 'MEMBER' check (role in ('MEMBER','ADMIN')),
    token_version integer not null default 0
);
create index accounts_search on accounts using gin ((username || ' ' || display_name) gin_trgm_ops);

create table account_tokens (
    digest varchar(64) primary key,
    username varchar(30) not null references accounts on delete cascade,
    purpose text not null check (purpose in ('refresh','verify','reset')),
    expires_at timestamptz not null
);
create index account_token_owner on account_tokens (username, purpose);
create index account_token_expiry on account_tokens (expires_at);

create table connections (
    sender varchar(30) references accounts on delete cascade,
    recipient varchar(30) references accounts on delete cascade,
    accepted boolean not null default false,
    primary key (sender, recipient),
    check (sender <> recipient)
);
create unique index unique_connection on connections (least(sender, recipient), greatest(sender, recipient));
create index connection_recipient on connections (recipient);

create table communities (
    id uuid primary key default gen_random_uuid(),
    name varchar(80) not null,
    description varchar(500) not null,
    private_group boolean not null,
    created_at timestamptz not null default now()
);
create index communities_search on communities using gin ((name || ' ' || description) gin_trgm_ops);
create table memberships (
    community_id uuid references communities on delete cascade,
    username varchar(30) references accounts on delete cascade,
    role text not null check (role in ('ADMIN','MEMBER','INVITED')),
    primary key (community_id, username)
);
create index membership_account on memberships (username, community_id);

create table posts (
    id uuid primary key default gen_random_uuid(),
    author varchar(30) not null references accounts on delete cascade,
    community_id uuid references communities on delete cascade,
    body varchar(4000) not null,
    image_url varchar(500),
    created_at timestamptz not null default now()
);
create index posts_latest on posts (created_at desc, id desc);
create index posts_by_community on posts (community_id, created_at desc);
create index posts_search on posts using gin (body gin_trgm_ops);
create table reactions (
    post_id uuid references posts on delete cascade,
    username varchar(30) references accounts on delete cascade,
    reaction text not null check (reaction in ('LIKE','SUPPORT','CELEBRATE')),
    primary key (post_id, username)
);
create table comments (
    id uuid primary key default gen_random_uuid(),
    post_id uuid not null references posts on delete cascade,
    author varchar(30) not null references accounts on delete cascade,
    body varchar(1000) not null,
    created_at timestamptz not null default now()
);
create index comments_by_post on comments (post_id, created_at);

create table conversations (
    id uuid primary key default gen_random_uuid(),
    title varchar(80),
    direct_key text unique,
    created_at timestamptz not null default now()
);
create table participants (
    conversation_id uuid references conversations on delete cascade,
    username varchar(30) references accounts on delete cascade,
    primary key (conversation_id, username)
);
create index participants_account on participants (username, conversation_id);
create table messages (
    id bigint generated always as identity primary key,
    conversation_id uuid not null references conversations on delete cascade,
    author varchar(30) not null references accounts on delete cascade,
    body varchar(4000) not null,
    attachment_url varchar(500),
    created_at timestamptz not null default now()
);
create index messages_history on messages (conversation_id, id desc);
create index messages_search on messages using gin (body gin_trgm_ops);
create table receipts (
    message_id bigint references messages on delete cascade,
    username varchar(30) references accounts on delete cascade,
    delivered_at timestamptz not null default now(),
    read_at timestamptz,
    primary key (message_id, username)
);

create table notifications (
    id uuid primary key default gen_random_uuid(),
    recipient varchar(30) not null references accounts on delete cascade,
    kind varchar(40) not null,
    actor varchar(30) references accounts on delete set null,
    resource uuid,
    read boolean not null default false,
    created_at timestamptz not null default now()
);
create index notifications_inbox on notifications (recipient, created_at desc);

create table events (
    id uuid primary key default gen_random_uuid(),
    community_id uuid references communities on delete cascade,
    organizer varchar(30) not null references accounts on delete cascade,
    title varchar(80) not null,
    detail varchar(500) not null,
    starts_at timestamptz not null,
    capacity integer not null check (capacity between 1 and 10000)
);
create index events_upcoming on events (starts_at, id);
create table rsvps (
    event_id uuid references events on delete cascade,
    username varchar(30) references accounts on delete cascade,
    reminded boolean not null default false,
    primary key (event_id, username)
);

create table mail_queue (
    id bigint generated always as identity primary key,
    recipient varchar(254) not null,
    subject text not null,
    body text not null,
    attempts integer not null default 0,
    available_at timestamptz not null default now()
);
create index mail_pending on mail_queue (available_at, id) where attempts < 8;
