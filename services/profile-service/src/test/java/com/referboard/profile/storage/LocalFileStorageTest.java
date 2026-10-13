package com.referboard.profile.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    @Test
    void roundTripsAndDeletes(@TempDir Path dir) {
        var storage = new LocalFileStorage(dir);
        storage.put("users/u1/resumes/r1.pdf", new byte[]{1, 2, 3}, "application/pdf");

        assertThat(storage.get("users/u1/resumes/r1.pdf")).containsExactly(1, 2, 3);
        storage.delete("users/u1/resumes/r1.pdf");
        assertThat(Files.exists(dir.resolve("users/u1/resumes/r1.pdf"))).isFalse();
    }

    @Test
    void refusesPathTraversal(@TempDir Path dir) {
        var storage = new LocalFileStorage(dir);
        assertThatThrownBy(() -> storage.get("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put("/abs/path", new byte[0], "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
