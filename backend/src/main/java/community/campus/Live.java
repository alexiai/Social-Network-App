package community.campus;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Configuration
@EnableWebSocket
class LiveConfig implements WebSocketConfigurer {
    private final Live live;
    private final String origin;

    LiveConfig(Live live, @Value("${app.origin}") String origin) {
        this.live = live;
        this.origin = origin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(live, "/live").setAllowedOrigins(origin);
    }

    @Bean
    RedisMessageListenerContainer liveListener(RedisConnectionFactory connection) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener((message, pattern) -> live.deliver(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)),
                new ChannelTopic(Live.CHANNEL));
        return container;
    }
}

@org.springframework.stereotype.Component
class Live extends TextWebSocketHandler {
    static final String CHANNEL = "campus:live";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Live.class);
    private final StringRedisTemplate redis;
    private final JwtDecoder decoder;
    private final ConcurrentHashMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();

    Live(StringRedisTemplate redis, JwtDecoder decoder) {
        this.redis = redis;
        this.decoder = decoder;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(4096);
        session.getAttributes().put("opened", java.time.Instant.now());
        sockets.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws java.io.IOException {
        try {
            var attributes = session.getAttributes();
            var now = java.time.Instant.now();
            if (message.getPayload().startsWith("auth ")) {
                var jwt = decoder.decode(message.getPayload().substring(5));
                if (attributes.containsKey("username") && !jwt.getSubject().equals(attributes.get("username"))) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                attributes.put("username", jwt.getSubject());
                attributes.put("expires", jwt.getExpiresAt());
            } else if (!message.getPayload().equals("ping")) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            if (!(attributes.get("expires") instanceof java.time.Instant expires) || !expires.isAfter(now)) {
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            redis.opsForZSet().add(presenceKey((String) attributes.get("username")), session.getId(), now.plusSeconds(45).toEpochMilli());
            redis.expire(presenceKey((String) attributes.get("username")), Duration.ofMinutes(2));
            if (message.getPayload().startsWith("auth ")) {
                synchronized (session) { session.sendMessage(new TextMessage("ready")); }
            }
        } catch (RuntimeException rejected) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sockets.remove(session.getId());
        if (session.getAttributes().get("username") instanceof String name) {
            try {
                redis.opsForZSet().remove(presenceKey(name), session.getId());
            } catch (org.springframework.dao.DataAccessException error) {
                LOG.debug("Presence will expire automatically", error);
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    void expireSockets() {
        var now = java.time.Instant.now();
        sockets.values().forEach(session -> {
            var attributes = session.getAttributes();
            var deadline = attributes.get("expires") instanceof java.time.Instant expires
                ? expires : ((java.time.Instant) attributes.get("opened")).plusSeconds(10);
            if (deadline.isBefore(now)) close(session);
        });
    }

    boolean online(String username) {
        Long count = redis.opsForZSet().count(presenceKey(username), System.currentTimeMillis(), Double.POSITIVE_INFINITY);
        return count != null && count > 0;
    }

    private String presenceKey(String username) { return "campus:presence:" + username; }

    void publish(String username, String event) {
        Runnable send = () -> {
            try {
                redis.convertAndSend(CHANNEL, username + " " + event);
            } catch (org.springframework.dao.DataAccessException error) {
                LOG.warn("Live update could not be published", error);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else send.run();
    }

    void deliver(String payload) {
        String[] parts = payload.split(" ", 2);
        if (parts.length != 2) return;
        sockets.values().forEach(session -> {
            var attributes = session.getAttributes();
            if (!parts[0].equals(attributes.get("username"))
                || !(attributes.get("expires") instanceof java.time.Instant expiration)
                || !expiration.isAfter(java.time.Instant.now())) return;
            synchronized (session) {
                try {
                    if (session.isOpen()) session.sendMessage(new TextMessage(parts[1]));
                } catch (java.io.IOException | IllegalStateException error) {
                    close(session);
                }
            }
            if (parts[1].equals("sessions-revoked")) close(session);
        });
    }

    private void close(WebSocketSession session) {
        try { session.close(); }
        catch (java.io.IOException error) { LOG.debug("Socket already closed", error); }
    }
}
