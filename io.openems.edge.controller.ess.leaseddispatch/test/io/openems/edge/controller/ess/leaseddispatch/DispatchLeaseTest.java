package io.openems.edge.controller.ess.leaseddispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The ADR-0005/0006 lease rules, the same scenarios as EMS-ADDONS test_ha_lease.py. */
class DispatchLeaseTest {

	private static final long S = 1_000_000_000L; // one second in ns

	@Test
	void firstRequestIsGrantedAndApplied() {
		var l = new DispatchLease(10_000);
		var r = l.lease("A", 3000, 100, 0);
		assertTrue(r.granted());
		assertEquals("A", r.holder());
		assertEquals(1, r.epoch());
		assertEquals(10_000, r.expiresInMillis());
		assertEquals(Integer.valueOf(3000), l.applied(1 * S).activePowerW());
		assertEquals(Integer.valueOf(100), l.applied(1 * S).reactivePowerVar());
	}

	@Test
	void otherPollerIsRefusedAndItsSetpointsAreDropped() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		var r = l.lease("B", -2000, null, 2 * S);
		assertFalse(r.granted());
		assertEquals("A", r.holder());
		assertEquals(8_000, r.expiresInMillis());
		assertEquals(Integer.valueOf(3000), l.applied(3 * S).activePowerW());
		assertEquals("A", l.applied(3 * S).holder());
	}

	@Test
	void renewalExtendsAndReplacesSetpointsWithoutNewEpoch() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		var r = l.lease("A", 1500, null, 9 * S);
		assertTrue(r.granted());
		assertEquals(1, r.epoch());
		assertEquals(Integer.valueOf(1500), l.applied(18 * S).activePowerW()); // renewed at 9 s, live until 19 s
	}

	@Test
	void lapsedLeaseFailsOverToStandbyWithNewEpoch() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		assertFalse(l.lease("B", -2000, null, 9 * S).granted());
		var r = l.lease("B", -2000, null, 10 * S); // exactly at expiry: lapsed
		assertTrue(r.granted());
		assertEquals(2, r.epoch());
		assertEquals(Integer.valueOf(-2000), l.applied(11 * S).activePowerW());
		var back = l.lease("A", 3000, null, 12 * S); // deposed leader returns
		assertFalse(back.granted());
		assertEquals("B", back.holder());
	}

	@Test
	void dualFailureReleasesTheBattery() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, 500, 0);
		var sp = l.applied(10 * S);
		assertNull(sp.holder());
		assertNull(sp.activePowerW());
		assertNull(sp.reactivePowerVar());
	}

	@Test
	void yieldHandsOverAtOnceAndKeepsSetpointUntilThen() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		assertFalse(l.yieldLease("B", 1 * S)); // only the holder may yield
		assertTrue(l.yieldLease("A", 1 * S));
		assertEquals(Integer.valueOf(3000), l.applied(2 * S).activePowerW()); // still applied: no gap
		var r = l.lease("B", 2500, null, 2 * S); // granted at once, well before expiry
		assertTrue(r.granted());
		assertEquals(2, r.epoch());
		assertFalse(l.lease("A", 3000, null, 3 * S).granted()); // yielding ended with the grant
	}

	@Test
	void yieldOfALapsedLeaseIsRefused() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		assertFalse(l.yieldLease("A", 11 * S));
	}

	@Test
	void releaseStopsApplyingAndLetsAnyoneIn() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		assertFalse(l.release("B"));
		assertTrue(l.release("A"));
		assertNull(l.applied(1 * S).holder());
		var r = l.lease("B", 1000, null, 1 * S);
		assertTrue(r.granted());
		assertEquals(2, r.epoch());
	}

	@Test
	void sameHolderReacquiringAfterLapseKeepsEpoch() {
		var l = new DispatchLease(10_000);
		l.lease("A", 3000, null, 0);
		var r = l.lease("A", 3000, null, 20 * S);
		assertTrue(r.granted());
		assertEquals(1, r.epoch());
	}

	@Test
	void nullSetpointMeansApplyNothingForIt() {
		var l = new DispatchLease(10_000);
		l.lease("A", null, -400, 0);
		assertNull(l.applied(1 * S).activePowerW());
		assertEquals(Integer.valueOf(-400), l.applied(1 * S).reactivePowerVar());
	}

	@Test
	void monotonicClockWrapIsHandled() {
		var l = new DispatchLease(10_000);
		long start = Long.MAX_VALUE - 5 * S; // nanoTime may sit anywhere, even near overflow
		l.lease("A", 3000, null, start);
		assertEquals("A", l.applied(start + 9 * S).holder());
		assertNull(l.applied(start + 11 * S).holder());
	}
}
