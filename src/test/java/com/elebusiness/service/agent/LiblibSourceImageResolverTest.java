package com.elebusiness.service.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiblibSourceImageResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsPublicUrlAsSourceImage() throws Exception {
        assertEquals(
                "https://cdn.example.com/ref.png",
                LiblibSourceImageResolver.resolve("https://cdn.example.com/ref.png", null)
        );
    }

    @Test
    void rejectsLocalFileWhenCosIsUnavailable() throws Exception {
        Path image = tempDir.resolve("ref.png");
        Files.write(image, new byte[]{1, 2, 3});

        assertThrows(IOException.class, () -> LiblibSourceImageResolver.resolve(image.toString(), null));
    }
}