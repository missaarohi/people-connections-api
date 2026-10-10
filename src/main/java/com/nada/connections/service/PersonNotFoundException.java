package com.nada.connections.service;

/** No person exists with the given id (maps to HTTP 404). */
public class PersonNotFoundException extends RuntimeException {
    public PersonNotFoundException(long id) {
        super("no person " + id);
    }
}
