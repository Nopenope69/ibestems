package io.openems.edge.controller.ess.leaseddispatch;

import java.util.Objects;

/**
 * The lease rules (ADR-0005, yield from ADR-0006), free of OpenEMS types so
 * they can be tested on their own. Time is a monotonic nanosecond reading
 * passed in by the caller. The Python fake edge in EMS-ADDONS
 * {@code grid-compliance/tests/test_ha_lease.py} is a transcription of this
 * class; keep the two identical.
 *
 * <p>
 * Not thread-safe on its own: the controller synchronizes every call.
 */
public final class DispatchLease {

	/** Reply to {@code leaseDispatch}. */
	public record Reply(boolean granted, String holder, long epoch, long expiresInMillis) {
	}

	/** What the controller applies this cycle; both null = nothing. */
	public record Setpoints(String holder, Integer activePowerW, Integer reactivePowerVar) {
	}

	private final long ttlNanos;

	private String holder = null;
	private long epoch = 0;
	private long expiresAt = Long.MIN_VALUE;
	private boolean lapsedForever = true; // no holder yet, or released
	private Integer activePowerW = null;
	private Integer reactivePowerVar = null;
	private boolean yielding = false;

	/**
	 * Creates a lease with no holder.
	 *
	 * @param ttlMillis how long a grant or renewal stays live [ms]
	 */
	public DispatchLease(long ttlMillis) {
		if (ttlMillis <= 0) {
			throw new IllegalArgumentException("lease timeout must be positive");
		}
		this.ttlNanos = ttlMillis * 1_000_000L;
	}

	private boolean isLive(long now) {
		return this.holder != null && !this.lapsedForever && now - this.expiresAt < 0;
	}

	/**
	 * A poller asks to hold the lease and sets its setpoints.
	 *
	 * @param requester the poller's holder id
	 * @param p         active power [W], null = apply none
	 * @param q         reactive power [var], null = apply none
	 * @param now       monotonic time [ns]
	 * @return the reply
	 */
	public Reply lease(String requester, Integer p, Integer q, long now) {
		Objects.requireNonNull(requester, "holder");
		if (this.isLive(now) && !requester.equals(this.holder) && !this.yielding) {
			return new Reply(false, this.holder, this.epoch, (this.expiresAt - now) / 1_000_000L);
		}
		if (!requester.equals(this.holder)) {
			this.holder = requester;
			this.epoch++;
		}
		this.yielding = false;
		this.lapsedForever = false;
		this.expiresAt = now + this.ttlNanos;
		this.activePowerW = p;
		this.reactivePowerVar = q;
		return new Reply(true, requester, this.epoch, this.ttlNanos / 1_000_000L);
	}

	/**
	 * The holder announces a planned stop: its setpoints stay applied until the
	 * next request from anyone, which is granted at once.
	 *
	 * @param requester the poller's holder id
	 * @param now       monotonic time [ns]
	 * @return true if the requester held a live lease
	 */
	public boolean yieldLease(String requester, long now) {
		if (!Objects.equals(requester, this.holder) || !this.isLive(now)) {
			return false;
		}
		this.yielding = true;
		return true;
	}

	/**
	 * The holder gives the lease up; nothing is applied until the next grant.
	 *
	 * @param requester the poller's holder id
	 * @return true if the requester was the holder
	 */
	public boolean release(String requester) {
		if (!Objects.equals(requester, this.holder)) {
			return false;
		}
		this.lapsedForever = true;
		this.activePowerW = null;
		this.reactivePowerVar = null;
		return true;
	}

	/**
	 * What to apply this cycle.
	 *
	 * @param now monotonic time [ns]
	 * @return the live holder's setpoints, or all-null when nothing is applied
	 */
	public Setpoints applied(long now) {
		return this.isLive(now) //
				? new Setpoints(this.holder, this.activePowerW, this.reactivePowerVar) //
				: new Setpoints(null, null, null);
	}

	/**
	 * The current epoch; it increments on every change of holder.
	 *
	 * @return the epoch
	 */
	public long epoch() {
		return this.epoch;
	}
}
