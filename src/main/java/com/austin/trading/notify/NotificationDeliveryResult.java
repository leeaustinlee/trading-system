package com.austin.trading.notify;

/**
 * Provider delivery truth for notification_log.
 *
 * <p>This is intentionally lightweight and side-effect free. Senders return this instead of throwing
 * so notification delivery failures remain observable without impacting trading workflows.</p>
 */
public record NotificationDeliveryResult(
        String provider,
        boolean attempted,
        boolean delivered,
        String status,
        Integer httpStatus,
        String providerMessageId,
        String errorCode,
        String errorBody,
        Integer retryCount
) {
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_ATTEMPTED = "ATTEMPTED";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";

    public NotificationDeliveryResult {
        retryCount = retryCount == null ? 0 : Math.max(0, retryCount);
    }

    public static NotificationDeliveryResult created(String provider) {
        return new NotificationDeliveryResult(provider, false, false, STATUS_CREATED,
                null, null, null, null, 0);
    }

    public static NotificationDeliveryResult delivered(String provider, Integer httpStatus,
                                                       String providerMessageId, Integer retryCount) {
        return new NotificationDeliveryResult(provider, true, true, STATUS_DELIVERED,
                httpStatus, providerMessageId, null, null, retryCount);
    }

    public static NotificationDeliveryResult skipped(String provider, String errorCode, String errorBody) {
        return new NotificationDeliveryResult(provider, false, false, STATUS_SKIPPED,
                null, null, errorCode, errorBody, 0);
    }

    public static NotificationDeliveryResult failed(String provider, Integer httpStatus,
                                                    String errorCode, String errorBody,
                                                    Integer retryCount) {
        return new NotificationDeliveryResult(provider, true, false, STATUS_FAILED,
                httpStatus, null, errorCode, errorBody, retryCount);
    }
}
