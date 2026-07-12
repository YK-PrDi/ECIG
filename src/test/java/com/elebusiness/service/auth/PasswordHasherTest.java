package com.elebusiness.service.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void hashesPasswordWithSaltAndVerifiesIt() {
        PasswordHasher hasher = new PasswordHasher();

        String hash1 = hasher.hash("secret-password");
        String hash2 = hasher.hash("secret-password");

        assertNotEquals(hash1, hash2);
        assertTrue(hasher.verify("secret-password", hash1));
        assertTrue(hasher.verify("secret-password", hash2));
        assertFalse(hasher.verify("wrong-password", hash1));
    }
}
