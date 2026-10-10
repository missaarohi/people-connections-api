package com.nada.connections.service;

/** Input is well-formed enough to read but breaks a business rule (maps to HTTP 400). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
