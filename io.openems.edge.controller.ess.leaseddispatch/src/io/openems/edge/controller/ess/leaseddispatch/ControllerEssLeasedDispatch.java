package io.openems.edge.controller.ess.leaseddispatch;

import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerEssLeasedDispatch extends Controller, OpenemsComponent {

	public static final String METHOD_LEASE = "leaseDispatch";
	public static final String METHOD_YIELD = "yieldDispatch";
	public static final String METHOD_RELEASE = "releaseDispatch";

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/** A lease is live and its setpoints are applied this cycle. */
		LEASE_LIVE(Doc.of(OpenemsType.BOOLEAN) //
				.persistencePriority(PersistencePriority.HIGH)),
		/** Increments on every change of holder. */
		LEASE_EPOCH(Doc.of(OpenemsType.LONG) //
				.persistencePriority(PersistencePriority.HIGH)),
		/** Active power applied for the holder this cycle. */
		APPLIED_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)),
		/** Reactive power applied for the holder this cycle. */
		APPLIED_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
