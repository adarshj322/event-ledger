package com.mphasis.eventledger.exception;

/**
 * Thrown when an event with the requested ID does not exist.
 */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("Event not found with id: " + eventId);
    }
}
