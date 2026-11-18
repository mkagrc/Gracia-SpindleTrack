package com.spindletrack.auth;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorageService {
    String store(InputStream content, String extension) throws IOException;

    InputStream open(String storedFilename) throws IOException;

    void delete(String storedFilename) throws IOException;
}
