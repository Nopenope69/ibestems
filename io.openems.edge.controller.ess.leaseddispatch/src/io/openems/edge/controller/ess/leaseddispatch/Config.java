package io.openems.edge.controller.ess.leaseddispatch;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Ess Leased Dispatch", //
		description = "Applies the setpoint of whichever redundant EMS poller holds the dispatch lease; "
				+ "refuses the others and releases the battery when the lease lapses.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlLeasedDispatch0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "ID of the ESS the lease holder dispatches.")
	String ess_id() default "ess0";

	@AttributeDefinition(name = "Lease timeout [s]", description = "The holder must renew within this time, "
			+ "or its setpoint stops being applied and a standby poller may take over. "
			+ "At least twice the poll interval.")
	int leaseTimeoutSeconds() default 30;

	String webconsole_configurationFactory_nameHint() default "Controller Ess Leased Dispatch [{id}]";
}
