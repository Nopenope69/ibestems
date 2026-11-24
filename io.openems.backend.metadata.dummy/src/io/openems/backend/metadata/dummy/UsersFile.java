package io.openems.backend.metadata.dummy;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.session.Role;
import io.openems.common.utils.JsonUtils;

/**
 * The users file read by Metadata.Dummy: usernames, display names, roles and
 * PBKDF2 password hashes. See users.example.json for the format.
 *
 * <p>
 * The file is re-read whenever its modification time or size changes, so
 * adding, removing or re-roling a user is a file edit, not a Backend restart.
 * A missing, unreadable or malformed file yields no users, so every login
 * fails rather than falling back to any default identity.
 */
final class UsersFile {

	/**
	 * One user entry. Never holds a plaintext password.
	 *
	 * @param username   the login name
	 * @param name       the display name
	 * @param role       the global role
	 * @param salt       the base64 salt
	 * @param hash       the base64 PBKDF2 hash
	 * @param iterations the PBKDF2 iteration count
	 */
	record Entry(String username, String name, Role role, String salt, String hash, int iterations) {
	}

	/**
	 * Hook for log messages, so this class stays free of the OSGi logger.
	 */
	interface Warn {
		/**
		 * Logs a warning.
		 *
		 * @param message the message
		 */
		void warn(String message);
	}

	private final Path path;
	private final Warn warn;

	private long lastModified = Long.MIN_VALUE;
	private long lastSize = -1;
	private Map<String, Entry> entries = Collections.emptyMap();

	UsersFile(String path, Warn warn) {
		this.path = path == null || path.isBlank() ? null : Path.of(path);
		this.warn = warn;
	}

	/**
	 * Gets the current entries, re-reading the file if it changed.
	 *
	 * @return username to {@link Entry}; empty if the file is not usable
	 */
	synchronized Map<String, Entry> get() {
		if (this.path == null) {
			this.warn.warn("No usersPath configured: all logins are rejected");
			return Collections.emptyMap();
		}
		try {
			var modified = Files.getLastModifiedTime(this.path).toMillis();
			var size = Files.size(this.path);
			if (modified == this.lastModified && size == this.lastSize) {
				return this.entries;
			}
			this.lastModified = modified;
			this.lastSize = size;
			this.entries = parse(Files.readString(this.path, UTF_8), this.warn);
		} catch (IOException e) {
			this.warn.warn("Unable to read users file [" + this.path + "]: " + e.getMessage());
			this.lastModified = Long.MIN_VALUE;
			this.entries = Collections.emptyMap();
		}
		return this.entries;
	}

	/**
	 * Parses the users file content. An entry with a missing field is skipped
	 * with a warning; a file that is not valid JSON yields no users.
	 *
	 * @param json the file content
	 * @param warn the warning sink
	 * @return username to {@link Entry}
	 */
	static Map<String, Entry> parse(String json, Warn warn) {
		final com.google.gson.JsonObject users;
		try {
			users = JsonUtils.getAsJsonObject(JsonUtils.parse(json), "users");
		} catch (OpenemsNamedException | RuntimeException e) {
			warn.warn("Unable to parse users file: " + e.getMessage());
			return Collections.emptyMap();
		}
		var result = new HashMap<String, Entry>();
		for (var member : users.entrySet()) {
			var username = member.getKey();
			try {
				var user = JsonUtils.getAsJsonObject(member.getValue());
				var roleName = JsonUtils.getAsString(user, "role");
				var role = Role.getRole(roleName);
				if (!role.name().equalsIgnoreCase(roleName)) {
					warn.warn("User [" + username + "] has unknown role [" + roleName + "]; using GUEST");
				}
				result.put(username, new Entry(username, //
						JsonUtils.getAsOptionalString(user, "name").orElse(username), //
						role, //
						JsonUtils.getAsString(user, "salt"), //
						JsonUtils.getAsString(user, "hash"), //
						JsonUtils.getAsInt(user, "iterations")));
			} catch (OpenemsNamedException | RuntimeException e) {
				warn.warn("Skipping user [" + username + "]: " + e.getMessage());
			}
		}
		return Collections.unmodifiableMap(result);
	}
}
