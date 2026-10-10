package com.nada.connections.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.nada.connections.model.Person;
import com.nada.connections.service.InvalidRequestException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** JSON parsing/serialising helpers. Parsing is strict so malformed bodies become 400s. */
final class Json {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    static JsonObject parseObject(String body) {
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            // Reading through the adapter keeps the reader strict (no unquoted keys, no comments).
            JsonElement element = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new InvalidRequestException("unexpected content after JSON body");
            }
            if (!element.isJsonObject()) {
                throw new InvalidRequestException("body must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            throw new InvalidRequestException("invalid JSON");
        }
    }

    /** Required string field. Does not trim: names must come back exactly as supplied. */
    static String requireString(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || value.isJsonNull()) {
            throw new InvalidRequestException("missing field '" + field + "'");
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new InvalidRequestException("field '" + field + "' must be a string");
        }
        return value.getAsString();
    }

    /** Required whole-number field (1 and 1.0 are accepted, 1.5 is not). */
    static long requireLong(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || value.isJsonNull()) {
            throw new InvalidRequestException("missing field '" + field + "'");
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InvalidRequestException("field '" + field + "' must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new InvalidRequestException("field '" + field + "' must be an integer");
        }
    }

    static JsonObject personJson(Person person) {
        JsonObject json = new JsonObject();
        json.add("id", new JsonPrimitive(person.id()));
        json.addProperty("name", person.name());
        return json;
    }

    static JsonObject errorJson(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return json;
    }
}
