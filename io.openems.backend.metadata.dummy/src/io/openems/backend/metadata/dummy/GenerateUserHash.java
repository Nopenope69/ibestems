package io.openems.backend.metadata.dummy;

import java.io.Console;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone CLI to generate one users.json entry for Metadata.Dummy's new
 * password-checked authenticate() (see B-001, 2026-09-11 commercialization
 * audit). Depends only on {@link PasswordHash} and the JDK, so it can be
 * compiled and run without pulling in the rest of the OpenEMS build:
 *
 * <pre>
 * javac io/openems/backend/metadata/dummy/PasswordHash.java \
 *       io/openems/backend/metadata/dummy/GenerateUserHash.java -d /tmp/genhash-out
 * java -cp /tmp/genhash-out io.openems.backend.metadata.dummy.GenerateUserHash \
 *       polli "Polli" owner
 * </pre>
 *
 * Prompts for the password on the terminal (never takes it as an argv, so it
 * does not end up in shell history or `ps`), then prints the JSON snippet to
 * paste into users.json under "users".
 */
public final class GenerateUserHash {

	private static final List<String> VALID_ROLES = Arrays.asList("admin", "installer", "owner", "guest");

	private GenerateUserHash() {
	}

	/**
	 * CLI entry point.
	 *
	 * @param args {@code <username> <displayName> <role>} - role is one of
	 *             admin/installer/owner/guest
	 */
	public static void main(String[] args) {
		if (args.length != 3) {
			System.err.println("Usage: GenerateUserHash <username> <displayName> <role: admin|installer|owner|guest>");
			System.exit(1);
		}
		var username = args[0];
		var displayName = args[1];
		var role = args[2].toLowerCase();
		if (!VALID_ROLES.contains(role)) {
			System.err.println("Role must be one of " + VALID_ROLES + ", got: " + args[2]);
			System.exit(1);
		}

		var console = System.console();
		String password;
		if (console != null) {
			var pw1 = console.readPassword("Password for %s: ", username);
			var pw2 = console.readPassword("Repeat password: ");
			if (!Arrays.equals(pw1, pw2)) {
				System.err.println("Passwords did not match.");
				System.exit(1);
				return;
			}
			password = new String(pw1);
		} else {
			// No TTY (e.g. piped) - fall back to a visible prompt via stdin.
			System.err.println("No console available; reading password from stdin (will be visible).");
			password = new java.util.Scanner(System.in).nextLine();
		}

		var salt = PasswordHash.newSalt();
		var hash = PasswordHash.hash(password, salt, PasswordHash.DEFAULT_ITERATIONS);

		System.out.println();
		System.out.println("Add this under \"users\" in users.json:");
		System.out.println();
		System.out.printf("  \"%s\": {%n", username);
		System.out.printf("    \"name\": \"%s\",%n", displayName);
		System.out.printf("    \"role\": \"%s\",%n", role);
		System.out.printf("    \"salt\": \"%s\",%n", salt);
		System.out.printf("    \"hash\": \"%s\",%n", hash);
		System.out.printf("    \"iterations\": %d%n", PasswordHash.DEFAULT_ITERATIONS);
		System.out.println("  }");
	}
}
