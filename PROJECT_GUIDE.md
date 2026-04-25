# Chat Project Guide

## 1) How to run

### Prerequisites
- Java 17 installed
- Maven wrapper files are already in this repo (`mvnw`, `mvnw.cmd`)

### Start the Spring Boot server
- Windows PowerShell:
  - `.\mvnw.cmd spring-boot:run`
- Or package first, then run:
  - `.\mvnw.cmd clean package`
  - `java -jar .\target\chat-0.0.1-SNAPSHOT.jar`

Server starts on `http://localhost:8080` and WebSocket endpoint is `http://localhost:8080/ws`.

### Open the test client
- Open `chat-test-client.html` in your browser.
- Click **Connect** for Alice and Bob.
- Keep both in the same room (default `42`) to exchange room messages.

## 2) How WebSocket works in this project

This app uses **WebSocket + STOMP** with Spring’s simple in-memory broker.

### Core flow
1. Browser creates SockJS connection to `/ws`.
2. STOMP `CONNECT` frame is sent with `Authorization: Bearer <username>`.
3. `AuthChannelInterceptor` reads that header and sets `Principal`.
4. Client `SUBSCRIBE`s to:
   - `/topic/chat.room.{roomId}` for room broadcast messages
   - `/user/queue/notifications` for seen receipts
   - `/user/queue/errors` for validation/auth errors
   - `/user/queue/history` for replayed offline room messages
5. Client `SEND`s to `/app/...` destinations (handled by `@MessageMapping`).
6. Server publishes responses/events to broker destinations (`/topic/...` or `/user/...`).

### Destination prefixes used
- `/app/**` -> incoming app commands routed to controller methods
- `/topic/**` -> broadcast fan-out to all subscribers
- `/user/**` -> per-user private queues

Configured in `WebSocketConfig`.

### Controller routes
- `@MessageMapping("/chat.send/{roomId}")`  
  Validates and broadcasts a chat message to `/topic/chat.room.{roomId}`.
- `@MessageMapping("/chat.typing/{roomId}")`  
  Broadcasts typing/stop events to room topic.
- `@MessageMapping("/chat.seen")`  
  Sends seen receipt to original sender’s private queue.
- `@MessageMapping("/chat.history/{roomId}")`  
  Replays room history to reconnecting user via `/user/queue/history`.

### Why reconnect now works for missed messages

Previously, room chat was live-only (`/topic/chat.room.*`) so offline users missed messages.

Now:
- Sent room chat messages are kept in in-memory history (`ChatService`).
- When a user reconnects, client sends `SEND /app/chat.history/{roomId}`.
- Server replays stored messages to that user’s `/user/queue/history`.
- Client renders those messages and tags them internally as history frames.

This makes Alice receive Bob’s messages sent while she was disconnected (for the current server uptime).

## 3) Important behavior notes

- History is in-memory only; it is lost when server restarts.
- History is capped (latest 100 messages per room) to keep memory bounded.
- User identity is demo-style (`Bearer alice`, `Bearer bob`), not production JWT validation.

## 4) Useful files to read

- `src/main/java/com/example/chat/config/WebSocketConfig.java`
- `src/main/java/com/example/chat/security/AuthChannelInterceptor.java`
- `src/main/java/com/example/chat/controller/ChatController.java`
- `src/main/java/com/example/chat/service/ChatService.java`
- `chat-test-client.html`
