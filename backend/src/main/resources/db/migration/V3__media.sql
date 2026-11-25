create table media (
  id uuid primary key,
  owner varchar(30) not null references accounts on delete cascade,
  content_type text not null check (content_type in ('image/jpeg','application/pdf')),
  filename text not null,
  original bytea,
  thumbnail bytea,
  created_at timestamptz not null default now()
);
create index media_owner on media(owner,created_at);
create index posts_media on posts(image_url) where image_url is not null;
create index messages_media on messages(attachment_url) where attachment_url is not null;
create index profile_avatar on accounts(avatar_url) where avatar_url is not null;
create index profile_cover on accounts(cover_url) where cover_url is not null;
