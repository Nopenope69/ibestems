package io.openems.edge.ess.pylontech.rs485;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BridgeReadingTest {

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void chargingOnTheBridgeIsNegativeActivePowerInOpenems() {
		// pylon_bridge.py: power_W positive = charging.
		var r = BridgeReading.fromBridgeJson(json("{\"power_W\": 1200}"));
		assertEquals(-1200, r.activePowerW());
	}

	@Test
	void dischargingOnTheBridgeIsPositiveActivePowerInOpenems() {
		var r = BridgeReading.fromBridgeJson(json("{\"power_W\": -800}"));
		assertEquals(800, r.activePowerW());
	}

	@Test
	void allowedPowersFollowOpenemsSigns() {
		var r = BridgeReading.fromBridgeJson(json(
				"{\"voltage_V\": 50.0, \"max_charge_current_A\": 25, \"max_discharge_current_A\": 50}"));
		assertEquals(-1250, r.allowedChargePowerW());
		assertEquals(2500, r.allowedDischargePowerW());
	}

	@Test
	void disabledOrMissingLimitsAllowNothing() {
		var disabled = BridgeReading.fromBridgeJson(json("{\"voltage_V\": 50.0, \"max_charge_current_A\": 25, "
				+ "\"max_discharge_current_A\": 50, \"charge_enable\": false, \"discharge_enable\": false}"));
		assertEquals(0, disabled.allowedChargePowerW());
		assertEquals(0, disabled.allowedDischargePowerW());

		var missing = BridgeReading.fromBridgeJson(json("{}"));
		assertEquals(0, missing.allowedChargePowerW());
		assertEquals(0, missing.allowedDischargePowerW());
		assertNull(missing.socPct());
		assertNull(missing.activePowerW());
	}

	@Test
	void negativeCurrentLimitIsTreatedAsZeroNotAsReversedPermission() {
		var r = BridgeReading.fromBridgeJson(json("{\"voltage_V\": 50.0, \"max_charge_current_A\": -10}"));
		assertEquals(0, r.allowedChargePowerW());
	}

	@Test
	void socIsClampedAndNullFieldsAreAbsent() {
		assertEquals(100, BridgeReading.fromBridgeJson(json("{\"soc_pct\": 104}")).socPct());
		assertNull(BridgeReading.fromBridgeJson(json("{\"soc_pct\": null}")).socPct());
	}

	@Test
	void wrongTypesAreRejectedNotGuessed() {
		assertThrows(IllegalArgumentException.class,
				() -> BridgeReading.fromBridgeJson(json("{\"power_W\": \"lots\"}")));
	}

	@Test
	void energySidesAreNonNegativeAndExclusive() {
		assertEquals(500, BridgeReading.chargePower(-500));
		assertEquals(0, BridgeReading.dischargePower(-500));
		assertEquals(0, BridgeReading.chargePower(700));
		assertEquals(700, BridgeReading.dischargePower(700));
		assertNull(BridgeReading.chargePower(null));
		assertNull(BridgeReading.dischargePower(null));
	}

	@Test
	void unavailableAllowsNothing() {
		assertEquals(0, BridgeReading.UNAVAILABLE.allowedChargePowerW());
		assertEquals(0, BridgeReading.UNAVAILABLE.allowedDischargePowerW());
		assertNull(BridgeReading.UNAVAILABLE.activePowerW());
	}
}
