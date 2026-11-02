package com.saas.org.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * File-system {@link ObjectStorage} for development and single-node deployments (REQ-PERS-03). A key is
 * only ever turned into a path after it matches a strict pattern and resolves inside the root directory,
 * so a hostile key such as {@code ../../etc/passwd} can neither read nor write outside it. Nothing
 * outside this package refers to this class; callers hold an {@link ObjectStorage}.
 */
@Component
public class LocalObjectStorage implements ObjectStorage {

    // tenants/<uuid>/<file>: lower-case letters, digits, '-', '_' and a single extension; no dots in directories
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-z0-9-]+(/[a-z0-9-]+)*/[a-z0-9_-]+\\.[a-z0-9]{1,5}$");

    private final Path root;

    public LocalObjectStorage(@Value("${saas.storage.local.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // write to a sibling then move, so a reader never sees a half-written file
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not store " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path target = resolve(key);
        try {
            return Files.isRegularFile(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete " + key, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid storage key");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("invalid storage key");
        }
        return resolved;
    }
}
