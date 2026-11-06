-- The whole schema, in one file: nothing is deployed anywhere, so there is no history to replay.
-- A change edits this file; a running dev database is recreated (docker compose -p security down -v).

-- rows of the gallery; the picture bytes live in meme_blobs (object storage in production)
create table memes (
    id                    varchar(36)  primary key,
    author_id             uuid,                        -- the only identity a row carries: the key; null once anonymised, and then it belongs to nobody
    format                varchar(10)  not null,
    published_at          timestamp    not null,
    -- the account-closure saga's reversible mark: hidden from every public read, destroyed by nothing but the closure
    status                varchar(20)  not null default 'ACTIVE',
    marked_for_erasure_at timestamp,
    constraint ck_memes_status check (status in ('ACTIVE', 'PENDING_ERASURE')),
    constraint ck_memes_erasure_mark check ((status = 'PENDING_ERASURE') = (marked_for_erasure_at is not null))
);
create index idx_memes_author_id on memes (author_id);
create index idx_memes_pending_erasure on memes (status, marked_for_erasure_at);

-- every public read goes through the view and never sees a marked meme (MemeReadFilterTest enforces it)
create view active_memes as
    select id, author_id, format, published_at
    from memes
    where status = 'ACTIVE';

create table meme_blobs (
    object_key varchar(64) primary key,   -- meme id, or id + a variant suffix like .webp
    data       bytea       not null
);

create table content_index (
    content_hash varchar(64) primary key,
    meme_id      varchar(36) not null
);
create index idx_content_index_meme_id on content_index (meme_id);

create table meme_tags (
    meme_id varchar(36) not null,
    tag     varchar(30) not null,
    primary key (meme_id, tag)
);
create index idx_meme_tags_tag on meme_tags (tag);

create table meme_votes (
    meme_id   varchar(36)  not null,
    voter     varchar(255) not null,   -- the voter's id, in its wire form
    direction varchar(4)   not null,
    primary key (meme_id, voter),
    constraint fk_meme_votes_meme foreign key (meme_id) references memes (id) on delete cascade
);
create index idx_meme_votes_voter on meme_votes (voter);

create table meme_flags (
    meme_id varchar(64) primary key references memes (id) on delete cascade,
    nsfw    boolean     not null
);

create table settings (
    setting_key   varchar(64)  primary key,
    setting_value varchar(255) not null,
    updated_at    timestamp    not null,
    updated_by    varchar(255) not null
);

-- the transactional outbox for MEME_DELETED and friends (transactional-outbox library)
create table meme_events_outbox (
    id              varchar(36) primary key,  -- also the payload's eventId: deterministic per row
    topic           varchar(64) not null,
    event_type      varchar(64) not null,
    event_key       varchar(64) not null,     -- Kafka partition key (the meme id)
    cid             varchar(64),              -- correlation header, stamped at announce time
    payload         text        not null,
    created_at      timestamp   not null,
    published_at    timestamp,
    attempts        int         not null default 0,
    next_attempt_at timestamp
);
create index idx_meme_events_outbox_pending on meme_events_outbox (published_at, created_at);

-- blobs a deleted meme still owes the object store (deleted after the transaction commits)
create table pending_blob_deletes (
    object_key   varchar(64) primary key,
    requested_at timestamp   not null
);
create index idx_pending_blob_deletes_age on pending_blob_deletes (requested_at);
