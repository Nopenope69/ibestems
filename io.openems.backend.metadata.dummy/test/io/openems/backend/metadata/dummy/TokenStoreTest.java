package io.openems.backend.metadata.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

public class TokenStoreTest {

	private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - 1_000); // also exercises wrap-around
	private final TokenStore store = new TokenStore(Duration.ofSeconds(60), this.now::get);

	@Test
	public void issuedTokenResolvesToItsUser() {
		var token = this.store.issue("alice");
		assertEquals(43, token.length());
		assertEquals(Optional.of("alice"), this.store.lookup(token));
		assertNotEquals(token, this.store.issue("alice"));
	}

	@Test
	public void unknownAndNullTokensAreRejected() {
		this.store.issue("alice");
		assertTrue(this.store.lookup("not-a-token").isEmpty());
		assertTrue(this.store.lookup(null).isEmpty());
	}

	@Test
	public void tokenExpires() {
		var token = this.store.issue("alice");
		this.now.addAndGet(Duration.ofSeconds(59).toNanos());
		assertEquals(Optional.of("alice"), this.store.lookup(token));
		this.now.addAndGet(Duration.ofSeconds(1).toNanos());
		assertTrue(this.store.lookup(token).isEmpty());
		assertEquals(0, this.store.size());
	}

	@Test
	public void revokedTokenIsRejected() {
		var a = this.store.issue("alice");
		final var b = this.store.issue("bob");
		this.store.revoke(a);
		this.store.revoke(null);
		assertTrue(this.store.lookup(a).isEmpty());
		assertEquals(Optional.of("bob"), this.store.lookup(b));
	}
}
