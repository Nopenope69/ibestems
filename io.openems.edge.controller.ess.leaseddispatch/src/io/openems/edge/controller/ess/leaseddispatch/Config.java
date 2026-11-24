package io.openems.edge.controller.ess.leaseddispatch;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Ess Leased Dispatch", //
		description = "Applies the active/reactive power setpoints of the dispatch poller that holds a time-limited lease. "
				+ "Redundant pollers: one commands, the others are refused until the lease lapses.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlLeasedDispatch0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "ID of Ess device.")
	String ess_id();

	@AttributeDefinition(name = "Lease timeout [s]", description = "A lease not renewed within this time lapses; "
			+ "must be at least twice the pollers' interval")
	int leaseTimeoutSeconds() default 30;

	String webconsole_configurationFactory_nameHint() default "Controller Ess Leased Dispatch [{id}]";
}
