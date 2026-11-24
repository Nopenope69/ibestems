package io.openems.backend.metadata.dummy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * In-memory login tokens for Metadata.Dummy. A token is 256 random bits,
 * valid until logout, until its lifetime runs out, or until the Backend
 * restarts.
 */
final class TokenStore {

	private record Session(String username, long expiresAtNanos) {
	}

	private final SecureRandom random = new SecureRandom();
	private final Map<String, Session> sessions = new HashMap<>();
	private final long ttlNanos;
	private final LongSupplier nanoClock;

	TokenStore(Duration ttl, LongSupplier nanoClock) {
		this.ttlNanos = ttl.toNanos();
		this.nanoClock = nanoClock;
	}

	/**
	 * Issues a new token for a user.
	 *
	 * @param username the authenticated username
	 * @return the token
	 */
	synchronized String issue(String username) {
		this.purgeExpired();
		var bytes = new byte[32];
		this.random.nextBytes(bytes);
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		this.sessions.put(token, new Session(username, this.nanoClock.getAsLong() + this.ttlNanos));
		return token;
	}

	/**
	 * Looks up the user behind a token.
	 *
	 * @param token the token
	 * @return the username, or empty if the token is unknown or expired
	 */
	synchronized Optional<String> lookup(String token) {
		if (token == null) {
			return Optional.empty();
		}
		var session = this.sessions.get(token);
		if (session == null) {
			return Optional.empty();
		}
		if (this.nanoClock.getAsLong() - session.expiresAtNanos() >= 0) {
			this.sessions.remove(token);
			return Optional.empty();
		}
		return Optional.of(session.username());
	}

	/**
	 * Revokes one token.
	 *
	 * @param token the token
	 */
	synchronized void revoke(String token) {
		if (token != null) {
			this.sessions.remove(token);
		}
	}

	/**
	 * Gets the number of live sessions, for tests and debug logging.
	 *
	 * @return the count
	 */
	synchronized int size() {
		this.purgeExpired();
		return this.sessions.size();
	}

	private void purgeExpired() {
		var now = this.nanoClock.getAsLong();
		this.sessions.values().removeIf(s -> now - s.expiresAtNanos() >= 0);
	}
}
