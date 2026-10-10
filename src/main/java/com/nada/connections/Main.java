package com.nada.connections;

import com.nada.connections.http.ApiServer;
import com.nada.connections.service.PeopleGraph;

import java.io.IOException;

public final class Main {

    private static final int DEFAULT_PORT = 8080;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        ApiServer server = new ApiServer(new PeopleGraph(), port);
        server.start();
        System.out.println("People & connections API listening on http://localhost:" + server.port());
    }

    /** Port from first CLI argument, then PORT env var, then 8080. */
    private static int resolvePort(String[] args) {
        String raw = args.length > 0 ? args[0] : System.getenv("PORT");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + raw);
        }
    }
}
