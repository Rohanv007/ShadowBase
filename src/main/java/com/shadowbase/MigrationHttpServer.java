package com.shadowbase;

import com.shadowbase.migration.MigrationService;
import com.shadowbase.migration.MigrationService.MigrationResult;
import com.shadowbase.parser.DdlParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MigrationHttpServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationHttpServer.class);
    private static final int MAX_REQUEST_BYTES = 16_384;

    private final HttpServer server;
    private final ExecutorService executor;

    public MigrationHttpServer(MigrationAction migrationAction, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, migrationAction));
    }

    public void start() {
        server.start();
        LOGGER.info("ShadowBase HTTP server listening on http://127.0.0.1:{}",
                server.getAddress().getPort());
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void handle(HttpExchange exchange, MigrationAction migrationAction)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/health".equals(path)) {
            respond(exchange, 200, "{\"status\":\"ok\"}");
            return;
        }
        if (!"POST".equals(method) || !"/migrations".equals(path)) {
            respond(exchange, 404, "{\"error\":\"Not found\"}");
            return;
        }

        byte[] requestBody;
        try (var input = exchange.getRequestBody()) {
            requestBody = input.readNBytes(MAX_REQUEST_BYTES + 1);
        }
        if (requestBody.length > MAX_REQUEST_BYTES) {
            respond(exchange, 413, "{\"error\":\"Request body is too large\"}");
            return;
        }

        String alterSql = new String(requestBody, StandardCharsets.UTF_8).trim();
        try {
            DdlParser.parse(alterSql);
            MigrationResult result = migrationAction.migrate(alterSql);
            respond(exchange, 200, "{\"status\":\"complete\",\"table\":"
                    + jsonString(result.tableName()) + ",\"shadowTable\":"
                    + jsonString(result.shadowTableName()) + ",\"rowsCopied\":"
                    + result.rowsCopied() + ",\"batches\":" + result.batches()
                    + ",\"consistent\":" + result.consistent() + ",\"elapsedMillis\":"
                    + result.elapsedMillis() + "}");
        } catch (IllegalArgumentException invalidRequest) {
            respond(exchange, 400, "{\"error\":" + jsonString(invalidRequest.getMessage()) + "}");
        } catch (SQLException failure) {
            LOGGER.error("Migration request failed", failure);
            respond(exchange, 500, "{\"error\":\"Migration failed; see server logs\"}");
        } catch (IllegalStateException failure) {
            LOGGER.error("Migration verification failed", failure);
            respond(exchange, 500, "{\"error\":\"Migration verification failed; see server logs\"}");
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, "{\"error\":\"Migration was interrupted\"}");
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    @FunctionalInterface
    public interface MigrationAction {
        MigrationResult migrate(String alterSql) throws SQLException, InterruptedException;
    }
}
