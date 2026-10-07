package io.openems.edge.ess.pylontech.rs485;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.Level;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;

@ProviderType
public interface EssPylontechRs485 extends ManagedSymmetricEss, SymmetricEss, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/**
		 * No valid reading from pylon_bridge.py within the configured staleness
		 * window. While set, SoC and ActivePower are undefined and both allowed
		 * powers are 0, so the Power solver cannot dispatch a battery whose state
		 * we cannot see. Raised at FAULT so it reaches the ESS State and the UI.
		 */
		BRIDGE_COMMUNICATION_FAILED(Doc.of(Level.FAULT) //
				.text("No valid data from pylon_bridge.py within the staleness window")) //
		;

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
