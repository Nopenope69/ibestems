package io.openems.edge.controller.ess.leaseddispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DispatchLeaseTest {

	@Test
	void firstRequesterWinsAndOthersAreFenced() {
		var l = new DispatchLease(30_000);
		var a = l.request("ems-a", 10_000, null, 0);
		assertTrue(a.granted());
		assertEquals(1, a.epoch());
		var b = l.request("ems-b", -25_000, 5_000, 1_000);
		assertFalse(b.granted());
		assertEquals("ems-a", b.holder());
		assertEquals(29_000, b.expiresInMillis());
		// the refused setpoint never reaches the battery
		assertEquals(10_000, l.activePowerToApply(1_000));
		assertNull(l.reactivePowerToApply(1_000));
	}

	@Test
	void holderRenewsAndUpdatesSetpoints() {
		var l = new DispatchLease(30_000);
		l.request("ems-a", 10_000, null, 0);
		var r = l.request("ems-a", 4_000, -2_000, 20_000);
		assertTrue(r.granted());
		assertEquals(1, r.epoch());
		assertEquals(4_000, l.activePowerToApply(49_999));
		assertEquals(-2_000, l.reactivePowerToApply(49_999));
	}

	@Test
	void standbyTakesOverOnlyAfterExpiryAndTheEpochAdvances() {
		var l = new DispatchLease(30_000);
		l.request("ems-a", 10_000, null, 0);
		assertFalse(l.request("ems-b", 0, null, 29_999).granted());
		var b = l.request("ems-b", 2_000, null, 30_000);
		assertTrue(b.granted());
		assertEquals(2, b.epoch());
		// the old leader, back from a partition, is now fenced
		assertFalse(l.request("ems-a", 25_000, null, 30_001).granted());
		assertEquals(2_000, l.activePowerToApply(30_001));
	}

	@Test
	void dualFailureReleasesTheBattery() {
		var l = new DispatchLease(30_000);
		l.request("ems-a", 25_000, 3_000, 0);
		assertNull(l.activePowerToApply(30_000));
		assertNull(l.reactivePowerToApply(30_000));
		assertNull(l.holder(30_000));
		assertFalse(l.isLive(30_000));
	}

	@Test
	void onlyTheHolderCanRelease() {
		var l = new DispatchLease(30_000);
		l.request("ems-a", 1_000, null, 0);
		assertFalse(l.release("ems-b"));
		assertTrue(l.release("ems-a"));
		assertNull(l.activePowerToApply(1));
		assertTrue(l.request("ems-b", 0, null, 2).granted());
	}

	@Test
	void sameHolderAfterExpiryKeepsTheEpoch() {
		var l = new DispatchLease(30_000);
		l.request("ems-a", 1_000, null, 0);
		assertEquals(1, l.request("ems-a", 1_000, null, 60_000).epoch());
	}

	@Test
	void rejectsBadInput() {
		assertThrows(IllegalArgumentException.class, () -> new DispatchLease(0));
		assertThrows(IllegalArgumentException.class, () -> new DispatchLease(1).request(" ", 0, null, 0));
	}
}
