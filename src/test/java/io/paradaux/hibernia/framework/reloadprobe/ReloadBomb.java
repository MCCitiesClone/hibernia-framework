package io.paradaux.hibernia.framework.reloadprobe;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;

/**
 * Test-only {@code @ConfigurationComponent} in an isolated package so the broad configurator-package
 * scans don't pick it up. Its no-arg constructor throws when {@link #armed} is set, letting a test load
 * it once and then force a reload failure.
 */
@ConfigurationComponent
public class ReloadBomb {

    public static volatile boolean armed = false;

    @ConfigurationValue(path = "reloadprobe.value", defaultValue = "x")
    String value;

    public ReloadBomb() {
        if (armed) {
            throw new IllegalStateException("reload boom");
        }
    }
}
