package io.openems.edge.ess.pylontech.rs485;

import com.google.gson.JsonObject;

/**
 * One reading from pylon_bridge.py's {@code GET /battery}, already converted
 * into OpenEMS conventions. Pure and IO-free so the conversion can be unit
 * tested without an OSGi container.
 *
 * <p>
 * <b>Sign conventions — the reason this class exists.</b> pylon_bridge.py
 * reports {@code power_W} with <b>positive = charging</b> (its own header:
 * "Power only (W, positive=charging)"; Pylontech's pack current is positive
 * into the battery). OpenEMS' {@code ActivePower} is <b>positive =
 * discharging</b>, and EMS-ADDONS' ADR-0001 fixes the same convention for
 * every interface. The first version of this bundle copied {@code power_W}
 * straight into {@code ActivePower}, so every charge was reported upstream as
 * a discharge. The flip happens here, once, at the seam.
 *
 * <p>
 * <b>Allowed power.</b> OpenEMS expects {@code AllowedChargePower} &le; 0 and
 * {@code AllowedDischargePower} &ge; 0. A missing or disabled limit is
 * reported as 0 (cannot charge / cannot discharge), never as "unlimited":
 * when the BMS has not told us we may move energy, we may not.
 */
public record BridgeReading(Integer socPct, Integer activePowerW, int allowedChargePowerW,
		int allowedDischargePowerW) {

	/** Fallback pack voltage when the bridge omits it — nominal 48 V LFP. */
	public static final double NOMINAL_VOLTAGE_V = 48.0;

	/** A reading that permits nothing and reports nothing. Used when stale. */
	public static final BridgeReading UNAVAILABLE = new BridgeReading(null, null, 0, 0);

	/**
	 * Converts a pylon_bridge.py {@code /battery} payload.
	 *
	 * @param state the parsed JSON object
	 * @return the reading in OpenEMS conventions
	 * @throws IllegalArgumentException if a present field has the wrong type
	 */
	public static BridgeReading fromBridgeJson(JsonObject state) {
		try {
			var soc = has(state, "soc_pct") ? clamp(state.get("soc_pct").getAsInt(), 0, 100) : null;

			// Bridge: positive = charging. OpenEMS: positive = discharging.
			var activePower = has(state, "power_W") ? -state.get("power_W").getAsInt() : null;

			var voltageV = has(state, "voltage_V") ? state.get("voltage_V").getAsDouble() : NOMINAL_VOLTAGE_V;
			var chargeEnable = !has(state, "charge_enable") || state.get("charge_enable").getAsBoolean();
			var dischargeEnable = !has(state, "discharge_enable") || state.get("discharge_enable").getAsBoolean();
			var maxChargeA = has(state, "max_charge_current_A") ? state.get("max_charge_current_A").getAsDouble() : 0;
			var maxDischargeA = has(state, "max_discharge_current_A")
					? state.get("max_discharge_current_A").getAsDouble()
					: 0;

			var allowedCharge = chargeEnable ? toWatts(voltageV, maxChargeA) : 0;
			var allowedDischarge = dischargeEnable ? toWatts(voltageV, maxDischargeA) : 0;

			return new BridgeReading(soc, activePower, -allowedCharge, allowedDischarge);
		} catch (UnsupportedOperationException | IllegalStateException | NumberFormatException e) {
			throw new IllegalArgumentException("Malformed pylon_bridge payload: " + e.getMessage(), e);
		}
	}

	private static boolean has(JsonObject state, String key) {
		return state.has(key) && !state.get(key).isJsonNull();
	}

	/** Non-negative watts; a negative current limit from a confused BMS is 0. */
	private static int toWatts(double voltageV, double currentA) {
		var watts = voltageV * currentA;
		if (!Double.isFinite(watts) || watts <= 0) {
			return 0;
		}
		return (int) Math.min(watts, Integer.MAX_VALUE);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** Charge-side power for {@code ActiveChargeEnergy}: W into the battery, else 0. */
	public static Integer chargePower(Integer activePowerW) {
		return activePowerW == null ? null : Math.max(0, -activePowerW);
	}

	/** Discharge-side power for {@code ActiveDischargeEnergy}: W out of the battery, else 0. */
	public static Integer dischargePower(Integer activePowerW) {
		return activePowerW == null ? null : Math.max(0, activePowerW);
	}
}
