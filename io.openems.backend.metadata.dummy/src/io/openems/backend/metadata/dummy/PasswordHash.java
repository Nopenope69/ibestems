package io.openems.backend.metadata.dummy;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256 password hashing using only the JDK, so the bundle needs
 * no new dependency.
 *
 * <p>
 * Salt is 128 bit, the derived key 256 bit. The iteration count is stored
 * next to each hash, so it can be raised later without invalidating existing
 * entries.
 */
public final class PasswordHash {

	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int KEY_LENGTH_BITS = 256;
	private static final int SALT_LENGTH_BYTES = 16;

	/** Iteration count for new hashes (OWASP 2023 guidance for PBKDF2-SHA256). */
	public static final int DEFAULT_ITERATIONS = 210_000;

	/** Lowest iteration count a stored entry may use; weaker entries never verify. */
	public static final int MIN_ITERATIONS = 100_000;

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
	 * Hashes a password with the given salt and iteration count.
	 *
	 * @param password   the plaintext password
	 * @param saltBase64 the salt, base64-encoded
	 * @param iterations the PBKDF2 iteration count
	 * @return the derived key, base64-encoded
	 * @throws IllegalArgumentException if the salt is not valid base64
	 */
	public static String hash(String password, String saltBase64, int iterations) {
		try {
			var salt = Base64.getDecoder().decode(saltBase64);
			var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
			try {
				var factory = SecretKeyFactory.getInstance(ALGORITHM);
				return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
			} finally {
				spec.clearPassword();
			}
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			// PBKDF2WithHmacSHA256 is a standard JDK algorithm
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Verifies a password against a stored salt, iteration count and hash. The
	 * final comparison runs in constant time. Malformed or weak entries return
	 * false instead of throwing.
	 *
	 * @param password       the plaintext password to check
	 * @param saltBase64     the stored salt, base64-encoded
	 * @param iterations     the stored iteration count
	 * @param expectedBase64 the stored hash, base64-encoded
	 * @return true if the password matches
	 */
	public static boolean verify(String password, String saltBase64, int iterations, String expectedBase64) {
		if (password == null || saltBase64 == null || expectedBase64 == null || iterations < MIN_ITERATIONS) {
			return false;
		}
		final String actual;
		try {
			actual = hash(password, saltBase64, iterations);
		} catch (IllegalArgumentException e) {
			return false;
		}
		return MessageDigest.isEqual(actual.getBytes(US_ASCII), expectedBase64.getBytes(US_ASCII));
	}
}
