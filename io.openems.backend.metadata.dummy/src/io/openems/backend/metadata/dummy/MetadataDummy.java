package io.openems.backend.metadata.dummy;

import static java.util.stream.Collectors.joining;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.backend.common.alerting.OfflineEdgeAlertingSetting;
import io.openems.backend.common.alerting.SumStateAlertingSetting;
import io.openems.backend.common.alerting.UserAlertingSettings;
import io.openems.backend.common.metadata.AbstractMetadata;
import io.openems.backend.common.metadata.Edge;
import io.openems.backend.common.metadata.EdgeHandler;
import io.openems.backend.common.metadata.Metadata;
import io.openems.backend.common.metadata.MetadataUtils;
import io.openems.backend.common.metadata.SimpleEdgeHandler;
import io.openems.backend.common.metadata.User;
import io.openems.common.channel.Level;
import io.openems.common.event.EventReader;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.request.GetEdgesRequest.PaginationOptions;
import io.openems.common.jsonrpc.response.GetEdgesResponse.EdgeMetadata;
import io.openems.common.session.Language;
import io.openems.common.session.Role;
import io.openems.common.utils.JsonUtils;
import io.openems.common.utils.ThreadPoolUtils;

/**
 * This Metadata provider keeps the original "Dummy" behaviour for Edges
 * (auto-provisions Edge-IDs/API-keys, no external DB), but real,
 * password-checked authentication for UI/API logins - see B-001 in the
 * 2026-09-11 commercialization audit.
 *
 * <p>
 * Login credentials are NOT hard-coded and NOT accepted unconditionally: they
 * are read from the JSON file at {@link Config#usersPath()} (see
 * users.example.json), matched by username, and verified with
 * {@link PasswordHash} (PBKDF2WithHmacSHA256, per-user salt). A wrong
 * username or password is rejected with
 * {@code OpenemsError.COMMON_AUTHENTICATION_FAILED}, same as every other
 * Metadata provider. The file is re-read on every login attempt so adding or
 * removing a user does not require a Backend restart.
 */
@Designate(ocd = Config.class, factory = false)
@Component(//
		name = "Metadata.Dummy", //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		immediate = true //
)
@EventTopics({ //
		Edge.Events.ON_SET_CONFIG //
})
public class MetadataDummy extends AbstractMetadata implements Metadata, EventHandler {

	private static final Pattern NAME_NUMBER_PATTERN = Pattern.compile("[^0-9]+([0-9]+)$");

	private final Logger log = LoggerFactory.getLogger(MetadataDummy.class);

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final EventAdmin eventAdmin;
	private final AtomicInteger nextEdgeId = new AtomicInteger(-1);

	private final Map<String, User> users = new HashMap<>();
	private final Map<String, MyEdge> edges = new HashMap<>();
	private final SimpleEdgeHandler edgeHandler = new SimpleEdgeHandler();

	private Language defaultLanguage = Language.DE;
	private JsonObject settings = new JsonObject();

	private String usersPath = "";

	/**
	 * One entry from the users file: everything needed to verify a login and
	 * mint a {@link User} with the right Role. Never holds a plaintext
	 * password.
	 */
	private static final class StoredCredential {
		final String name;
		final Role role;
		final String salt;
		final String hash;
		final int iterations;

		StoredCredential(String name, Role role, String salt, String hash, int iterations) {
			this.name = name;
			this.role = role;
			this.salt = salt;
			this.hash = hash;
			this.iterations = iterations;
		}
	}

	@Activate
	public MetadataDummy(@Reference EventAdmin eventadmin, Config config) {
		super("Metadata.Dummy");
		this.eventAdmin = eventadmin;
		this.usersPath = config.usersPath();
		this.logInfo(this.log, "Activate [usersPath=" + this.usersPath + "]");

		// Prefill
		this.logInfo(this.log, "Prefilling Edges [" //
				+ String.format(config.edgeIdTemplate(), 0) + "..."
				+ String.format(config.edgeIdTemplate(), config.edgeIdMax()) + "]");
		for (var i = 0; i < config.edgeIdMax() + 1; i++) {
			this.createEdge(config.edgeIdTemplate(), i);
		}
		this.nextEdgeId.set(config.edgeIdMax() + 1);

		// Allow the services some time to settle
		this.executor.schedule(() -> {
			this.setInitialized();
		}, 10, TimeUnit.SECONDS);
	}

	@Deactivate
	private void deactivate() {
		ThreadPoolUtils.shutdownAndAwaitTermination(this.executor, 0);
		this.logInfo(this.log, "Deactivate");
	}

	@Override
	public User authenticate(String username, String password) throws OpenemsNamedException {
		var credentials = this.loadCredentials();
		var cred = credentials.get(username);
		if (cred == null || !PasswordHash.verify(password, cred.salt, cred.iterations, cred.hash)) {
			// Deliberately the same error for "no such user" and "wrong password" -
			// do not let a caller enumerate valid usernames.
			this.logWarn(this.log, "Rejected login for username [" + username + "]");
			throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
		}
		var token = UUID.randomUUID().toString();
		var user = new User(username, cred.name, token, this.defaultLanguage, cred.role, this.hasMultipleEdges(),
				this.settings);
		this.users.put(user.getId(), user);
		this.logInfo(this.log, "Authenticated [" + username + "] as [" + cred.role + "]");
		return user;
	}

	@Override
	public User authenticate(String token) throws OpenemsNamedException {
		for (var user : this.users.values()) {
			if (!user.getToken().equals(token)) {
				continue;
			}
			final var hasMultipleEdges = this.hasMultipleEdges();
			final User returnUser;
			if (user.hasMultipleEdges() != hasMultipleEdges //
					|| !user.getSettings().equals(this.settings)) {
				returnUser = this.createUser(user.getId(), user.getName(), user.getToken(), user.getGlobalRole(),
						hasMultipleEdges);
				this.users.put(token, returnUser);
			} else {
				returnUser = user;
			}

			return returnUser;
		}
		throw OpenemsError.COMMON_AUTHENTICATION_FAILED.exception();
	}

	private User createUser(String username, String name, String token, Role globalRole, boolean hasMultipleEdges) {
		return new User(username, name, token, this.defaultLanguage, globalRole, this.hasMultipleEdges(),
				this.settings);
	}

	private boolean hasMultipleEdges() {
		return this.edges.size() > 1;
	}

	/**
	 * Reads and parses {@link #usersPath}. Re-read on every call (logins are
	 * infrequent) so editing the file takes effect without a restart. Returns
	 * an empty map - i.e. every login is rejected - if the file is missing or
	 * malformed, rather than falling back to any default identity.
	 *
	 * @return username -> {@link StoredCredential}
	 */
	private Map<String, StoredCredential> loadCredentials() {
		var result = new HashMap<String, StoredCredential>();
		if (this.usersPath == null || this.usersPath.isBlank()) {
			this.logWarn(this.log, "No usersPath configured - all logins will be rejected");
			return result;
		}

		var sb = new StringBuilder();
		try (var br = new BufferedReader(new FileReader(this.usersPath))) {
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
		} catch (IOException e) {
			this.logWarn(this.log, "Unable to read users file [" + this.usersPath + "]: " + e.getMessage());
			return result;
		}

		try {
			var root = JsonUtils.parse(sb.toString());
			var jUsers = JsonUtils.getAsJsonObject(root, "users");
			for (Entry<String, JsonElement> entry : jUsers.entrySet()) {
				var username = entry.getKey();
				var jUser = JsonUtils.getAsJsonObject(entry.getValue());
				var name = JsonUtils.getAsOptionalString(jUser, "name").orElse(username);
				var role = Role.getRole(JsonUtils.getAsString(jUser, "role"));
				var salt = JsonUtils.getAsString(jUser, "salt");
				var hash = JsonUtils.getAsString(jUser, "hash");
				var iterations = JsonUtils.getAsInt(jUser, "iterations");
				result.put(username, new StoredCredential(name, role, salt, hash, iterations));
			}
		} catch (OpenemsNamedException e) {
			this.logWarn(this.log, "Unable to JSON-parse users file [" + this.usersPath + "]: " + e.getMessage());
			return new HashMap<>();
		}
		return result;
	}

	@Override
	public void logout(User user) {
		this.users.remove(user.getId(), user);
	}

	@Override
	public Optional<String> getEdgeIdForApikey(String apikey) {
		var edgeOpt = this.edges.values().stream() //
				.filter(edge -> apikey.equals(edge.getApikey())) //
				.findFirst();
		if (edgeOpt.isPresent()) {
			return Optional.ofNullable(edgeOpt.get().getId());
		}
		// not found. Is apikey a valid Edge-ID?
		var idOpt = MetadataDummy.parseNumberFromName(apikey);
		int id;
		String edgeId;
		String setupPassword;
		if (idOpt.isPresent()) {
			edgeId = apikey;
			id = idOpt.get();
		} else {
			// create new ID
			id = this.nextEdgeId.incrementAndGet();
			edgeId = "edge" + id;
		}
		setupPassword = edgeId;
		var edge = new MyEdge(this, edgeId, apikey, setupPassword, "OpenEMS Edge #" + id, "", "");
		this.edges.put(edgeId, edge);
		return Optional.ofNullable(edgeId);
	}

	/**
	 * Creates and adds a {@link MyEdge}.
	 * 
	 * @param edgeIdTemplate the Edge-ID template
	 * @param i              value to be filled in the template
	 */
	private void createEdge(String edgeIdTemplate, int i) {
		var edgeId = String.format(edgeIdTemplate, i);
		var edge = new MyEdge(this, edgeId, edgeId, edgeId, "OpenEMS Edge #" + i, "", "");
		this.edges.put(edgeId, edge);
	}

	@Override
	public Optional<Edge> getEdgeBySetupPassword(String setupPassword) {
		var edgeOpt = this.edges.values().stream().filter(edge -> edge.getSetupPassword().equals(setupPassword))
				.findFirst();

		if (edgeOpt.isPresent()) {
			var edge = edgeOpt.get();
			return Optional.of(edge);
		}

		return Optional.empty();
	}

	@Override
	public Optional<Edge> getEdge(String edgeId) {
		Edge edge = this.edges.get(edgeId);
		return Optional.ofNullable(edge);
	}

	@Override
	public Optional<User> getUser(String userId) {
		return Optional.ofNullable(this.users.get(userId));
	}

	@Override
	public Collection<Edge> getAllOfflineEdges() {
		return this.edges.values().stream().filter(Edge::isOffline).collect(Collectors.toUnmodifiableList());
	}

	private static Optional<Integer> parseNumberFromName(String name) {
		try {
			var matcher = MetadataDummy.NAME_NUMBER_PATTERN.matcher(name);
			if (matcher.find()) {
				var nameNumberString = matcher.group(1);
				return Optional.ofNullable(Integer.parseInt(nameNumberString));
			}
		} catch (NullPointerException e) {
			/* ignore */
		}
		return Optional.empty();
	}

	@Override
	public void addEdgeToUser(User user, Edge edge) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.addEdgeToUser() is not implemented");
	}

	@Override
	public Map<String, Object> getUserInformation(User user) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.getUserInformation() is not implemented");
	}

	@Override
	public void setUserInformation(User user, JsonObject jsonObject) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.setUserInformation() is not implemented");
	}

	@Override
	public byte[] getSetupProtocol(User user, int setupProtocolId) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.getSetupProtocol() is not implemented");
	}

	@Override
	public JsonObject getSetupProtocolData(User user, String edgeId) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.getSetupProtocolData() is not implemented");
	}

	@Override
	public int submitSetupProtocol(User user, JsonObject jsonObject) {
		throw new UnsupportedOperationException("DummyMetadata.submitSetupProtocol() is not implemented");
	}

	@Override
	public void registerUser(JsonObject jsonObject, String oem) throws OpenemsNamedException {
		throw new UnsupportedOperationException("DummyMetadata.registerUser() is not implemented");
	}

	@Override
	public void updateUserLanguage(User user, Language language) throws OpenemsNamedException {
		this.defaultLanguage = language;
	}

	@Override
	public EventAdmin getEventAdmin() {
		return this.eventAdmin;
	}

	@Override
	public void handleEvent(Event event) {
		var reader = new EventReader(event);

		switch (event.getTopic()) {
		case Edge.Events.ON_SET_CONFIG:
			this.edgeHandler.setEdgeConfigFromEvent(reader);
			break;
		}
	}

	@Override
	public EdgeHandler edge() {
		return this.edgeHandler;
	}

	@Override
	public Optional<String> getSerialNumberForEdge(Edge edge) {
		throw new UnsupportedOperationException("DummyMetadata.getSerialNumberForEdge() is not implemented");
	}

	@Override
	public Optional<String> getEmsTypeForEdge(String edgeId) {
		throw new UnsupportedOperationException("DummyMetadata.getEmsTypeForEdge() is not implemented");
	}

	@Override
	public UserAlertingSettings getUserAlertingSettings(String edgeId, String userId) throws OpenemsException {
		throw new UnsupportedOperationException("DummyMetadata.getUserAlertingSettings() is not implemented");
	}

	@Override
	public List<UserAlertingSettings> getUserAlertingSettings(String edgeId) {
		throw new UnsupportedOperationException("DummyMetadata.getUserAlertingSettings() is not implemented");
	}

	@Override
	public List<OfflineEdgeAlertingSetting> getEdgeOfflineAlertingSettings(String edgeId) throws OpenemsException {
		throw new UnsupportedOperationException("DummyMetadata.getEdgeOfflineAlertingSettings() is not implemented");
	}

	@Override
	public List<SumStateAlertingSetting> getSumStateAlertingSettings(String edgeId) throws OpenemsException {
		throw new UnsupportedOperationException("DummyMetadata.getSumStateAlertingSettings() is not implemented");
	}

	@Override
	public void setUserAlertingSettings(User user, String edgeId, List<UserAlertingSettings> settings) {
		throw new UnsupportedOperationException("DummyMetadata.setUserAlertingSettings() is not implemented");
	}

	@Override
	public List<EdgeMetadata> getPageDevice(User user, PaginationOptions paginationOptions)
			throws OpenemsNamedException {
		return MetadataUtils.getPageDevice(user, this.edges.values(), paginationOptions);
	}

	@Override
	public EdgeMetadata getEdgeMetadataForUser(User user, String edgeId) throws OpenemsNamedException {
		final var edge = this.edges.get(edgeId);
		if (edge == null) {
			return null;
		}
		// Use the User's own verified global Role - do NOT hand out ADMIN
		// regardless of who they are (this was the other half of B-001: even a
		// correctly-authenticated non-admin user was silently upgraded here).
		var role = user.getGlobalRole();
		user.setRole(edgeId, role);

		return new EdgeMetadata(//
				edge.getId(), //
				edge.getComment(), //
				edge.getProducttype(), //
				edge.getVersion(), //
				role, //
				edge.isOnline(), //
				edge.getLastmessage(), //
				null, // firstSetupProtocol
				Level.OK //
		);
	}

	@Override
	public Optional<Level> getSumState(String edgeId) {
		throw new UnsupportedOperationException("DummyMetadata.getSumState() is not implemented");
	}

	@Override
	public void logGenericSystemLog(GenericSystemLog systemLog) {
		this.logInfo(this.log,
				"%s on %s executed %s [%s]".formatted(systemLog.user().getId(), systemLog.edgeId(), systemLog.teaser(),
						systemLog.getValues().entrySet().stream() //
								.map(t -> t.getKey() + "=" + t.getValue()) //
								.collect(joining(", "))));
	}

	@Override
	public void updateUserSettings(User user, JsonObject settings) {
		this.settings = settings == null ? new JsonObject() : settings;
	}
}
