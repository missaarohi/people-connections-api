# People & Connections API

A small in-memory HTTP API that stores people and their direct contacts, and answers how two people are connected (shortest path, three hops or fewer).

Built with Core Java 17+, the JDK's built-in `com.sun.net.httpserver`, and Gson for JSON. No database; all data lives in memory and is lost on restart.

## Requirements

- JDK 17 or newer
- Maven 3.8+

## Run the server

```bash
mvn -q compile exec:java
```

The server listens on **port 8080** by default. To use another port, set the `PORT` environment variable or pass it as an argument:

```bash
PORT=9090 mvn -q compile exec:java
mvn -q compile exec:java -Dexec.args=9090
```

## Run the tests

```bash
mvn test
```

This runs unit tests for the graph logic and end-to-end tests that start a real server on a free port. The three-hop limit is covered in both layers: a chain A-B-C-D gives A to D connected in 3 hops, and a chain A-B-C-D-E gives A to E not connected (`PeopleGraphTest` and `ApiServerTest`).

## API

| Method | Path | Success | Notes |
|---|---|---|---|
| POST | `/people` | 201 `{"id":1,"name":"Asha"}` | Body `{"name": "..."}`; ID assigned by the server, starting at 1 |
| POST | `/knows` | 204 | Body `{"a":1,"b":2}`; mutual; repeating a connection is not an error |
| GET | `/people/{id}/contacts` | 200 list | Sorted by ID; empty list if none |
| GET | `/path?from=1&to=5` | 200 | `{"connected":true,"hops":2,"path":[...]}` or `{"connected":false}` |

Errors are JSON, e.g. `{"error": "no person 9"}`:

- **400**: invalid JSON, missing or wrongly typed field, blank name, `a == b`, missing or malformed `from`/`to` or `{id}`
- **404**: unknown person, unknown route
- **405**: wrong HTTP method for a known route
- **413**: request body larger than 64 KB

Names are accepted in any script and returned exactly as supplied (no trimming or normalisation).

Example:

```bash
curl -X POST localhost:8080/people -d '{"name":"आशा"}'
curl -X POST localhost:8080/people -d '{"name":"Rohan"}'
curl -X POST localhost:8080/knows  -d '{"a":1,"b":2}'
curl 'localhost:8080/path?from=1&to=2'
```

## Design

- `model/Person`: immutable record.
- `service/PeopleGraph`: the in-memory store and all business rules. Adjacency is `Map<Long, Set<Long>>` (a sorted set, so duplicate connections are naturally idempotent and output is deterministic). Shortest path is a breadth-first search that stops after 3 levels and rebuilds the path from a parent map.
- `http/ApiServer`: routing, strict JSON/UTF-8 parsing, and mapping exceptions to status codes. It contains no business logic.
- Concurrency: the server is multi-threaded, so `PeopleGraph` methods are `synchronized`. One coarse lock is simple and correct at this scale.

## What I would do next with more time

- Replace the coarse lock with a read/write lock (searches are read-only), and measure before going further.
- Persistence (a database or snapshotting), since memory is lost on restart.
- Pagination for contacts, and body/rate limits beyond the current 64 KB cap.
- Make the hop limit configurable via a query parameter, and return several equally short paths if wanted.
- Endpoints to rename or delete people and remove connections.
- Structured request logging, a health endpoint, and a Dockerfile.
- If the graph gets large or queries get more complex (more hops, recommendations), revisit the data store; a graph database may be justified then, but not for this scope.

## AI tools used

- Claude (Anthropic) was used to plan the design, draft the initial implementation and tests, and review edge cases. I reviewed and ran all of the code and tests myself.
