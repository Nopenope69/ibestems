package io.openems.edge.controller.ess.leaseddispatch;

import static io.openems.common.channel.PersistencePriority.HIGH;

import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerEssLeasedDispatch extends Controller, OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/** The poller currently holding the lease, or null. */
		HOLDER(Doc.of(OpenemsType.STRING).persistencePriority(HIGH)),
		/** Increments on every change of holder (each takeover). */
		EPOCH(Doc.of(OpenemsType.LONG).persistencePriority(HIGH)),
		/** True while a live lease is being applied. */
		LEASE_ACTIVE(Doc.of(OpenemsType.BOOLEAN).persistencePriority(HIGH)),
		/** Active power applied this cycle (W, positive = discharge), or null. */
		APPLIED_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)),
		/** Reactive power applied this cycle (var, positive = supply), or null. */
		APPLIED_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE_REACTIVE));

		private final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
