package com.sismics.docs.core.util;

import com.google.common.base.Strings;
import com.sismics.docs.core.model.context.AppContext;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;

/**
 * Encryption utilities.
 * 
 * @author bgamard
 */
public class EncryptionUtil {
    /**
     * Salt.
     */
    private static final String SALT = "LEpxZmm2SMu2PeKzPNrar2rhVAS6LrrgvXKeL9uyXC4vgKHg";
    public static String FileEncrypt = ConfigUtil.getConfigBundle().getString("file.encrypt");

    static {
        // Initialize Bouncy Castle provider
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        Security.removeProvider("SunRsaSign");
    }

    /**
     * Encrypt an OutputStream using the specified private key.
     *
     * @param os OutputStream to encrypt
     * @param privateKey Private key
     * @return Encrypted stream
     * @throws Exception  e
     */
    public static OutputStream encryptOutputStream(OutputStream os, String privateKey) throws Exception {
        if( FileEncrypt.equals("0"))
            return os;
        else
            return new CipherOutputStream(os, getCipher(privateKey, Cipher.ENCRYPT_MODE));
    }

    /**
     * Generate a private key.
     * 
     * @return New random private key
     */
    public static String generatePrivateKey() {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            return new BigInteger(176, random).toString(32);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Encrypt an InputStream using the specified private key.
     *
     * @param is InputStream to encrypt
     * @param privateKey Private key
     * @return Encrypted stream
     * @throws Exception  e
     */
    public static InputStream encryptInputStream(InputStream is, String privateKey) throws Exception {
        if( FileEncrypt.equals("0"))
            return is;
        else
            return new CipherInputStream(is, getCipher(privateKey, Cipher.ENCRYPT_MODE));
    }

    /**
     * Decrypt an InputStream using the specified private key.
     * 
     * @param is InputStream to encrypt
     * @param privateKey Private key
     * @return Encrypted stream
     * @throws Exception  e
     */
    public static InputStream decryptInputStream(InputStream is, String privateKey) throws Exception {
        if( FileEncrypt.equals("0"))
          return is;
        else
          return new CipherInputStream(is, getCipher(privateKey, Cipher.DECRYPT_MODE));
    }

    /**
     * Decrypt a file to a temporary file using the specified private key.
     *
     * @param file Encrypted file
     * @param privateKey Private key
     * @return Decrypted temporary file
     * @throws Exception e
     */
    public static Path decryptFile(Path file, String privateKey) throws Exception {
        if (privateKey == null || FileEncrypt.equals("0")) {
            // For unit testing
            return file;
        }

        Path tmpFile = AppContext.getInstance().getFileService().createTemporaryFile();
        try (InputStream is = Files.newInputStream(file)) {
            Files.copy(new CipherInputStream(is, getCipher(privateKey, Cipher.DECRYPT_MODE)), tmpFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmpFile;
    }

    /**
     * Return an encryption cipher.
     * 
     * @param privateKey Private key
     * @return Encryption cipher
     * @throws Exception e
     */
    private static Cipher getEncryptionCipher(String privateKey) throws Exception {
        if (Strings.isNullOrEmpty(privateKey)) {
            throw new IllegalArgumentException("The private key is null or empty");
        }
        return getCipher(privateKey, Cipher.ENCRYPT_MODE);
    }
    
    /**
     * Initialize a Cipher.
     * 
     * @param privateKey Private key
     * @param mode Mode (encrypt or decrypt)
     * @return Cipher
     * @throws Exception e
     */
    private static Cipher getCipher(String privateKey, int mode) throws Exception {
        PBEKeySpec keySpec = new PBEKeySpec(privateKey.toCharArray(), SALT.getBytes(), 2000, 256);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBEWITHSHA256AND256BITAES-CBC-BC");
        SecretKey desKey = skf.generateSecret(keySpec);
        Cipher cipher = Cipher.getInstance("AES/CTR/NOPADDING");
        cipher.init(mode, desKey);
        return cipher;
    }
}
