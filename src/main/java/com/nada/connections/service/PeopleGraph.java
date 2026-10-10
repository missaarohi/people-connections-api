package com.nada.connections.service;

import com.nada.connections.model.Person;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-memory store of people and their mutual connections.
 *
 * <p>All public methods are synchronized: the HTTP server is multi-threaded, and a single
 * coarse lock is the simplest thing that is obviously correct for an in-memory take-home.
 */
public final class PeopleGraph {

    /** Longest path (in hops) we are willing to report. */
    public static final int MAX_HOPS = 3;

    private final Map<Long, Person> people = new HashMap<>();
    // TreeSet keeps contacts sorted by id, so responses and BFS are deterministic.
    private final Map<Long, Set<Long>> contacts = new HashMap<>();
    private long nextId = 1;

    public synchronized Person addPerson(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("name must not be blank");
        }
        Person person = new Person(nextId++, name);
        people.put(person.id(), person);
        contacts.put(person.id(), new TreeSet<>());
        return person;
    }

    /** Creates a mutual connection. Repeating an existing connection is a no-op. */
    public synchronized void connect(long a, long b) {
        if (a == b) {
            throw new InvalidRequestException("a and b must be different people");
        }
        requirePerson(a);
        requirePerson(b);
        contacts.get(a).add(b);
        contacts.get(b).add(a);
    }

    public synchronized List<Person> contactsOf(long id) {
        requirePerson(id);
        List<Person> result = new ArrayList<>();
        for (long contactId : contacts.get(id)) {
            result.add(people.get(contactId));
        }
        return result;
    }

    /**
     * Shortest path from {@code from} to {@code to} using at most {@link #MAX_HOPS} hops.
     * Returns the people along the path (both ends included), or empty if none qualifies.
     */
    public synchronized Optional<List<Person>> shortestPath(long from, long to) {
        Person start = requirePerson(from);
        requirePerson(to);
        if (from == to) {
            return Optional.of(List.of(start));
        }

        // Breadth-first search, one level per iteration, stopping after MAX_HOPS levels.
        Map<Long, Long> parent = new HashMap<>();
        parent.put(from, null);
        List<Long> frontier = List.of(from);

        for (int hops = 1; hops <= MAX_HOPS && !frontier.isEmpty(); hops++) {
            List<Long> next = new ArrayList<>();
            for (long current : frontier) {
                for (long neighbour : contacts.get(current)) {
                    if (parent.containsKey(neighbour)) {
                        continue;
                    }
                    parent.put(neighbour, current);
                    if (neighbour == to) {
                        return Optional.of(buildPath(parent, to));
                    }
                    next.add(neighbour);
                }
            }
            frontier = next;
        }
        return Optional.empty();
    }

    private List<Person> buildPath(Map<Long, Long> parent, long end) {
        List<Person> path = new ArrayList<>();
        for (Long id = end; id != null; id = parent.get(id)) {
            path.add(people.get(id));
        }
        Collections.reverse(path);
        return path;
    }

    private Person requirePerson(long id) {
        Person person = people.get(id);
        if (person == null) {
            throw new PersonNotFoundException(id);
        }
        return person;
    }
}
