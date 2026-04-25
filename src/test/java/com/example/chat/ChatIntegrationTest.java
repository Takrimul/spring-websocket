package com.example.chat;

import com.example.chat.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================
 * INTEGRATION TEST — Full Alice ↔ Bob messaging scenario
 * ============================================================
 *
 * This test spins up a real Spring Boot server on a random port
 * and connects two real WebSocket clients. It is the closest thing
 * to production without a browser.
 *
 * Scenario:
 *   1. Alice connects
 *   2. Bob connects
 *   3. Both subscribe to room 42
 *   4. Alice starts typing → Bob sees the indicator
 *   5. Alice sends a message → Bob receives it
 *   6. Bob sends a seen receipt → Alice sees "✓✓"
 *   7. Bob replies → Alice receives it
 *   8. Test validates every step
 *   9. Error handling tested (empty message)
 *
 * @SpringBootTest(webEnvironment = RANDOM_PORT) starts the full
 * application context with an embedded Tomcat on a free port.
 * This guarantees tests don't conflict on CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChatIntegrationTest {

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;
    private StompSession aliceSession;
    private StompSession bobSession;

    private static final String ROOM_ID = "42";
    private static final String ALICE   = "Alice";
    private static final String BOB     = "Bob";

    /** Messages received by each user — thread-safe queues */
    private final BlockingQueue<ChatMessage> aliceMessages   = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChatMessage> bobMessages     = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChatMessage> aliceErrors     = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChatMessage> aliceTypingEvents = new LinkedBlockingQueue<>();

    private static final int TIMEOUT_SECONDS = 5;

    // =========================================================================
    // SETUP — Create STOMP clients
    // =========================================================================

    @BeforeEach
    void setUp() throws Exception {
        // SockJS client with WebSocket transport
        // SockJS tries WebSocket first; falls back to HTTP polling
        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient())
        );
        SockJsClient sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);

        // Configure Jackson to handle Java 8 time types (Instant)
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        stompClient.setMessageConverter(converter);

        String url = "http://localhost:" + port + "/ws";

        // Connect ALICE
        aliceSession = connectUser(url, ALICE);

        // Connect BOB
        bobSession = connectUser(url, BOB);

        // ── Subscribe Alice to the room ──────────────────────────────────────
        aliceSession.subscribe(
                "/topic/chat.room." + ROOM_ID,
                frameHandler(ChatMessage.class, aliceMessages)
        );

        // Subscribe Alice to typing events (same topic, different queue)
        aliceSession.subscribe(
                "/topic/chat.room." + ROOM_ID,
                frameHandler(ChatMessage.class, aliceTypingEvents)
        );

        // Subscribe Alice to her private notifications (seen receipts, errors)
        aliceSession.subscribe(
                "/user/queue/notifications",
                frameHandler(ChatMessage.class, aliceMessages)  // reuse same queue
        );

        aliceSession.subscribe(
                "/user/queue/errors",
                frameHandler(ChatMessage.class, aliceErrors)
        );

        // ── Subscribe Bob to the room ────────────────────────────────────────
        bobSession.subscribe(
                "/topic/chat.room." + ROOM_ID,
                frameHandler(ChatMessage.class, bobMessages)
        );

        bobSession.subscribe(
                "/user/queue/messages",
                frameHandler(ChatMessage.class, bobMessages)
        );

        // Wait for subscriptions to settle
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() {
        if (aliceSession != null && aliceSession.isConnected()) {
            aliceSession.disconnect();
        }
        if (bobSession != null && bobSession.isConnected()) {
            bobSession.disconnect();
        }
    }

    // =========================================================================
    // TEST 1: Alice sends a chat message, Bob receives it
    // =========================================================================

    @Test
    @Order(1)
    void testAliceSendsMessage_BobReceivesIt() throws Exception {
        // Arrange
        ChatMessage msg = new ChatMessage();
        msg.setContent("Hey Bob! Are you there?");
        msg.setRoomId(ROOM_ID);

        // Act: Alice sends to /app/chat.send
        // Spring strips /app → routes to @MessageMapping("/chat.send")
        // Controller returns enriched message → broker sends to /topic/chat.room.42
        aliceSession.send("/app/chat.send." + ROOM_ID, msg);

        // Assert: Bob receives the message within 5 seconds
        ChatMessage received = bobMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotNull(received, "Bob should receive Alice's message");
        assertEquals(ChatMessage.Type.CHAT, received.getType());
        assertEquals(ALICE, received.getFrom()); // server sets this from Principal
        assertEquals("Hey Bob! Are you there?", received.getContent());
        assertNotNull(received.getId());         // server assigns UUID
        assertNotNull(received.getTimestamp()); // server assigns timestamp
    }

    // =========================================================================
    // TEST 2: Typing indicator
    // =========================================================================

    @Test
    @Order(2)
    void testAliceIsTyping_BobSeesIndicator() throws Exception {
        // Arrange: build a TYPING event
        ChatMessage typingEvent = new ChatMessage();
        typingEvent.setType(ChatMessage.Type.TYPING);
        typingEvent.setRoomId(ROOM_ID);

        // Act: Alice sends typing indicator to /app/chat.typing
        aliceSession.send("/app/chat.typing." + ROOM_ID, typingEvent);

        // Assert: Bob sees the typing indicator
        ChatMessage received = bobMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(received, "Bob should see typing indicator");
        assertEquals(ChatMessage.Type.TYPING, received.getType());
        assertEquals(ALICE, received.getFrom()); // server sets sender from Principal

        // Arrange: Alice stops typing
        ChatMessage stopTyping = new ChatMessage();
        stopTyping.setType(ChatMessage.Type.STOP_TYPING);
        stopTyping.setRoomId(ROOM_ID);

        // Act
        aliceSession.send("/app/chat.typing." + ROOM_ID, stopTyping);

        // Assert
        ChatMessage stopReceived = bobMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(stopReceived);
        assertEquals(ChatMessage.Type.STOP_TYPING, stopReceived.getType());
    }

    // =========================================================================
    // TEST 3: Bob sends seen receipt, Alice sees the "✓✓"
    // =========================================================================

    @Test
    @Order(3)
    void testBobSendsSeen_AliceReceivesReceipt() throws Exception {
        // First, Alice sends a message and capture its ID
        ChatMessage msg = new ChatMessage();
        msg.setContent("Did you see this?");
        msg.setRoomId(ROOM_ID);

        aliceSession.send("/app/chat.send." + ROOM_ID, msg);

        // Wait for Bob to receive it (so we have the message ID)
        ChatMessage bobReceived = bobMessages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(bobReceived);
        String messageId = bobReceived.getId(); // the server-assigned UUID

        // Now Bob sends a SEEN receipt: "I've seen message <messageId>"
        ChatMessage seenEvent = new ChatMessage();
        seenEvent.setType(ChatMessage.Type.SEEN);
        seenEvent.setSeenMessageId(messageId);
        seenEvent.setTo(ALICE);         // who sent the original message
        seenEvent.setRoomId(ROOM_ID);

        bobSession.send("/app/chat.seen", seenEvent);

        // Alice should receive the seen receipt on her notifications channel
        // We poll Alice's queue for a SEEN-type message
        ChatMessage seenReceipt = null;
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        while (System.currentTimeMillis() < deadline) {
            ChatMessage m = aliceMessages.poll(500, TimeUnit.MILLISECONDS);
            if (m != null && m.getType() == ChatMessage.Type.SEEN) {
                seenReceipt = m;
                break;
            }
        }

        assertNotNull(seenReceipt, "Alice should receive seen receipt from Bob");
        assertEquals(BOB, seenReceipt.getFrom());
        assertEquals(messageId, seenReceipt.getSeenMessageId());
    }

    // =========================================================================
    // TEST 4: Error handling — empty message content
    // =========================================================================

    @Test
    @Order(4)
    void testEmptyMessageContent_AliceReceivesError() throws Exception {
        // Alice sends a message with no content — should trigger validation error
        ChatMessage badMsg = new ChatMessage();
        badMsg.setContent("");  // Empty — ChatService.validate() should reject this
        badMsg.setRoomId(ROOM_ID);

        aliceSession.send("/app/chat.send." + ROOM_ID, badMsg);

        // Alice should receive an error on her /user/queue/errors channel
        ChatMessage error = aliceErrors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotNull(error, "Alice should receive a validation error");
        assertEquals(ChatMessage.Type.ERROR, error.getType());
        assertEquals("VALIDATION_ERROR", error.getErrorCode());
        assertNotNull(error.getErrorDetail());
    }

    // =========================================================================
    // TEST 5: Bob replies to Alice
    // =========================================================================

    @Test
    @Order(5)
    void testBobReplies_AliceReceivesReply() throws Exception {
        ChatMessage reply = new ChatMessage();
        reply.setContent("Yes! I see you Alice!");
        reply.setRoomId(ROOM_ID);

        bobSession.send("/app/chat.send." + ROOM_ID, reply);

        // Alice receives Bob's reply via the room broadcast
        ChatMessage aliceReceived = null;
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        while (System.currentTimeMillis() < deadline) {
            ChatMessage m = aliceMessages.poll(500, TimeUnit.MILLISECONDS);
            if (m != null && m.getType() == ChatMessage.Type.CHAT
                    && BOB.equals(m.getFrom())) {
                aliceReceived = m;
                break;
            }
        }

        assertNotNull(aliceReceived, "Alice should receive Bob's reply");
        assertEquals(BOB, aliceReceived.getFrom()); // server-assigned, not spoofable
        assertEquals("Yes! I see you Alice!", aliceReceived.getContent());
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Connect a named user via STOMP.
     * The Authorization header is picked up by AuthChannelInterceptor
     * from the CONNECT frame's native headers.
     */
    private StompSession connectUser(String url, String username)
            throws InterruptedException, ExecutionException, TimeoutException {

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        // For SockJS, auth headers can also go here but STOMP headers are more reliable

        CompletableFuture<StompSession> future = stompClient.connectAsync(
                url,
                httpHeaders,
                new StompSessionHandlerAdapter() {

                    @Override
                    public void afterConnected(StompSession session,
                                               StompHeaders connectedHeaders) {
                        System.out.println("[TEST] " + username + " connected");
                    }

                    @Override
                    public void handleException(StompSession session,
                                                StompCommand command,
                                                StompHeaders headers,
                                                byte[] payload,
                                                Throwable exception) {
                        System.err.println("[TEST] STOMP exception for " + username
                                + ": " + exception.getMessage());
                    }

                    @Override
                    public void handleTransportError(StompSession session,
                                                     Throwable exception) {
                        System.err.println("[TEST] Transport error for " + username
                                + ": " + exception.getMessage());
                    }

                    // Set auth in the STOMP CONNECT frame headers
                    // Our AuthChannelInterceptor reads "Authorization" native header
                /*    @Override
                    public StompHeaders getConnectHeaders() {
                        StompHeaders connectHeaders = new StompHeaders();
                        connectHeaders.add("Authorization", "Bearer " + username);
                        return connectHeaders;
                    }*/
                }
        );

        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Generic STOMP frame handler that deserializes the payload and
     * adds it to the given queue.
     *
     * The Type parameter T tells Jackson which class to deserialize into.
     */
    private <T> StompFrameHandler frameHandler(Class<T> type, BlockingQueue<T> queue) {
        return new StompFrameHandler() {

            @Override
            public Type getPayloadType(StompHeaders headers) {
                // Tell Spring how to deserialize the STOMP frame body
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // Cast is safe because we declared the type above
                queue.offer(type.cast(payload));
            }
        };
    }
}