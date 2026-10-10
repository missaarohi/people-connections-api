package com.nada.connections.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nada.connections.model.Person;
import com.nada.connections.service.InvalidRequestException;
import com.nada.connections.service.PeopleGraph;
import com.nada.connections.service.PersonNotFoundException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP layer: routing, request validation and JSON responses. Business rules live in {@link PeopleGraph}. */
public final class ApiServer {

    private static final Pattern CONTACTS_PATH = Pattern.compile("^/people/([^/]+)/contacts$");
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String JSON_UTF8 = "application/json; charset=utf-8";

    private final PeopleGraph graph;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    /** @param port port to listen on; 0 picks a free port (used by tests). */
    public ApiServer(PeopleGraph graph, int port) throws IOException {
        this.graph = graph;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---------------------------------------------------------------- routing

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (InvalidRequestException e) {
            sendJson(exchange, 400, Json.errorJson(e.getMessage()));
        } catch (PersonNotFoundException e) {
            sendJson(exchange, 404, Json.errorJson(e.getMessage()));
        } catch (HttpError e) {
            if (e.allow() != null) {
                exchange.getResponseHeaders().set("Allow", e.allow());
            }
            sendJson(exchange, e.status(), Json.errorJson(e.getMessage()));
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendJson(exchange, 500, Json.errorJson("internal error"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        Matcher contacts = CONTACTS_PATH.matcher(path);

        if (path.equals("/people")) {
            requireMethod(method, "POST");
            createPerson(exchange);
        } else if (path.equals("/knows")) {
            requireMethod(method, "POST");
            createConnection(exchange);
        } else if (path.equals("/path")) {
            requireMethod(method, "GET");
            findPath(exchange);
        } else if (contacts.matches()) {
            requireMethod(method, "GET");
            listContacts(exchange, parseId(contacts.group(1), "id"));
        } else {
            throw new HttpError(404, "not found");
        }
    }

    // --------------------------------------------------------------- handlers

    private void createPerson(HttpExchange exchange) throws IOException {
        JsonObject body = Json.parseObject(readBody(exchange));
        Person person = graph.addPerson(Json.requireString(body, "name"));
        sendJson(exchange, 201, Json.personJson(person));
    }

    private void createConnection(HttpExchange exchange) throws IOException {
        JsonObject body = Json.parseObject(readBody(exchange));
        long a = Json.requireLong(body, "a");
        long b = Json.requireLong(body, "b");
        graph.connect(a, b);
        exchange.sendResponseHeaders(204, -1);
    }

    private void listContacts(HttpExchange exchange, long id) throws IOException {
        JsonArray result = new JsonArray();
        for (Person contact : graph.contactsOf(id)) {
            result.add(Json.personJson(contact));
        }
        sendJson(exchange, 200, result);
    }

    private void findPath(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        long from = parseId(query.get("from"), "from");
        long to = parseId(query.get("to"), "to");

        Optional<List<Person>> path = graph.shortestPath(from, to);
        JsonObject response = new JsonObject();
        if (path.isEmpty()) {
            response.addProperty("connected", false);
        } else {
            JsonArray people = new JsonArray();
            for (Person person : path.get()) {
                people.add(Json.personJson(person));
            }
            response.addProperty("connected", true);
            response.addProperty("hops", path.get().size() - 1);
            response.add("path", people);
        }
        sendJson(exchange, 200, response);
    }

    // ---------------------------------------------------------------- helpers

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new HttpError(405, "method not allowed", expected);
        }
    }

    private static long parseId(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("missing '" + label + "'");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("'" + label + "' must be an integer id");
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        try {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.putIfAbsent(key, value);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("malformed query string");
        }
        return params;
    }

    /** Reads the body as strict UTF-8, so bad bytes give a 400 instead of silently becoming '?'. */
    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new HttpError(413, "request body too large");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidRequestException("body must be valid UTF-8");
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_UTF8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
