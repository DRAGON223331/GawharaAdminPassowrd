package net.gawhara.lock;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** تشفير الباسورد بـ PBKDF2 (مفيش تخزين للباسورد نفسه). */
final class Hasher {

    private static final int ITERATIONS = 50_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Hasher() {
    }

    static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    static String hash(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 مش متاح", e);
        }
    }

    static boolean matches(String password, byte[] salt, String expectedBase64) {
        byte[] a = hash(password, salt).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = expectedBase64.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b); // مقارنة ثابتة الزمن
    }
}
