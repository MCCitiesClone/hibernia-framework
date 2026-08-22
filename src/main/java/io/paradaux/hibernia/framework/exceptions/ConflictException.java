package io.paradaux.hibernia.framework.exceptions;

import java.util.Map;

public class ConflictException extends RuntimeException implements KeyedException {

    private final transient Map<String, Object> placeholders;

    /** The message doubles as a {@code messages.properties} key (or plain text — see {@link KeyedException}). */
    public ConflictException(String messageKey) {
        super(messageKey);
        this.placeholders = Map.of();
    }

    public ConflictException(String messageKey, Throwable cause) {
        super(messageKey, cause);
        this.placeholders = Map.of();
    }

    /** As {@link #ConflictException(String)} with {@code key, value, …} placeholder pairs. */
    public ConflictException(String messageKey, Object... placeholders) {
        super(messageKey);
        this.placeholders = KeyedException.pairs(placeholders);
    }

    @Override
    public String messageKey() {
        return getMessage();
    }

    @Override
    public Map<String, ?> placeholders() {
        return placeholders;
    }
}
