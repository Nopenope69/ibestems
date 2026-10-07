package io.openems.edge.ess.pylontech.rs485;

import static io.openems.common.bridge.http.api.HttpMethod.GET;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import static java.util.Collections.emptyMap;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttpFactory;
import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleService;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleServiceDefinition;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(
		name = "Ess.PylontechRs485",
		immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ TOPIC_CYCLE_AFTER_PROCESS_IMAGE })
public class EssPylontechRs485Impl extends AbstractOpenemsComponent
		implements EssPylontechRs485, ManagedSymmetricEss, SymmetricEss, OpenemsComponent,
		EventHandler, TimedataProvider, StartStoppable, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(EssPylontechRs485Impl.class);

	// ── OSGi references ───────────────────────────────────────────────────────

	@Reference
	private Power power;

	@Reference(cardinality = MANDATORY)
	private BridgeHttpFactory httpBridgeFactory;

	@Reference(cardinality = MANDATORY)
	private HttpBridgeCycleServiceDefinition httpBridgeCycleServiceDefinition;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata;

	// ── State ─────────────────────────────────────────────────────────────────

	private Config config;
	private BridgeHttp httpBridge;
	private HttpBridgeCycleService cycleService;
	private volatile boolean bridgeError = false;
	private boolean communicationFailed = false;

	/**
	 * Latest successfully converted reading and when it arrived, updated from the
	 * HTTP bridge thread and read on the cycle thread. Kept as one immutable
	 * pair so the cycle never sees a reading with another reading's timestamp.
	 */
	private record Timestamped(BridgeReading reading, Instant receivedAt) {
	}

	private final AtomicReference<Timestamped> latest = new AtomicReference<>(null);

	private Instant activatedAt = Instant.now();

	private final CalculateEnergyFromPower calculateChargeEnergy =
			new CalculateEnergyFromPower(this, SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDischargeEnergy =
			new CalculateEnergyFromPower(this, SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	public EssPylontechRs485Impl() {
		super(
				OpenemsComponent.ChannelId.values(),
				SymmetricEss.ChannelId.values(),
				ManagedSymmetricEss.ChannelId.values(),
				StartStoppable.ChannelId.values(),
				EssPylontechRs485.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.activatedAt = Instant.now();

		this._setMaxApparentPower(config.maxApparentPower());
		this._setCapacity(config.capacityWh());
		setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.ON_GRID);
		this._setStartStop(StartStop.START);

		if (!config.enabled()) {
			return;
		}

		this.httpBridge = this.httpBridgeFactory.get();
		this.cycleService = this.httpBridge.createService(this.httpBridgeCycleServiceDefinition);

		this.cycleService.subscribeCycle(
				config.pollCycles(),
				this.createEndpoint(GET, config.bridgeUrl()),
				t -> this.handleBridgeResponse(t),
				t -> this.handleBridgeError(t));
	}

	@Override
	protected void deactivate() {
		if (this.httpBridge != null) {
			this.httpBridgeFactory.unget(this.httpBridge);
			this.httpBridge = null;
		}
		super.deactivate();
	}

	// ── HTTP bridge helpers ───────────────────────────────────────────────────

	protected BridgeHttp.Endpoint createEndpoint(io.openems.common.bridge.http.api.HttpMethod method, String url) {
		return new BridgeHttp.Endpoint(url, method,
				BridgeHttp.DEFAULT_CONNECT_TIMEOUT, BridgeHttp.DEFAULT_READ_TIMEOUT, "",
				emptyMap());
	}

	// ── HTTP bridge callbacks ─────────────────────────────────────────────────

	private void handleBridgeResponse(io.openems.common.bridge.http.api.HttpResponse<String> response) {
		try {
			var json = io.openems.common.utils.JsonUtils.parse(response.data());
			if (!json.isJsonObject()) {
				this.logWarn(this.log, "Bridge returned non-object JSON");
				this.bridgeError = true;
				return;
			}
			// Convert here, not on the cycle thread: a malformed payload is a
			// bridge error now, rather than an exception inside handleEvent later.
			this.latest.set(new Timestamped(BridgeReading.fromBridgeJson(json.getAsJsonObject()), Instant.now()));
			this.bridgeError = false;
		} catch (Exception e) {
			this.logWarn(this.log, "Bridge JSON parse error: " + e.getMessage());
			this.bridgeError = true;
		}
	}

	private void handleBridgeError(io.openems.common.bridge.http.api.HttpError err) {
		this.logWarn(this.log, "Bridge HTTP error: " + err);
		this.bridgeError = true;
	}

	// ── Cycle event ───────────────────────────────────────────────────────────

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.updateChannelsFromBridge();
			this.calculateEnergy();
			break;
		}
	}

	private void updateChannelsFromBridge() {
		var current = this.latest.get();
		var now = Instant.now();
		var window = Duration.ofSeconds(this.config.staleAfterSeconds());

		// No reading yet, or an expired one: allow nothing, report nothing.
		// Previously the last payload was held forever, so a dead bridge kept
		// advertising its last charge/discharge limits to the Power solver.
		var usable = current != null && Duration.between(current.receivedAt(), now).compareTo(window) <= 0;
		var reading = usable ? current.reading() : BridgeReading.UNAVAILABLE;

		// The FAULT is only raised once the window has elapsed since the last
		// reading, or since activation if none has ever arrived — so a normal
		// start-up (first poll is up to pollCycles away) is not a fault.
		var since = current != null ? current.receivedAt() : this.activatedAt;
		var failed = !usable && Duration.between(since, now).compareTo(window) > 0;
		this.communicationFailed = failed;
		this.channel(EssPylontechRs485.ChannelId.BRIDGE_COMMUNICATION_FAILED).setNextValue(failed);

		this._setSoc(reading.socPct());
		this._setActivePower(reading.activePowerW());
		this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).setNextValue(reading.allowedChargePowerW());
		this._setAllowedDischargePower(reading.allowedDischargePowerW());
		this._setReactivePower(reading.activePowerW() == null ? null : 0);
	}

	private void calculateEnergy() {
		// Each counter gets its own non-negative side of the signed power, as
		// upstream's simulators do. Passing the signed value to both made
		// ActiveChargeEnergy count discharges and never count a charge.
		var activePower = this.getActivePower().get();
		this.calculateChargeEnergy.update(BridgeReading.chargePower(activePower));
		this.calculateDischargeEnergy.update(BridgeReading.dischargePower(activePower));
	}

	// ── ManagedSymmetricEss ───────────────────────────────────────────────────

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		// Phase 1: pylon_bridge.py has no write endpoint, so the setpoint is
		// recorded but NOT executed. It goes to the DEBUG channel, not to
		// ActivePower: ActivePower is the measured value from the BMS, and
		// overwriting it with the setpoint made every command look obeyed.
		// Phase 2 (hardware with a PCS): forward to the PCS, not to the BMS.
		this.getDebugSetActivePowerChannel().setNextValue(activePower);
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public int getPowerPrecision() {
		return 1;
	}

	// ── StartStoppable ────────────────────────────────────────────────────────

	@Override
	public void setStartStop(StartStop value) {
		this._setStartStop(value);
	}

	// ── TimedataProvider ─────────────────────────────────────────────────────

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	// ── ModbusSlave ───────────────────────────────────────────────────────────

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(
				OpenemsComponent.getModbusSlaveNatureTable(accessMode),
				SymmetricEss.getModbusSlaveNatureTable(accessMode),
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode));
	}

	// ── Debug log ────────────────────────────────────────────────────────────

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString()
				+ "|P:" + this.getActivePower().orElse(null) + "W"
				+ "|Chg:" + this.getAllowedChargePower().orElse(null) + "W"
				+ "|Dis:" + this.getAllowedDischargePower().orElse(null) + "W"
				+ (this.bridgeError ? "|BridgeERR" : "")
				+ (this.communicationFailed ? "|STALE" : "");
	}
}
