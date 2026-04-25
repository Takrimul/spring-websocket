CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS dm_messages (
    id UUID PRIMARY KEY,
    sender_phone VARCHAR(20) NOT NULL,
    receiver_phone VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ NULL,
    seen_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_dm_receiver_status_created
    ON dm_messages(receiver_phone, status, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_dm_pair_created
    ON dm_messages(sender_phone, receiver_phone, created_at ASC);
