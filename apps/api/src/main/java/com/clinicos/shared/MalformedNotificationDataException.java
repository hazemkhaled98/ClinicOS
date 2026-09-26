package com.clinicos.shared;

public final class MalformedNotificationDataException extends RuntimeException {

    public MalformedNotificationDataException(String message) {
        super(message);
    }

    public MalformedNotificationDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
