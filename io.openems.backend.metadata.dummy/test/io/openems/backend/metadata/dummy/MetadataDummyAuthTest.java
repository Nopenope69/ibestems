package io.openems.backend.metadata.dummy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.openems.backend.common.test.DummyEventAdmin;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.session.Role;

public class MetadataDummyAuthTest {

	private static final String ALICE_PW = "alice-long-password";
	private static final String BOB_PW = "bob-long-password";

	private Path usersFile;
	private MetadataDummy sut;

	private static Config config(String usersPath) {
		return (Config) Proxy.newProxyInstance(Config.class.getClassLoader(), new Class<?>[] { Config.class },
				(proxy, method, args) -> switch (method.getName()) {
				case "edgeIdTemplate" -> "edge%d";
				case "edgeIdMax" -> 1;
				case "usersPath" -> usersPath;
				case "tokenLifetimeMinutes" -> 60;
				case "annotationType" -> Config.class;
				case "hashCode" -> 0;
				case "equals" -> proxy == args[0];
				default -> "Metadata Dummy";
				});
	}

	private static String entry(String name, String role, String password) {
		var salt = PasswordHash.newSalt();
		var hash = PasswordHash.hash(password, salt, PasswordHash.DEFAULT_ITERATIONS);
		return "\"" + name + "\":{\"name\":\"" + name + " Example\",\"role\":\"" + role + "\",\"salt\":\"" + salt
				+ "\",\"hash\":\"" + hash + "\",\"iterations\":" + PasswordHash.DEFAULT_ITERATIONS + "}";
	}

	private void writeUsers(String... entries) throws IOException {
		var existed = Files.exists(this.usersFile);
		var before = existed ? Files.getLastModifiedTime(this.usersFile).toMillis() : 0;
		Files.writeString(this.usersFile, "{\"users\":{" + String.join(",", entries) + "}}");
		if (existed) {
			Files.setLastModifiedTime(this.usersFile, FileTime.fromMillis(before + 5_000));
		}
	}

	private static void assertAuthFailed(CompletableFuture<?> future) {
		var e = assertThrows(ExecutionException.class, future::get);
		var cause = assertInstanceOf(OpenemsNamedException.class, e.getCause());
		assertEquals(OpenemsError.COMMON_AUTHENTICATION_FAILED, cause.getError());
	}

	@BeforeEach
	public void setUp(@TempDir Path dir) throws IOException {
		this.usersFile = dir.resolve("users.json");
		this.writeUsers(entry("alice", "owner", ALICE_PW), entry("bob", "admin", BOB_PW));
		this.sut = new MetadataDummy(new DummyEventAdmin(e -> {
		}), config(this.usersFile.toString()));
	}

	@Test
	public void wrongPasswordIsRejected() {
		assertAuthFailed(this.sut.authenticateWithPassword("alice", "alice-wrong-password"));
		assertAuthFailed(this.sut.authenticateWithPassword("alice", ""));
		assertAuthFailed(this.sut.authenticateWithPassword("alice", null));
	}

	@Test
	public void unknownUserIsRejectedTheSameWay() {
		assertAuthFailed(this.sut.authenticateWithPassword("mallory", ALICE_PW));
		assertAuthFailed(this.sut.authenticateWithPassword(null, ALICE_PW));
		assertAuthFailed(this.sut.getUserByExternalId("mallory"));
	}

	@Test
	public void correctPasswordGivesTheRoleFromTheFile() throws Exception {
		var result = this.sut.authenticateWithPassword("alice", ALICE_PW).get();
		assertEquals("alice", result.userId());
		assertFalse(result.token().isBlank());

		var user = this.sut.getUserByExternalId(result.userId()).get();
		assertEquals(Role.OWNER, user.getGlobalRole());
		assertEquals(Role.OWNER, this.sut.getUserRole(user, "edge0"));
		assertEquals(Role.OWNER, this.sut.getEdgeMetadataForUser(user, "edge0").get().role());

		var bob = this.sut.getUserByExternalId(this.sut.authenticateWithPassword("bob", BOB_PW).get().userId())
				.get();
		assertEquals(Role.ADMIN, this.sut.getUserRole(bob, "edge0"));
	}

	@Test
	public void ownerCannotPassAnAdminCheck() throws Exception {
		var user = this.sut.getUserByExternalId("alice").get();
		assertThrows(OpenemsNamedException.class,
				() -> this.sut.assertRoleIsAtLeast(user, "edge0", Role.ADMIN, "test"));
		assertEquals(Role.OWNER, this.sut.assertRoleIsAtLeast(user, "edge0", Role.OWNER, "test"));
	}

	@Test
	public void tokenWorksUntilLogout() throws Exception {
		var token = this.sut.authenticateWithPassword("alice", ALICE_PW).get().token();
		assertEquals("alice", this.sut.authenticateWithToken(token).get().userId());
		assertAuthFailed(this.sut.authenticateWithToken("forged-token"));

		this.sut.logout(token).get();
		assertAuthFailed(this.sut.authenticateWithToken(token));
	}

	@Test
	public void removingAUserRevokesAccessWithoutRestart() throws Exception {
		var token = this.sut.authenticateWithPassword("alice", ALICE_PW).get().token();
		final var user = this.sut.getUserByExternalId("alice").get();

		this.writeUsers(entry("bob", "admin", BOB_PW));

		assertAuthFailed(this.sut.authenticateWithToken(token));
		assertAuthFailed(this.sut.authenticateWithPassword("alice", ALICE_PW));
		assertEquals(Role.GUEST, this.sut.getUserRole(user, "edge0"));
	}

	@Test
	public void changingARoleAppliesToOpenSessions() throws Exception {
		var user = this.sut.getUserByExternalId("alice").get();
		this.writeUsers(entry("alice", "guest", ALICE_PW), entry("bob", "admin", BOB_PW));
		assertEquals(Role.GUEST, this.sut.getUserRole(user, "edge0"));
	}

	@Test
	public void missingUsersFileRejectsEveryone() throws Exception {
		Files.delete(this.usersFile);
		assertAuthFailed(this.sut.authenticateWithPassword("bob", BOB_PW));

		var unconfigured = new MetadataDummy(new DummyEventAdmin(e -> {
		}), config(""));
		assertAuthFailed(unconfigured.authenticateWithPassword("bob", BOB_PW));
		assertAuthFailed(unconfigured.getUserByExternalId("bob"));
	}

	@Test
	public void edgeApikeyPathIsUnchanged() {
		assertTrue(this.sut.getEdgeIdForApikey("edge0").isPresent());
		assertTrue(this.sut.getEdge("edge1").isPresent());
	}
}
