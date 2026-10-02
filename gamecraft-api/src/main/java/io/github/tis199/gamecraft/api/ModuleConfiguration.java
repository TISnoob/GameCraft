package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.Optional;

/** Read-only view of a module's generated and owner-editable YAML settings. */
public interface ModuleConfiguration {
    Optional<String> getString(String path);

    boolean getBoolean(String path, boolean defaultValue);

    int getInt(String path, int defaultValue);

    List<String> getStringList(String path);
}
