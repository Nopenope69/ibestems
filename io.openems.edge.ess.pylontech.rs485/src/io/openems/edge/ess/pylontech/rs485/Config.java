package io.openems.edge.ess.pylontech.rs485;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
		name = "ESS Pylontech RS485 Bridge",
		description = "ESS implementation that reads battery state from the pylon_bridge.py REST API")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID for this component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Bridge URL", description = "Base URL of pylon_bridge.py REST API")
	String bridgeUrl() default "http://localhost:7070/battery";

	@AttributeDefinition(name = "Poll every N cycles", description = "How often to query the bridge (1 cycle ≈ 1 s)")
	int pollCycles() default 5;

	@AttributeDefinition(name = "Battery capacity [Wh]")
	int capacityWh() default 5000;

	@AttributeDefinition(name = "Max apparent power [VA]")
	int maxApparentPower() default 5000;

	String webconsole_configurationFactory_nameHint() default "ESS Pylontech RS485 [{id}]";
}
