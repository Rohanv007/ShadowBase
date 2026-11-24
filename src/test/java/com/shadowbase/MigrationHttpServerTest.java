package com.shadowbase;

import com.shadowbase.migration.MigrationService.MigrationResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class MigrationHttpServerTest {
    private MigrationHttpServerTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<String> submittedSql = new AtomicReference<>();
        MigrationResult result = new MigrationResult("users", "_shadow_users",
                12, 3, true, 48);
        try (MigrationHttpServer server = new MigrationHttpServer(sql -> {
            submittedSql.set(sql);
            return result;
        }, 0)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.getPort();

            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), "health response status");
            assertContains(health.body(), "\"status\":\"ok\"", "health response body");

            String validSql = "ALTER TABLE users ADD COLUMN phone VARCHAR(20)";
            HttpResponse<String> migration = client.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/migrations"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(validSql)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, migration.statusCode(), "migration response status");
            assertEquals(validSql, submittedSql.get(), "migration SQL passed to service");
            assertContains(migration.body(), "\"consistent\":true", "migration response body");

            HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/migrations"))
                    .POST(HttpRequest.BodyPublishers.ofString("DROP TABLE users")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, rejected.statusCode(), "invalid DDL response status");
            assertEquals(validSql, submittedSql.get(), "invalid DDL must not reach migration service");

            String oversizedBody = "x".repeat(16_385);
            HttpResponse<String> oversized = client.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/migrations"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            oversizedBody, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, oversized.statusCode(), "oversized request response status");
        }
        System.out.println("MigrationHttpServerTest passed");
    }

    private static void assertContains(String actual, String expected, String description) {
        if (!actual.contains(expected)) {
            throw new AssertionError(description + ": expected <" + expected + "> in <" + actual + ">");
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
