package io.openems.backend.metadata.dummy;

import static java.util.stream.Collectors.joining;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

import com.google.gson.JsonObject;

import io.openems.backend.authentication.api.AuthUserPasswordAuthenticationService;
import io.openems.backend.authentication.api.model.PasswordAuthenticationResult;
import io.openems.backend.common.alerting.OfflineEdgeAlertingSetting;
import io.openems.backend.common.alerting.SumStateAlertingSetting;
import io.openems.backend.common.alerting.UserAlertingSettings;
import io.openems.backend.common.edge.jsonrpc.UpdateMetadataCache;
import io.openems.backend.common.metadata.AbstractMetadata;
import io.openems.backend.common.metadata.Edge;
import io.openems.backend.common.metadata.EdgeHandler;
import io.openems.backend.common.metadata.Metadata;
import io.openems.backend.common.metadata.MetadataUtils;
import io.openems.backend.common.metadata.SimpleEdgeHandler;
import io.openems.backend.common.metadata.User;
import io.openems.common.channel.Level;
import io.openems.common.event.EventBuilder;
import io.openems.common.event.EventReader;
import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.request.GetEdgesRequest.PaginationOptions;
import io.openems.common.jsonrpc.response.GetEdgesResponse.EdgeMetadata;
import io.openems.common.session.Language;
import io.openems.common.session.Role;
import io.openems.common.utils.ThreadPoolUtils;

/**
 * Metadata provider for single-site deployments without Odoo or Keycloak.
 *
 * <p>
 * Edges keep the original "Dummy" behaviour: Edge-IDs and API keys are
 * auto-provisioned, no external database. User logins are real: this
 * component also provides the {@link AuthUserPasswordAuthenticationService},
 * checking usernames and PBKDF2 password hashes from the JSON file at
 * {@link Config#usersPath()} (see users.example.json). Roles come from that
 * file. Unknown users, wrong passwords and a missing file are all rejected
 * with {@code COMMON_AUTHENTICATION_FAILED}.
 */
@Designate(ocd = Config.class, factory = false)
@Component(//
		name = "Metadata.Dummy", //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		service = { Metadata.class, EventHandler.class, MetadataDummy.class,
				AuthUserPasswordAuthenticationService.class }, //
		immediate = true //
)
@EventTopics({ //
		Edge.Events.ON_SET_CONFIG //
})
public class MetadataDummy extends AbstractMetadata
		implements Metadata, EventHandler, AuthUserPasswordAuthenticationService {

	private static final Pattern NAME_NUMBER_PATTERN = Pattern.compile("[^0-9]+([0-9]+)$");

	/**
	 * Fixed salt and hash used to spend the same PBKDF2 time on an unknown
	 * username as on a known one, so response time does not reveal which
	 * usernames exist.
	 */
	private static final String DECOY_SALT = "AAAAAAAAAAAAAAAAAAAAAA==";
	private static final String DECOY_HASH = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	private final Logger log = LoggerFactory.getLogger(MetadataDummy.class);

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	private final EventAdmin eventAdmin;
	private final AtomicInteger nextEdgeId = new AtomicInteger(-1);

	private final Map<String, User> users = new HashMap<>();
	private final Map<String, MyEdge> edges = new HashMap<>();
	private final SimpleEdgeHandler edgeHandler = new SimpleEdgeHandler();
	private final UsersFile usersFile;
	private final TokenStore tokens;

	private Language defaultLanguage = Language.DE;
	private JsonObject settings = new JsonObject();

	@Activate
	public MetadataDummy(@Reference EventAdmin eventadmin, Config config) {
		super("Metadata.Dummy");
		this.eventAdmin = eventadmin;
		this.usersFile = new UsersFile(config.usersPath(), message -> this.logWarn(this.log, message));
		final var lifetime = Duration.ofMinutes(Math.max(1, config.tokenLifetimeMinutes()));
		this.tokens = new TokenStore(lifetime, System::nanoTime);
		this.logInfo(this.log, "Activate [usersPath=" + config.usersPath() + ", users="
				+ this.usersFile.get().size() + "]");

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
	public Optional<User> getUser(String userId) {
		return Optional.ofNullable(this.users.get(userId));
	}

	@Override
	public CompletableFuture<User> getUserByExternalId(String login) {
		// Called after a successful password or token check, and by the OAuth
		// handler with a token subject. Only users in the users file exist;
		// the role always comes from the file.
		final var entry = login == null ? null : this.usersFile.get().get(login);
		if (entry == null) {
			this.logWarn(this.log, "Unknown user [" + login + "]");
			return CompletableFuture.failedFuture(OpenemsError.COMMON_AUTHENTICATION_FAILED.exception());
		}
		final var user = new User(entry.username(), entry.name(), entry.username(), this.defaultLanguage,
				entry.role(), this.hasMultipleEdges(), this.settings);
		this.users.put(user.getId(), user);
		return CompletableFuture.completedFuture(user);
	}

	@Override
	public CompletableFuture<PasswordAuthenticationResult> authenticateWithPassword(String username,
			String password) {
		final var entry = username == null ? null : this.usersFile.get().get(username);
		final boolean ok;
		if (entry == null) {
			PasswordHash.verify(password == null ? "" : password, DECOY_SALT, PasswordHash.DEFAULT_ITERATIONS,
					DECOY_HASH);
			ok = false;
		} else {
			ok = PasswordHash.verify(password, entry.salt(), entry.iterations(), entry.hash());
		}
		if (!ok) {
			// Same error for "no such user" and "wrong password"
			this.logWarn(this.log, "Rejected login for [" + username + "]");
			return CompletableFuture.failedFuture(OpenemsError.COMMON_AUTHENTICATION_FAILED.exception());
		}
		final var token = this.tokens.issue(entry.username());
		this.logInfo(this.log, "Authenticated [" + entry.username() + "] as [" + entry.role() + "]");
		return CompletableFuture
				.completedFuture(new PasswordAuthenticationResult(entry.username(), entry.username(), token));
	}

	@Override
	public CompletableFuture<PasswordAuthenticationResult> authenticateWithToken(String token) {
		final var username = this.tokens.lookup(token);
		if (username.isEmpty() || !this.usersFile.get().containsKey(username.get())) {
			this.tokens.revoke(token);
			return CompletableFuture.failedFuture(OpenemsError.COMMON_AUTHENTICATION_FAILED.exception());
		}
		return CompletableFuture
				.completedFuture(new PasswordAuthenticationResult(username.get(), username.get(), token));
	}

	@Override
	public CompletableFuture<Void> logout(String token) {
		this.tokens.revoke(token);
		return CompletableFuture.completedFuture(null);
	}

	private boolean hasMultipleEdges() {
		return this.edges.size() > 1;
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
		var edge = new MyEdge(this, edgeId, apikey, setupPassword, "OpenEMS Edge #" + id, "", "", null);
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
		var edge = new MyEdge(this, edgeId, edgeId, edgeId, "OpenEMS Edge #" + i, "", "", null);
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
	public SetupProtocolCoreInfo getLatestSetupProtocolCoreInfo(String edgeId) throws OpenemsNamedException {
		return null;
	}

	@Override
	public List<SetupProtocolCoreInfo> getProtocolsCoreInfo(String edgeId) throws OpenemsNamedException {
		return Collections.emptyList();
	}

	@Override
	public int submitSetupProtocol(User user, JsonObject jsonObject) {
		throw new UnsupportedOperationException("DummyMetadata.submitSetupProtocol() is not implemented");
	}

	@Override
	public void createSerialNumberExtensionProtocol(String edgeId, Map<String, Map<String, String>> serialNumbers,
			List<SetupProtocolItem> items) {
		this.log.info("SerialNumberProtocol[{}]: {}, {}", edgeId, serialNumbers, items);
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
		case Edge.Events.ON_SET_CONFIG -> {
			this.edgeHandler.setEdgeConfigFromEvent(reader, (edge, oldConfig, newConfig) -> {
				EventBuilder.from(this.eventAdmin, Edge.Events.ON_UPDATE_CONFIG) //
						.addArg(Edge.Events.OnUpdateConfig.EDGE_ID, edge.getId()) //
						.addArg(Edge.Events.OnUpdateConfig.OLD_CONFIG, oldConfig) //
						.addArg(Edge.Events.OnUpdateConfig.NEW_CONFIG, newConfig) //
						.send();
			});
		}
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
		return List.of(new UserAlertingSettings("demo", 5, 10, 15));
	}

	@Override
	public List<OfflineEdgeAlertingSetting> getEdgeOfflineAlertingSettings(String edgeId) throws OpenemsException {
		return List.of(new OfflineEdgeAlertingSetting(edgeId, "demo", 5, null));
	}

	@Override
	public List<SumStateAlertingSetting> getSumStateAlertingSettings(String edgeId) throws OpenemsException {
		return List.of(new SumStateAlertingSetting(edgeId, "demo", 10, 15, null));
	}

	@Override
	public void setUserAlertingSettings(User user, String edgeId, List<UserAlertingSettings> settings) {
		throw new UnsupportedOperationException("DummyMetadata.setUserAlertingSettings() is not implemented");
	}

	@Override
	public CompletableFuture<List<EdgeMetadata>> getPageDevice(User user, PaginationOptions paginationOptions) {
		return CompletableFuture.completedFuture(
				MetadataUtils.getPageDevice(user, this.edges.values(), MyEdge::getSettings, paginationOptions));
	}

	@Override
	public Role getUserRole(User user, String edgeId) {
		// Current role from the users file, so a role change or removal applies
		// to open sessions too. A removed user drops to GUEST.
		final var entry = this.usersFile.get().get(user.getId());
		return entry == null ? Role.GUEST : entry.role();
	}

	@Override
	public CompletableFuture<EdgeMetadata> getEdgeMetadataForUser(User user, String edgeId) {
		final var edge = this.edges.get(edgeId);
		if (edge == null) {
			return CompletableFuture.failedFuture(new OpenemsException("Unable to find edge with id [" + edgeId + "]"));
		}

		return CompletableFuture.completedFuture(new EdgeMetadata(//
				edge.getId(), //
				edge.getComment(), //
				edge.getProducttype(), //
				edge.getVersion(), //
				this.getUserRole(user, edgeId), //
				edge.isOnline(), //
				edge.getLastmessage(), //
				null, // firstSetupProtocol
				Level.OK, //
				edge.getSettings() //
		));
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

	@Override
	public CompletableFuture<Void> updateEdgeSettings(String edgeId, JsonObject settings) {
		var edge = this.edges.get(edgeId);
		edge.setSettings(settings);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public UpdateMetadataCache.Notification generateUpdateMetadataCacheNotification() {
		var apikeysToEdgeIds = this.edges.values().stream() //
				.collect(Collectors.toMap(//
						e -> e.getId(), //
						e -> e.getApikey()));
		return new UpdateMetadataCache.Notification(apikeysToEdgeIds);
	}

}
