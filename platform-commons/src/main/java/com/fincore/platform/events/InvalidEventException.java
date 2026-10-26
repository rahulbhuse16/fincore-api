package com.fincore.platform.events;

/** A message that can never be processed (malformed JSON, missing fields). Never retried: goes straight to the DLT. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidEventException(String message) {
        super(message);
    }
}
