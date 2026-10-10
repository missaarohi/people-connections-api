package com.nada.connections.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nada.connections.service.PeopleGraph;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** End-to-end tests: a real server on a free port, driven through java.net.http. */
class ApiServerTest {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private ApiServer server;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = new ApiServer(new PeopleGraph(), 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonElement json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body());
    }

    private long createPerson(String name) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        HttpResponse<String> response = post("/people", body.toString());
        assertEquals(201, response.statusCode());
        return json(response).getAsJsonObject().get("id").getAsLong();
    }

    private void connect(long a, long b) throws Exception {
        assertEquals(204, post("/knows", "{\"a\":" + a + ",\"b\":" + b + "}").statusCode());
    }

    private long[] createChain(int length) throws Exception {
        long[] ids = new long[length];
        for (int i = 0; i < length; i++) {
            ids[i] = createPerson("P" + i);
            if (i > 0) {
                connect(ids[i - 1], ids[i]);
            }
        }
        return ids;
    }

    // ---- POST /people ----------------------------------------------------------------------

    @Test
    void createPersonReturns201WithAssignedId() throws Exception {
        HttpResponse<String> response = post("/people", "{\"name\":\"Asha\"}");

        assertEquals(201, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonObject body = json(response).getAsJsonObject();
        assertEquals(1, body.get("id").getAsLong());
        assertEquals("Asha", body.get("name").getAsString());
    }

    @Test
    void hindiNamesComeBackExactlyAsSupplied() throws Exception {
        String name = "आशा शर्मा ";
        long id = createPerson(name);
        long other = createPerson("Rohan");
        connect(id, other);

        JsonArray contacts = json(get("/people/" + other + "/contacts")).getAsJsonArray();

        assertEquals(name, contacts.get(0).getAsJsonObject().get("name").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{", "not json", "[]", "null", "{}", "{\"name\":null}", "{\"name\":\"\"}",
            "{\"name\":\"   \"}", "{\"name\":5}", "{\"name\":[\"x\"]}", "{name: \"x\"}",
            "{\"name\":\"x\"} trailing"})
    void createPersonRejectsBadBodies(String body) throws Exception {
        HttpResponse<String> response = post("/people", body);

        assertEquals(400, response.statusCode());
        assertTrue(json(response).getAsJsonObject().has("error"));
    }

    // ---- POST /knows -----------------------------------------------------------------------

    @Test
    void knowsIsMutualAndRepeatingItIsNotAnError() throws Exception {
        long a = createPerson("Asha");
        long b = createPerson("Rohan");

        connect(a, b);
        connect(a, b);
        connect(b, a);

        JsonArray contactsOfA = json(get("/people/" + a + "/contacts")).getAsJsonArray();
        JsonArray contactsOfB = json(get("/people/" + b + "/contacts")).getAsJsonArray();
        assertEquals(1, contactsOfA.size());
        assertEquals("Rohan", contactsOfA.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(1, contactsOfB.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{", "{}", "{\"a\":1}", "{\"b\":1}", "{\"a\":1,\"b\":1}", "{\"a\":\"1\",\"b\":2}",
            "{\"a\":1.5,\"b\":2}", "{\"a\":null,\"b\":2}"})
    void knowsRejectsBadBodies(String body) throws Exception {
        createPerson("Asha");
        createPerson("Rohan");

        assertEquals(400, post("/knows", body).statusCode());
    }

    @Test
    void knowsWithUnknownPersonIs404() throws Exception {
        long a = createPerson("Asha");

        HttpResponse<String> response = post("/knows", "{\"a\":" + a + ",\"b\":9}");

        assertEquals(404, response.statusCode());
        assertEquals("no person 9", json(response).getAsJsonObject().get("error").getAsString());
    }

    // ---- GET /people/{id}/contacts ---------------------------------------------------------

    @Test
    void contactsOfPersonWithNoConnectionsIsEmptyList() throws Exception {
        long a = createPerson("Asha");

        HttpResponse<String> response = get("/people/" + a + "/contacts");

        assertEquals(200, response.statusCode());
        assertEquals(0, json(response).getAsJsonArray().size());
    }

    @Test
    void contactsOfUnknownPersonIs404() throws Exception {
        HttpResponse<String> response = get("/people/9/contacts");

        assertEquals(404, response.statusCode());
        assertEquals("no person 9", json(response).getAsJsonObject().get("error").getAsString());
    }

    @Test
    void contactsWithMalformedIdIs400() throws Exception {
        assertEquals(400, get("/people/abc/contacts").statusCode());
    }

    // ---- GET /path -------------------------------------------------------------------------

    @Test
    void pathOfThreeHopsIsConnected() throws Exception {
        long[] ids = createChain(4); // A - B - C - D

        HttpResponse<String> response = get("/path?from=" + ids[0] + "&to=" + ids[3]);

        assertEquals(200, response.statusCode());
        JsonObject body = json(response).getAsJsonObject();
        assertTrue(body.get("connected").getAsBoolean());
        assertEquals(3, body.get("hops").getAsInt());
        JsonArray path = body.getAsJsonArray("path");
        assertEquals(4, path.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(ids[i], path.get(i).getAsJsonObject().get("id").getAsLong());
            assertEquals("P" + i, path.get(i).getAsJsonObject().get("name").getAsString());
        }
    }

    @Test
    void pathOfFourHopsIsNotConnected() throws Exception {
        long[] ids = createChain(5); // A - B - C - D - E

        HttpResponse<String> response = get("/path?from=" + ids[0] + "&to=" + ids[4]);

        assertEquals(200, response.statusCode());
        JsonObject body = json(response).getAsJsonObject();
        assertEquals(false, body.get("connected").getAsBoolean());
        assertEquals(1, body.size(), "not-connected response is just {\"connected\": false}");
    }

    @Test
    void pathFromPersonToSelfIsZeroHops() throws Exception {
        long a = createPerson("Asha");

        JsonObject body = json(get("/path?from=" + a + "&to=" + a)).getAsJsonObject();

        assertTrue(body.get("connected").getAsBoolean());
        assertEquals(0, body.get("hops").getAsInt());
        assertEquals(1, body.getAsJsonArray("path").size());
    }

    @Test
    void pathPicksTheShortestRoute() throws Exception {
        long[] ids = createChain(4);
        connect(ids[0], ids[3]);

        JsonObject body = json(get("/path?from=" + ids[0] + "&to=" + ids[3])).getAsJsonObject();

        assertEquals(1, body.get("hops").getAsInt());
    }

    @Test
    void pathWithUnknownPersonIs404() throws Exception {
        long a = createPerson("Asha");

        assertEquals(404, get("/path?from=" + a + "&to=9").statusCode());
        assertEquals(404, get("/path?from=9&to=" + a).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/path", "/path?from=1", "/path?to=1", "/path?from=1&to=", "/path?from=&to=1",
            "/path?from=a&to=1", "/path?from=1&to=1.5", "/path?from=%E0%A4&to=1"})
    void pathWithMissingOrMalformedParametersIs400(String url) throws Exception {
        createPerson("Asha");

        assertEquals(400, get(url).statusCode());
    }

    // ---- routing ---------------------------------------------------------------------------

    @Test
    void unknownRouteIs404AndWrongMethodIs405() throws Exception {
        assertEquals(404, get("/nope").statusCode());
        assertEquals(405, get("/people").statusCode());
        assertEquals(405, post("/path", "{}").statusCode());
    }
}
