package io.openems.backend.metadata.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PasswordHashTest {

	private static final int ITERATIONS = PasswordHash.MIN_ITERATIONS;

	@Test
	public void correctPasswordVerifies() {
		var salt = PasswordHash.newSalt();
		var hash = PasswordHash.hash("correct horse battery", salt, ITERATIONS);
		assertTrue(PasswordHash.verify("correct horse battery", salt, ITERATIONS, hash));
	}

	@Test
	public void wrongPasswordIsRejected() {
		var salt = PasswordHash.newSalt();
		var hash = PasswordHash.hash("correct horse battery", salt, ITERATIONS);
		assertFalse(PasswordHash.verify("correct horse batterz", salt, ITERATIONS, hash));
		assertFalse(PasswordHash.verify("", salt, ITERATIONS, hash));
	}

	@Test
	public void saltsDiffer() {
		assertNotEquals(PasswordHash.newSalt(), PasswordHash.newSalt());
		assertEquals(24, PasswordHash.newSalt().length());
	}

	@Test
	public void placeholderAndWeakEntriesNeverVerify() {
		assertFalse(PasswordHash.verify("x", "REPLACE_ME_USE_GenerateUserHash", ITERATIONS,
				"REPLACE_ME_USE_GenerateUserHash"));
		var salt = PasswordHash.newSalt();
		var weak = PasswordHash.hash("pw", salt, 1000);
		assertFalse(PasswordHash.verify("pw", salt, 1000, weak));
		assertFalse(PasswordHash.verify(null, salt, ITERATIONS, weak));
		assertFalse(PasswordHash.verify("pw", null, ITERATIONS, weak));
		assertFalse(PasswordHash.verify("pw", salt, ITERATIONS, null));
	}
}
