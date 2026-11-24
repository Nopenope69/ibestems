package io.openems.backend.metadata.dummy;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Command-line tool that prints one users.json entry for Metadata.Dummy.
 * Depends only on {@link PasswordHash} and the JDK, so it runs without the
 * rest of the OpenEMS build:
 *
 * <pre>
 * cd io.openems.backend.metadata.dummy/src/io/openems/backend/metadata/dummy
 * javac -d /tmp/genhash PasswordHash.java GenerateUserHash.java
 * java -cp /tmp/genhash io.openems.backend.metadata.dummy.GenerateUserHash \
 *     alice "Alice Example" owner
 * </pre>
 *
 * <p>
 * The password is read from the terminal, never from the command line, so it
 * does not land in shell history or the process list.
 */
public final class GenerateUserHash {

	private static final List<String> VALID_ROLES = Arrays.asList("admin", "installer", "owner", "guest");

	private GenerateUserHash() {
	}

	/**
	 * Entry point.
	 *
	 * @param args {@code <username> <displayName> <role>}, role one of
	 *             admin/installer/owner/guest
	 */
	public static void main(String[] args) {
		if (args.length != 3) {
			System.err.println("Usage: GenerateUserHash <username> <displayName> <admin|installer|owner|guest>");
			System.exit(1);
			return;
		}
		final var username = args[0];
		final var displayName = args[1];
		final var role = args[2].toLowerCase();
		if (!VALID_ROLES.contains(role)) {
			System.err.println("Role must be one of " + VALID_ROLES + ", got: " + args[2]);
			System.exit(1);
			return;
		}

		final String password;
		final var console = System.console();
		if (console != null) {
			var first = console.readPassword("Password for %s: ", username);
			var second = console.readPassword("Repeat password: ");
			if (first == null || !Arrays.equals(first, second)) {
				System.err.println("Passwords did not match.");
				System.exit(1);
				return;
			}
			password = new String(first);
			Arrays.fill(first, '\0');
			Arrays.fill(second, '\0');
		} else {
			// No terminal (piped input): read one line from stdin
			System.err.println("No terminal; reading the password from stdin.");
			try (var scanner = new Scanner(System.in)) {
				password = scanner.hasNextLine() ? scanner.nextLine() : "";
			}
		}
		if (password.length() < 12) {
			System.err.println("Use at least 12 characters.");
			System.exit(1);
			return;
		}

		final var salt = PasswordHash.newSalt();
		final var hash = PasswordHash.hash(password, salt, PasswordHash.DEFAULT_ITERATIONS);

		System.out.println();
		System.out.println("Add this under \"users\" in users.json:");
		System.out.println();
		System.out.printf("    \"%s\": {%n", username);
		System.out.printf("      \"name\": \"%s\",%n", displayName.replace("\"", "\\\""));
		System.out.printf("      \"role\": \"%s\",%n", role);
		System.out.printf("      \"salt\": \"%s\",%n", salt);
		System.out.printf("      \"hash\": \"%s\",%n", hash);
		System.out.printf("      \"iterations\": %d%n", PasswordHash.DEFAULT_ITERATIONS);
		System.out.println("    }");
	}
}
