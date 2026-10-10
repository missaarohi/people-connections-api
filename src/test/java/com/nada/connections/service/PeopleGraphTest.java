package com.nada.connections.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nada.connections.model.Person;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PeopleGraphTest {

    private final PeopleGraph graph = new PeopleGraph();

    private long person(String name) {
        return graph.addPerson(name).id();
    }

    private void chain(long... ids) {
        for (int i = 0; i + 1 < ids.length; i++) {
            graph.connect(ids[i], ids[i + 1]);
        }
    }

    private static List<Long> ids(List<Person> path) {
        return path.stream().map(Person::id).toList();
    }

    // ---- required: both sides of the three-hop limit -------------------------------------

    @Test
    void chainOfThreeHopsIsConnected() {
        long a = person("A"), b = person("B"), c = person("C"), d = person("D");
        chain(a, b, c, d);

        Optional<List<Person>> path = graph.shortestPath(a, d);

        assertTrue(path.isPresent());
        assertEquals(3, path.get().size() - 1);
        assertEquals(List.of(a, b, c, d), ids(path.get()));
    }

    @Test
    void chainOfFourHopsIsNotConnected() {
        long a = person("A"), b = person("B"), c = person("C"), d = person("D"), e = person("E");
        chain(a, b, c, d, e);

        assertTrue(graph.shortestPath(a, e).isEmpty());
    }

    // ---- other path behaviour --------------------------------------------------------------

    @Test
    void samePersonIsZeroHopsWithSingleEntryPath() {
        long a = person("A");

        Optional<List<Person>> path = graph.shortestPath(a, a);

        assertTrue(path.isPresent());
        assertEquals(List.of(a), ids(path.get()));
    }

    @Test
    void prefersTheShortcutOverALongerRoute() {
        long a = person("A"), b = person("B"), c = person("C"), d = person("D");
        chain(a, b, c, d);
        graph.connect(a, d);

        assertEquals(List.of(a, d), ids(graph.shortestPath(a, d).orElseThrow()));
    }

    @Test
    void pathWorksInBothDirections() {
        long a = person("A"), b = person("B"), c = person("C");
        chain(a, b, c);

        assertEquals(List.of(c, b, a), ids(graph.shortestPath(c, a).orElseThrow()));
    }

    @Test
    void cyclesDoNotBreakTheSearch() {
        long a = person("A"), b = person("B"), c = person("C"), d = person("D");
        chain(a, b, c);
        graph.connect(c, a);

        assertTrue(graph.shortestPath(a, d).isEmpty());
    }

    @Test
    void disconnectedPeopleAreNotConnected() {
        long a = person("A"), b = person("B");

        assertTrue(graph.shortestPath(a, b).isEmpty());
    }

    // ---- people and connections ------------------------------------------------------------

    @Test
    void idsAreAssignedSequentiallyFromOne() {
        assertEquals(1, person("Asha"));
        assertEquals(2, person("Rohan"));
    }

    @Test
    void connectingTwiceDoesNotDuplicateContacts() {
        long a = person("A"), b = person("B");
        graph.connect(a, b);
        graph.connect(a, b);
        graph.connect(b, a);

        assertEquals(1, graph.contactsOf(a).size());
        assertEquals(1, graph.contactsOf(b).size());
    }

    @Test
    void connectionIsMutualAndContactsAreSortedById() {
        long a = person("A"), b = person("B"), c = person("C");
        graph.connect(a, c);
        graph.connect(a, b);

        assertEquals(List.of(b, c), ids(graph.contactsOf(a)));
        assertEquals(List.of(a), ids(graph.contactsOf(b)));
    }

    @Test
    void personWithNoContactsHasEmptyList() {
        assertTrue(graph.contactsOf(person("Lonely")).isEmpty());
    }

    // ---- validation ------------------------------------------------------------------------

    @Test
    void rejectsBlankOrNullNames() {
        assertThrows(InvalidRequestException.class, () -> graph.addPerson(""));
        assertThrows(InvalidRequestException.class, () -> graph.addPerson("   "));
        assertThrows(InvalidRequestException.class, () -> graph.addPerson(null));
    }

    @Test
    void namesAreStoredExactlyAsSupplied() {
        assertEquals("  आशा शर्मा ", graph.addPerson("  आशा शर्मा ").name());
    }

    @Test
    void rejectsConnectingAPersonToThemselves() {
        long a = person("A");
        assertThrows(InvalidRequestException.class, () -> graph.connect(a, a));
    }

    @Test
    void unknownPeopleAreReportedAsNotFound() {
        long a = person("A");
        PersonNotFoundException e = assertThrows(PersonNotFoundException.class, () -> graph.connect(a, 9));
        assertEquals("no person 9", e.getMessage());
        assertThrows(PersonNotFoundException.class, () -> graph.contactsOf(9));
        assertThrows(PersonNotFoundException.class, () -> graph.shortestPath(a, 9));
        assertThrows(PersonNotFoundException.class, () -> graph.shortestPath(9, a));
    }
}
