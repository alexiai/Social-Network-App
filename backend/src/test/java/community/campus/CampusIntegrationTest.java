package community.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "app.secret=test-only-secret-for-spring-boot-integration-suite",
    "app.secure-cookie=false",
    "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CampusIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store store;

    @Test
    void authRotationPrivateContentAndMessaging() throws Exception {
        mvc.perform(get("/api/v1/posts")).andExpect(status().isUnauthorized());

        String alice = register("alice");
        String bob = register("bob");
        String carol = register("carol");
        var login = login("alice");
        String cookie = login.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly", "SameSite=Strict");
        var refreshCookie = new Cookie("campus_refresh", cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';')));
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie).header("X-Campus-Request", "test"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie).header("X-Campus-Request", "test"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/status").header("Authorization", alice)).andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/profiles/me").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"displayName":"Alice","bio":"Friends only","interests":"art","location":"Campus","privateProfile":true}
                """)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/profiles/alice").header("Authorization", bob))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bio").doesNotExist());

        mvc.perform(put("/api/v1/connections/bob").header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/connections/alice").header("Authorization", bob)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/profiles/alice").header("Authorization", bob)).andExpect(jsonPath("$.bio").value("Friends only"));

        var chat = mvc.perform(post("/api/v1/chats").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"people\":[\"bob\"]}")).andExpect(status().isOk()).andReturn();
        String id = json.readValue(chat.getResponse().getContentAsString(), String.class);
        String path = "/api/v1/chats/" + id + "/messages";
        mvc.perform(post(path).header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Private message\"}")).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", bob)).andExpect(jsonPath("$[0].display_name").value("alice"))
            .andExpect(jsonPath("$[0].body").value("Private message"));
        mvc.perform(get(path).header("Authorization", carol)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", carol).contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Unauthorized\"}")).andExpect(status().isNotFound());

        var post = mvc.perform(post("/api/v1/posts").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Private post\"}")).andExpect(status().isOk()).andReturn();
        String postId = json.readTree(post.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get("/api/v1/posts").header("Authorization", carol)).andExpect(jsonPath("$").isEmpty());
        mvc.perform(put("/api/v1/posts/" + postId + "/reaction?kind=LIKE").header("Authorization", carol))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", bob)).andExpect(status().isNotFound());
    }

    @Test
    void invitedMembersCanAccessPrivateUploadsAndReserveLimitedSeats() throws Exception {
        String organizer = register("organizer");
        String guest = register("guest");
        String outsider = register("outsider");
        var community = mvc.perform(post("/api/v1/communities").header("Authorization", organizer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Study group","description":"Private group","privateGroup":true}
                """)).andExpect(status().isOk()).andReturn();
        String groupId = json.readTree(community.getResponse().getContentAsString()).path("id").asText();
        String group = "/api/v1/communities/" + groupId;
        mvc.perform(put(group + "/membership").header("Authorization", guest)).andExpect(status().isNotFound());
        mvc.perform(post(group + "/invitations").header("Authorization", organizer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"guest\"}")).andExpect(status().isOk());
        mvc.perform(get(group + "/members").header("Authorization", guest)).andExpect(status().isNotFound());
        mvc.perform(put(group + "/membership").header("Authorization", guest)).andExpect(status().isOk());
        mvc.perform(get(group + "/members").header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(get(group + "/members").header("Authorization", guest))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].display_name").value("organizer"));
        String promoteGuest = group + "/members/guest/role";
        String adminRole = json.writeValueAsString(Map.of("role", "ADMIN"));
        mvc.perform(put(promoteGuest).header("Authorization", guest).contentType(MediaType.APPLICATION_JSON).content(adminRole))
            .andExpect(status().isNotFound());
        mvc.perform(put(promoteGuest).header("Authorization", organizer).contentType(MediaType.APPLICATION_JSON).content(adminRole))
            .andExpect(status().isOk());
        mvc.perform(get(group + "/members").header("Authorization", guest))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].role").value("ADMIN"));

        byte[] image = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jX2sAAAAASUVORK5CYII=");
        var upload = mvc.perform(multipart("/api/v1/media").file(
            new org.springframework.mock.web.MockMultipartFile("file", "picture.png", "image/png", image))
            .header("Authorization", organizer)).andExpect(status().isCreated()).andReturn();
        String url = json.readTree(upload.getResponse().getContentAsString()).path("url").asText();
        mvc.perform(get(url).header("Authorization", guest)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/posts").header("Authorization", organizer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("communityId", groupId, "body", "Discussion", "imageUrl", url))))
            .andExpect(status().isOk());
        mvc.perform(get(url).param("thumb", "true").header("Authorization", guest))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(get(url).header("Authorization", outsider)).andExpect(status().isNotFound());

        var event = mvc.perform(post("/api/v1/events").header("Authorization", organizer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title", "One seat", "detail", "Study meetup", "capacity", 1,
                "communityId", groupId, "startsAt", java.time.OffsetDateTime.now().plusHours(2).toString()))))
            .andExpect(status().isOk()).andReturn();
        String eventId = json.readTree(event.getResponse().getContentAsString()).path("id").asText();
        String rsvp = "/api/v1/events/" + eventId + "/rsvp";
        mvc.perform(put(rsvp).header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(put(rsvp).header("Authorization", guest)).andExpect(status().isOk());
        mvc.perform(put(rsvp).header("Authorization", organizer)).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/notifications/unread").header("Authorization", guest))
            .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/v1/communities").param("id", groupId).header("Authorization", guest))
            .andExpect(jsonPath("$[0].name").value("Study group"));
        mvc.perform(put("/api/v1/notifications/read-all").header("Authorization", guest)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications").param("unread", "true").header("Authorization", guest))
            .andExpect(jsonPath("$").isEmpty());
        mvc.perform(put(promoteGuest).header("Authorization", organizer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("role", "MEMBER")))).andExpect(status().isOk());
        var moderated = mvc.perform(post("/api/v1/posts").header("Authorization", guest)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("communityId", groupId, "body", "Moderated post"))))
            .andExpect(status().isOk()).andReturn();
        String moderatedId = json.readTree(moderated.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(delete("/api/v1/posts/" + moderatedId).header("Authorization", outsider)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/posts/" + moderatedId).header("Authorization", organizer)).andExpect(status().isOk());
        mvc.perform(delete(group + "/membership").header("Authorization", organizer)).andExpect(status().isBadRequest());
        mvc.perform(delete(group + "/members/guest").header("Authorization", organizer)).andExpect(status().isOk());
        mvc.perform(get(group + "/members").header("Authorization", guest)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/events").param("id", eventId).header("Authorization", guest))
            .andExpect(jsonPath("$").isEmpty());
        mvc.perform(put(rsvp).header("Authorization", organizer)).andExpect(status().isOk());
    }

    @Test
    void signedInPasswordChangeRevokesExistingSessions() throws Exception {
        String bearer = register("password_change");
        var previousLogin = login("password_change");
        String cookie = previousLogin.getResponse().getHeader("Set-Cookie");
        var refresh = new Cookie("campus_refresh", cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';')));
        String previousPassword = "test-password-only";
        String updatedPassword = "another-test-passphrase";
        String path = "/api/v1/auth/password";
        String payload = json.writeValueAsString(Map.of("currentPassword", previousPassword, "newPassword", updatedPassword));

        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(put(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("currentPassword", "incorrect-passphrase", "newPassword", updatedPassword))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/profiles/me").header("Authorization", bearer)).andExpect(status().isOk());

        mvc.perform(put(path).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(get("/api/v1/profiles/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refresh).header("X-Campus-Request", "test")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("identity", "password_change", "password", updatedPassword))))
            .andExpect(status().isOk());
    }

    @Test
    void operationalMetricsAreRestrictedToAdministrators() throws Exception {
        String member = register("metrics_member");
        register("metrics_admin");
        store.write("update accounts set role='ADMIN' where username=?", "metrics_admin");
        String admin = "Bearer " + json.readTree(login("metrics_admin").getResponse().getContentAsString())
            .path("accessToken").asText();
        mvc.perform(get("/api/v1/admin/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/status").header("Authorization", member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/status").header("Authorization", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending_mail").isNumber())
            .andExpect(jsonPath("$.failed_mail").isNumber())
            .andExpect(jsonPath("$.http.requests").isNumber())
            .andExpect(jsonPath("$.http.serverErrors").isNumber())
            .andExpect(jsonPath("$.uptime_seconds").isNumber());
    }

    private String register(String username) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", username, "displayName", username,
                "email", username + "@example.test", "password", "test-password-only")))).andExpect(status().isCreated());
        store.write("update accounts set verified=true where username=?", username);
        return "Bearer " + json.readTree(login(username).getResponse().getContentAsString()).path("accessToken").asText();
    }

    private MvcResult login(String username) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("identity", username, "password", "test-password-only"))))
            .andExpect(status().isOk()).andReturn();
    }
}
