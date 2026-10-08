package io.openems.edge.controller.ess.leaseddispatch;

/**
 * The lease and fencing rules for redundant dispatch pollers, with no OpenEMS
 * or OSGi dependency so they can be tested on their own (EMS-ADDONS SOR
 * clause (j), 2026-10-08).
 *
 * <p>
 * Two EMS pollers (hot standby) both run every tick. Only the one holding the
 * lease may move the battery, and the battery is moved BY THIS EDGE, from the
 * setpoint carried in the holder's last lease request — so a poller that has
 * lost the lease cannot command the plant even if it still believes it is the
 * leader (fencing at the actuator, not in the poller).
 *
 * <ul>
 * <li>A request from the current holder, or from anyone once the lease has
 * expired, is granted and renews the lease for {@code ttlMillis}.
 * <li>A request from anyone else while the lease is live is refused; its
 * setpoint is ignored.
 * <li>A change of holder increments the epoch, so a log or a UI can see every
 * takeover.
 * <li>When the lease expires no setpoint is applied at all: with both pollers
 * dead the battery is released (fail-safe on dual failure), exactly as the
 * Api.Backend timeout releases it on a single-poller site (ADR-0004).
 * </ul>
 *
 * <p>
 * Time is this edge's monotonic clock only; the pollers' clocks never matter.
 * Not thread-safe by itself; the component synchronises access.
 */
public final class DispatchLease {

	/** Result of one lease request. */
	public record Result(boolean granted, String holder, long epoch, long expiresInMillis) {
	}

	private final long ttlMillis;
	private String holder = null;
	private long epoch = 0;
	private long expiresAtMillis = Long.MIN_VALUE;
	private Integer activePowerW = null;
	private Integer reactivePowerVar = null;
	private boolean yielding = false;

	public DispatchLease(long ttlMillis) {
		if (ttlMillis <= 0) {
			throw new IllegalArgumentException("ttlMillis must be positive");
		}
		this.ttlMillis = ttlMillis;
	}

	/**
	 * Request (or renew) the lease and, if granted, set the setpoints the edge
	 * applies until the next renewal or expiry.
	 *
	 * @param requester        the poller's stable identity (non-empty)
	 * @param activePowerW     canonical W, positive = discharge; null = no P
	 * @param reactivePowerVar var, positive = supply; null = no Q
	 * @param nowMillis        this edge's monotonic clock
	 * @return the outcome
	 */
	public Result request(String requester, Integer activePowerW, Integer reactivePowerVar, long nowMillis) {
		if (requester == null || requester.isBlank()) {
			throw new IllegalArgumentException("requester is required");
		}
		final var live = this.holder != null && nowMillis < this.expiresAtMillis;
		if (live && !this.holder.equals(requester) && !this.yielding) {
			return new Result(false, this.holder, this.epoch, this.expiresAtMillis - nowMillis);
		}
		if (!requester.equals(this.holder)) {
			this.holder = requester;
			this.epoch++;
		}
		this.yielding = false; // a holder that renews takes back its yield
		this.expiresAtMillis = nowMillis + this.ttlMillis;
		this.activePowerW = activePowerW;
		this.reactivePowerVar = reactivePowerVar;
		return new Result(true, this.holder, this.epoch, this.ttlMillis);
	}

	/**
	 * Give the lease up early (a clean shutdown). Only the holder can.
	 *
	 * @param requester the poller's identity
	 * @return true if it was the holder
	 */
	/**
	 * The holder hands over WITHOUT a gap: its setpoints stay applied until the
	 * lease would expire anyway, but the next request from any other poller is
	 * granted at once. Used for a planned stop (rolling upgrade), where
	 * {@link #release(String)} would leave the battery idle until the standby's
	 * next tick.
	 *
	 * @param requester the poller asking
	 * @param nowMillis edge monotonic time
	 * @return true if the requester held a live lease
	 */
	public boolean yieldTo(String requester, long nowMillis) {
		if (requester == null || !requester.equals(this.holder) || !this.isLive(nowMillis)) {
			return false;
		}
		this.yielding = true;
		return true;
	}

	/**
	 * Whether the live lease has been yielded.
	 *
	 * @param nowMillis edge monotonic time
	 * @return true while a yielded lease is still live
	 */
	public boolean isYielding(long nowMillis) {
		return this.yielding && this.isLive(nowMillis);
	}

	public boolean release(String requester) {
		if (requester == null || !requester.equals(this.holder)) {
			return false;
		}
		this.expiresAtMillis = Long.MIN_VALUE;
		this.activePowerW = null;
		this.reactivePowerVar = null;
		return true;
	}

	/** The active-power setpoint to apply now, or null (no live lease / none set). */
	public Integer activePowerToApply(long nowMillis) {
		return this.isLive(nowMillis) ? this.activePowerW : null;
	}

	/** The reactive-power setpoint to apply now, or null. */
	public Integer reactivePowerToApply(long nowMillis) {
		return this.isLive(nowMillis) ? this.reactivePowerVar : null;
	}

	public boolean isLive(long nowMillis) {
		return this.holder != null && nowMillis < this.expiresAtMillis;
	}

	/** Current holder, or null if the lease is not live. */
	public String holder(long nowMillis) {
		return this.isLive(nowMillis) ? this.holder : null;
	}

	public long epoch() {
		return this.epoch;
	}
}
