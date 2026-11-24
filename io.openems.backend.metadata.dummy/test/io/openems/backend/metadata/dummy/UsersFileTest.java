package io.openems.backend.metadata.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.openems.common.session.Role;

public class UsersFileTest {

	private final List<String> warnings = new ArrayList<>();
	private final UsersFile.Warn warn = this.warnings::add;

	private static String user(String role) {
		return "{\"name\":\"N\",\"role\":\"" + role + "\",\"salt\":\"s\",\"hash\":\"h\",\"iterations\":210000}";
	}

	@Test
	public void parsesEntriesAndRoles() {
		var map = UsersFile.parse("{\"users\":{\"a\":" + user("admin") + ",\"o\":" + user("OWNER") + "}}",
				this.warn);
		assertEquals(Role.ADMIN, map.get("a").role());
		assertEquals(Role.OWNER, map.get("o").role());
		assertEquals("N", map.get("a").name());
		assertEquals(210000, map.get("a").iterations());
		assertTrue(this.warnings.isEmpty());
	}

	@Test
	public void unknownRoleFallsBackToGuest() {
		var map = UsersFile.parse("{\"users\":{\"x\":" + user("superuser") + "}}", this.warn);
		assertEquals(Role.GUEST, map.get("x").role());
		assertEquals(1, this.warnings.size());
	}

	@Test
	public void incompleteEntryIsSkipped() {
		var map = UsersFile.parse("{\"users\":{\"ok\":" + user("guest") + ",\"bad\":{\"role\":\"admin\"}}}",
				this.warn);
		assertEquals(1, map.size());
		assertFalse(map.containsKey("bad"));
	}

	@Test
	public void malformedFileYieldsNoUsers() {
		assertTrue(UsersFile.parse("not json", this.warn).isEmpty());
		assertTrue(UsersFile.parse("{\"people\":{}}", this.warn).isEmpty());
	}

	@Test
	public void missingOrUnsetPathYieldsNoUsers(@TempDir Path dir) {
		assertTrue(new UsersFile("", this.warn).get().isEmpty());
		assertTrue(new UsersFile(null, this.warn).get().isEmpty());
		assertTrue(new UsersFile(dir.resolve("absent.json").toString(), this.warn).get().isEmpty());
	}

	@Test
	public void reloadsWhenTheFileChanges(@TempDir Path dir) throws IOException {
		var file = dir.resolve("users.json");
		Files.writeString(file, "{\"users\":{\"a\":" + user("admin") + "}}");
		var users = new UsersFile(file.toString(), this.warn);
		assertEquals(Role.ADMIN, users.get().get("a").role());

		Files.writeString(file, "{\"users\":{\"a\":" + user("guest") + ",\"b\":" + user("owner") + "}}");
		Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
		assertEquals(Role.GUEST, users.get().get("a").role());
		assertEquals(2, users.get().size());

		Files.delete(file);
		assertTrue(users.get().isEmpty());
	}
}
