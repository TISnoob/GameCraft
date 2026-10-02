package io.github.tis199.gamecraft.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable identity and compatibility declaration for a game module. */
public record GameModuleDescriptor(
        String id,
        String displayName,
        String version,
        int apiVersion,
        String description) {

    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public GameModuleDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        if (!VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Module id must match " + VALID_ID.pattern());
        }
        if (displayName.isBlank() || version.isBlank() || apiVersion < 1) {
            throw new IllegalArgumentException("Module metadata is incomplete");
        }
    }
}
