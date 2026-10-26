package com.fincore.notification.application;

/** Delivery to the (simulated) channel failed. Retryable; after the retry budget the event goes to the DLT. */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }
}
