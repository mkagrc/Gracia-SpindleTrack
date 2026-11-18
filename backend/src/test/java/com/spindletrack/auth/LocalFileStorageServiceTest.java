package com.spindletrack.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesWithRandomUuidFilenameAndSupportsPrivateReadAndDelete() throws Exception {
        Path privateDirectory = temporaryDirectory.resolve("private-documents");
        LocalFileStorageService storage = new LocalFileStorageService(privateDirectory.toString());
        byte[] content = "%PDF-1.7 private".getBytes();

        String storedName = storage.store(new ByteArrayInputStream(content), ".pdf");

        assertTrue(storedName.matches("[0-9a-fA-F-]{36}\\.pdf"));
        assertTrue(Files.isRegularFile(privateDirectory.resolve(storedName)));
        try (var input = storage.open(storedName)) {
            assertArrayEquals(content, input.readAllBytes());
        }
        storage.delete(storedName);
        assertFalse(Files.exists(privateDirectory.resolve(storedName)));
    }

    @Test
    void rejectsStorageDirectoriesInsideTheRepository() {
        LocalFileStorageService storage = new LocalFileStorageService(System.getProperty("user.dir"));

        assertThrows(IOException.class, () -> storage.store(new ByteArrayInputStream(new byte[] {1}), ".pdf"));
    }

    @Test
    void rejectsUntrustedStoredNamesAndMissingConfiguration() {
        LocalFileStorageService storage = new LocalFileStorageService(temporaryDirectory.toString());
        assertThrows(IOException.class, () -> storage.open("..\\secret.json"));
        assertThrows(IOException.class, () -> storage.store(new ByteArrayInputStream(new byte[] {1}), ".exe"));

        LocalFileStorageService unconfigured = new LocalFileStorageService("");
        assertThrows(IOException.class,
                () -> unconfigured.store(new ByteArrayInputStream(new byte[] {1}), ".pdf"));
    }
}
