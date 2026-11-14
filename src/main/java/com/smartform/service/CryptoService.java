package com.smartform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-256-GCM encryption. The key is derived from the user's passphrase with PBKDF2. */
@Service
public class CryptoService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int ITERATIONS = 210_000;
    private static final byte[] VERIFIER = "SMARTFORM-OK".getBytes(StandardCharsets.UTF_8);

    private final Path saltFile;
    private final Path verifierFile;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public CryptoService(@Value("${smartform.data-dir}") String dataDir) throws IOException {
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);
        saltFile = dir.resolve("salt.bin");
        verifierFile = dir.resolve("verifier.bin");
    }

    public boolean isInitialized() {
        return Files.exists(saltFile) && Files.exists(verifierFile);
    }

    /** First call creates the key material; later calls verify the passphrase. */
    public void unlock(char[] passphrase) {
        try {
            boolean existing = isInitialized();
            byte[] salt;
            if (existing) {
                salt = Files.readAllBytes(saltFile);
            } else {
                salt = new byte[16];
                random.nextBytes(salt);
            }
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] raw = factory.generateSecret(new PBEKeySpec(passphrase, salt, ITERATIONS, 256)).getEncoded();
            SecretKeySpec candidate = new SecretKeySpec(raw, "AES");
            Arrays.fill(passphrase, '\0');
            if (existing) {
                try {
                    decryptWith(candidate, Files.readAllBytes(verifierFile));
                } catch (GeneralSecurityException e) {
                    throw new IllegalArgumentException("Wrong passphrase.");
                }
                key = candidate;
            } else {
                key = candidate;
                Files.write(saltFile, salt);
                Files.write(verifierFile, encrypt(VERIFIER));
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialise encryption: " + e.getMessage(), e);
        }
    }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        try {
            return decryptWith(requireKey(), data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or corrupted data)", e);
        }
    }

    public byte[] encryptString(String s) {
        return encrypt(s.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptString(byte[] data) {
        return new String(decrypt(data), StandardCharsets.UTF_8);
    }

    private static byte[] decryptWith(SecretKeySpec k, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
        return cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException("SmartForm is locked.");
        }
        return key;
    }
}
