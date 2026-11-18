package com.spindletrack.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LocalFileStorageService implements FileStorageService {
    private static final long MAX_FILE_SIZE_BYTES = ShopDocumentValidator.MAX_FILE_SIZE_BYTES;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".png", ".pdf");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final String configuredPath;

    public LocalFileStorageService(@Value("${spindletrack.files.storage-path:}") String configuredPath) {
        this.configuredPath = configuredPath;
    }

    @Override
    public String store(InputStream content, String extension) throws IOException {
        if (content == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IOException("A validated document stream and supported extension are required.");
        }
        Path root = storageRoot();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("The private document storage folder cannot be a symbolic link.");
        }
        restrictPermissions(root, PRIVATE_DIRECTORY_PERMISSIONS);

        String filename = UUID.randomUUID() + extension;
        Path destination = root.resolve(filename).normalize();
        if (!destination.getParent().equals(root)) {
            throw new IOException("Invalid private document filename.");
        }
        try (OutputStream output = Files.newOutputStream(
                destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            long copied = 0;
            int length;
            while ((length = content.read(buffer)) != -1) {
                copied += length;
                if (copied > MAX_FILE_SIZE_BYTES) {
                    throw new IOException("Document exceeds the maximum allowed size.");
                }
                output.write(buffer, 0, length);
            }
            if (copied == 0) {
                throw new IOException("Document content is empty.");
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
        try {
            restrictPermissions(destination, PRIVATE_FILE_PERMISSIONS);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
        return filename;
    }

    @Override
    public InputStream open(String storedFilename) throws IOException {
        Path file = resolveStoredFilename(storedFilename);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Document file was not found.");
        }
        return Files.newInputStream(file, StandardOpenOption.READ);
    }

    @Override
    public void delete(String storedFilename) throws IOException {
        Files.deleteIfExists(resolveStoredFilename(storedFilename));
    }

    private Path resolveStoredFilename(String storedFilename) throws IOException {
        if (!isStorageFilename(storedFilename)) {
            throw new IOException("Invalid private document filename.");
        }
        Path root = storageRoot();
        Path file = root.resolve(storedFilename).normalize();
        if (!file.getParent().equals(root) || Files.isSymbolicLink(file)) {
            throw new IOException("Invalid private document filename.");
        }
        return file;
    }

    private Path storageRoot() throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new FileStorageConfigurationException("FILE_STORAGE_PATH is not configured.");
        }
        Path root;
        try {
            root = Path.of(configuredPath).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new FileStorageConfigurationException("FILE_STORAGE_PATH is invalid.");
        }
        if (!Path.of(configuredPath).isAbsolute()) {
            throw new FileStorageConfigurationException("FILE_STORAGE_PATH must be an absolute path outside the repository.");
        }
        Path repositoryRoot = findRepositoryRoot(Path.of(System.getProperty("user.dir")));
        if (root.startsWith(repositoryRoot)) {
            throw new FileStorageConfigurationException("FILE_STORAGE_PATH must point outside the repository.");
        }
        return root;
    }

    private static Path findRepositoryRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        return start.toAbsolutePath().normalize();
    }

    private static boolean isStorageFilename(String filename) {
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || !ALLOWED_EXTENSIONS.contains(filename.substring(dot))) {
            return false;
        }
        try {
            UUID.fromString(filename.substring(0, dot));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        FileStore store = Files.getFileStore(path);
        if (store.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
