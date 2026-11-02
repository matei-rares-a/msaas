package com.saas.org.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The local {@link ObjectStorage} (REQ-PERS-03): round trip, replacement, and no way out of its directory. */
@Tag("REQ-PERS-03")
class LocalObjectStorageTest {

    @TempDir
    Path root;

    private ObjectStorage storage() {
        return new LocalObjectStorage(root.toString());
    }

    @Test
    void whatIsStoredCanBeReadBackAndReplaced() {
        String key = "tenants/" + UUID.randomUUID() + "/logo-abc123.png";

        storage().put(key, new byte[] {1, 2, 3});
        assertThat(storage().get(key)).contains(new byte[] {1, 2, 3});

        storage().put(key, new byte[] {9});
        assertThat(storage().get(key)).contains(new byte[] {9});
    }

    @Test
    void aMissingObjectIsEmptyAndDeletingItIsHarmless() {
        String key = "tenants/" + UUID.randomUUID() + "/logo-none.png";

        assertThat(storage().get(key)).isEmpty();
        storage().delete(key);
    }

    @Test
    void deletingRemovesTheObjectAndLeavesNoTemporaryFileBehind() throws IOException {
        String key = "tenants/" + UUID.randomUUID() + "/logo-abc123.jpg";
        storage().put(key, new byte[] {1});

        storage().delete(key);

        assertThat(storage().get(key)).isEmpty();
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @ParameterizedTest(name = "key {0} is refused")
    @ValueSource(strings = {"../outside.png", "tenants/../../outside.png", "/etc/passwd", "C:/Windows/win.ini",
            "tenants/x/..%2f..%2fy.png", "tenants\\x\\logo.png", "tenants/x/logo.png/../../../y.png",
            "tenants/x/logo", "tenants/x/LOGO.PNG", "tenants/x/logo.p ng", "", "tenants//logo.png"})
    void aKeyThatCouldLeaveTheRootDirectoryIsRefusedForEveryOperation(String key) {
        ObjectStorage storage = storage();

        assertThatThrownBy(() -> storage.put(key, new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullKeyIsRefused() {
        assertThatThrownBy(() -> storage().get(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingIsWrittenOutsideTheRootEvenWhenARefusedKeyIsAttempted() throws IOException {
        Path outside = root.resolveSibling("escaped-" + UUID.randomUUID() + ".png");

        assertThatThrownBy(() -> storage().put("../" + outside.getFileName(), new byte[] {1})).isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.exists(outside)).isFalse();
    }
}
