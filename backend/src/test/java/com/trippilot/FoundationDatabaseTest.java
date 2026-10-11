package com.trippilot;

import static org.junit.jupiter.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Runs against real configured MySQL, never an H2 substitute. */
@EnabledIfEnvironmentVariable(named = "PHASE1_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,properties="trippilot.dispatch.enabled=false")
class FoundationDatabaseTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired com.trippilot.research.RestartRecovery recovery;
    private static final String PASSWORD = "synthetic-test-passphrase-42";

    private class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        final String email = "test-" + UUID.randomUUID() + "@example.invalid";

        HttpResponse<String> call(String method, String path, String body, boolean csrf, String contentType) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
            if (csrf) {
                var token = call("GET", "/api/identity/csrf", null, false, null);
                request.header(JsonPath.read(token.body(), "$.headerName"), JsonPath.read(token.body(), "$.token"));
            }
            if (contentType != null) { request.header("Content-Type", contentType); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception { return call("GET", path, null, false, null); }
        HttpResponse<String> post(String path, String json, boolean csrf) throws Exception { return call("POST", path, json, csrf, "application/json"); }
        String register() throws Exception {
            var response = post("/api/identity/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", true);
            assertEquals(201, response.statusCode(), response.body());
            return JsonPath.read(response.body(), "$.id");
        }
        HttpResponse<String> login(String password) throws Exception {
            return call("POST", "/api/identity/login", "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), true, "application/x-www-form-urlencoded");
        }
        String sessionCookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().getValue(); }
    }

    @Test
    void migrationsAndDurableAuthenticationProtectSessionLifecycle() throws Exception {
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
        var browser = new Browser();
        assertEquals(401, browser.get("/api/identity/me").statusCode());
        assertEquals(403, browser.post("/api/identity/register", "{}", false).statusCode());
        String id = browser.register();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, id);
        assertNotEquals(PASSWORD, hash);
        assertTrue(encoder.matches(PASSWORD, hash));
        assertEquals(401, browser.login("wrong-password").statusCode());
        String beforeLogin = browser.sessionCookie();
        assertEquals(204, browser.login(PASSWORD).statusCode());
        assertNotEquals(beforeLogin, browser.sessionCookie(), "Login must rotate session IDs");
        assertEquals(id, JsonPath.read(browser.get("/api/identity/me").body(), "$.id"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Integer.class, id) > 0);
        assertEquals(403, browser.post("/api/planning/sessions", "{\"title\":\"Trip\"}", false).statusCode());
        String cookie = browser.sessionCookie();
        assertEquals(204, browser.post("/api/identity/logout", "", true).statusCode());
        assertEquals(401, browser.get("/api/identity/me").statusCode());
        var replay = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/identity/me")).header("Cookie", "SESSION=" + cookie).GET().build();
        assertEquals(401, HttpClient.newHttpClient().send(replay, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void ownerQueriesAndDatabaseForeignKeysRejectOtherUsers() throws Exception {
        var first = new Browser();
        var second = new Browser();
        String firstId = first.register();
        String secondId = second.register();
        assertEquals(204, first.login(PASSWORD).statusCode());
        assertEquals(204, second.login(PASSWORD).statusCode());
        var created = first.post("/api/planning/sessions", "{\"title\":\"Jaipur research\"}", true);
        assertEquals(201, created.statusCode(), created.body());
        String sessionId = JsonPath.read(created.body(), "$.id");
        assertEquals(200, first.get("/api/planning/sessions/" + sessionId).statusCode());
        assertEquals(404, second.get("/api/planning/sessions/" + sessionId).statusCode());
        assertFalse(second.get("/api/planning/sessions").body().contains(sessionId));
        assertEquals(400, first.post("/api/planning/sessions", "{\"title\":\"Spoof\",\"ownerId\":\"" + secondId + "\"}", true).statusCode());
        assertEquals(400, first.post("/api/planning/sessions/" + sessionId + "/jobs", "{}", true).statusCode());
        assertEquals(403, first.get("/api/booking/submit").statusCode());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO request_revisions(id,session_id,owner_id,revision_no,requirements) VALUES (?,?,?,1,'{}')", UUID.randomUUID().toString(), sessionId, secondId));
        assertEquals(firstId, jdbc.queryForObject("SELECT owner_id FROM planning_sessions WHERE id=?", String.class, sessionId));
    }

    @Test
    void invalidInputsCannotCreateUsersOrLeakPasswords() throws Exception {
        var browser = new Browser();
        var invalid = browser.post("/api/identity/register", "{\"email\":\"bad\",\"password\":\"short-secret\"}", true);
        assertEquals(400, invalid.statusCode());
        assertFalse(invalid.body().contains("short-secret"));
        browser.register();
        var duplicate = browser.post("/api/identity/register", "{\"email\":\"" + browser.email + "\",\"password\":\"" + PASSWORD + "\"}", true);
        assertEquals(409, duplicate.statusCode());
        assertFalse(duplicate.body().contains(PASSWORD));
        assertEquals(200, browser.get("/actuator/health").statusCode());
        assertEquals(401, browser.get("/actuator/env").statusCode());
    }

    @Test
    void restartInterruptsActiveRunsWithoutReplayingQueuedOrTerminalWork() throws Exception {
        var browser = new Browser();
        String owner = browser.register();
        assertEquals(204, browser.login(PASSWORD).statusCode());
        var created = browser.post("/api/planning/sessions", "{\"title\":\"Recovery fixture\"}", true);
        String session = JsonPath.read(created.body(), "$.id");
        String context = jdbc.queryForObject("SELECT id FROM trip_contexts WHERE session_id=?", String.class, session);
        String revision = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO request_revisions(id,session_id,owner_id,revision_no,requirements) VALUES (?,?,?,1,'{}')", revision, session, owner);
        var jobs = new java.util.HashMap<String, String>();
        for (String state : java.util.List.of("RUNNING", "CANCEL_REQUESTED", "QUEUED", "SUCCEEDED")) {
            String job = UUID.randomUUID().toString();
            jobs.put(state, job);
            jdbc.update("INSERT INTO research_jobs(id,owner_id,session_id,context_id,request_revision_id,model_snapshot,harness_snapshot,status,trace_id) VALUES (?,?,?,?,?,'{}','{}',?,?)", job, owner, session, context, revision, state, UUID.randomUUID().toString());
        }
        assertEquals(2, recovery.interruptOrphanedRuns());
        for (String state : java.util.List.of("RUNNING", "CANCEL_REQUESTED")) {
            assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT status FROM research_jobs WHERE id=?", String.class, jobs.get(state)));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM research_jobs WHERE id=?", Long.class, jobs.get(state)));
        }
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM research_jobs WHERE id=?", String.class, jobs.get("QUEUED")));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM research_jobs WHERE id=?", String.class, jobs.get("SUCCEEDED")));
        assertEquals(0, recovery.interruptOrphanedRuns(), "Recovery must be idempotent");
        var rejected = assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.update("UPDATE research_jobs SET status='BOGUS' WHERE id=?", jobs.get("QUEUED")));
        assertEquals(3819, rejected.getSQLException().getErrorCode(), "MySQL must reject the state through the CHECK constraint");
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM research_jobs WHERE id=?", String.class, jobs.get("QUEUED")));
    }
}

