package io.openems.edge.ess.pylontech.rs485;

import static io.openems.common.bridge.http.api.HttpMethod.GET;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import static java.util.Collections.emptyMap;

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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
	private boolean bridgeError = false;

	/** Latest JSON payload from the bridge, updated asynchronously. */
	private final AtomicReference<JsonObject> latestState = new AtomicReference<>(null);

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
			this.latestState.set(json.getAsJsonObject());
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
		JsonObject state = this.latestState.get();
		if (state == null) {
			return;
		}

		// SoC
		if (state.has("soc_pct")) {
			this._setSoc(state.get("soc_pct").getAsInt());
		}

		// Voltage for power limit calculation
		double voltageV = state.has("voltage_V") ? state.get("voltage_V").getAsDouble() : 48.0;

		// Charge / discharge enable flags
		boolean chargeEnable    = !state.has("charge_enable")    || state.get("charge_enable").getAsBoolean();
		boolean dischargeEnable = !state.has("discharge_enable") || state.get("discharge_enable").getAsBoolean();

		// Current limits from BMS
		double maxChargeA    = state.has("max_charge_current_A")
				? state.get("max_charge_current_A").getAsDouble() : 0;
		double maxDischargeA = state.has("max_discharge_current_A")
				? state.get("max_discharge_current_A").getAsDouble() : 0;

		// Power limits (W); OpenEMS: charge = negative, discharge = positive
		int allowedCharge    = chargeEnable    ? (int) (voltageV * maxChargeA)    : 0;
		int allowedDischarge = dischargeEnable ? (int) (voltageV * maxDischargeA) : 0;

		this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).setNextValue(-allowedCharge);
		this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER).setNextValue(allowedDischarge);

		// Active power from bridge (W); negative = charging
		if (state.has("power_W")) {
			this._setActivePower(state.get("power_W").getAsInt());
		}

		this._setReactivePower(0);
	}

	private void calculateEnergy() {
		this.calculateChargeEnergy.update(this.getActivePower().orElse(null));
		this.calculateDischargeEnergy.update(this.getActivePower().orElse(null));
	}

	// ── ManagedSymmetricEss ───────────────────────────────────────────────────

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		// Phase 1 (simulator): record setpoint only.
		// Phase 2 (hardware): POST to pylon_bridge.py /battery/power
		this._setActivePower(activePower);
		this._setReactivePower(reactivePower);
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
				+ (this.bridgeError ? "|BridgeERR" : "");
	}
}
