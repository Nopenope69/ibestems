package io.openems.backend.metadata.dummy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256 password hashing, using only the JDK (no external
 * dependency, so this bundle does not need a new bnd/gradle dependency).
 *
 * <p>
 * Salt and derived key are both 256 bit. The iteration count is stored
 * alongside the hash so it can be raised later without invalidating
 * passwords hashed with a lower count.
 */
public final class PasswordHash {

	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int KEY_LENGTH_BITS = 256;
	private static final int SALT_LENGTH_BYTES = 16;

	/** Default iteration count for newly hashed passwords (OWASP 2023 guidance for PBKDF2-SHA256). */
	public static final int DEFAULT_ITERATIONS = 210_000;

	private PasswordHash() {
	}

	/**
	 * Generates a new random salt.
	 *
	 * @return the salt, base64-encoded
	 */
	public static String newSalt() {
		var salt = new byte[SALT_LENGTH_BYTES];
		new SecureRandom().nextBytes(salt);
		return Base64.getEncoder().encodeToString(salt);
	}

	/**
	 * Hashes a password with the given base64 salt and iteration count.
	 *
	 * @param password   the plaintext password
	 * @param saltBase64 the salt, base64-encoded
	 * @param iterations the PBKDF2 iteration count
	 * @return the derived hash, base64-encoded
	 */
	public static String hash(String password, String saltBase64, int iterations) {
		try {
			var salt = Base64.getDecoder().decode(saltBase64);
			var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
			var factory = SecretKeyFactory.getInstance(ALGORITHM);
			var derived = factory.generateSecret(spec).getEncoded();
			return Base64.getEncoder().encodeToString(derived);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			// PBKDF2WithHmacSHA256 is a standard JDK algorithm; this cannot happen at runtime.
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Verifies a password against a stored salt/hash/iterations triple, in
	 * constant time.
	 *
	 * @param password       the plaintext password to check
	 * @param saltBase64     the stored salt, base64-encoded
	 * @param iterations     the stored PBKDF2 iteration count
	 * @param expectedBase64 the stored derived hash, base64-encoded
	 * @return true if the password is correct
	 */
	public static boolean verify(String password, String saltBase64, int iterations, String expectedBase64) {
		var actual = PasswordHash.hash(password, saltBase64, iterations);
		// MessageDigest.isEqual() is specified to run in constant time when both
		// inputs are the same length, avoiding a timing side-channel on comparison.
		return MessageDigest.isEqual(//
				actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII), //
				expectedBase64.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}
}
