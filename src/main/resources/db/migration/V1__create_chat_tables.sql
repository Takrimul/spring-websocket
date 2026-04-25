CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY,
    room_id VARCHAR(100) NOT NULL,
    sender VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_room_created_at
    ON chat_messages(room_id, created_at DESC);

CREATE TABLE IF NOT EXISTS pending_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id UUID NOT NULL,
    room_id VARCHAR(100) NOT NULL,
    recipient VARCHAR(100) NOT NULL,
    sender VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_recipient_room
    ON pending_messages(recipient, room_id, created_at ASC);
