package io.openems.edge.controller.ess.leaseddispatch;

import java.util.function.LongSupplier;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.LeasedDispatch", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssLeasedDispatchImpl extends AbstractOpenemsComponent
		implements ControllerEssLeasedDispatch, Controller, OpenemsComponent, ComponentJsonApi {

	@Reference
	private ComponentManager componentManager;

	private Config config;
	private DispatchLease lease;
	/** Monotonic clock [ns]; replaceable in tests. Wall-clock steps never extend a lease. */
	private LongSupplier nanoClock = System::nanoTime;

	public ControllerEssLeasedDispatchImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssLeasedDispatch.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.lease = new DispatchLease(config.leaseTimeoutSeconds() * 1000L);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	/* package-private, tests only */
	void setNanoClock(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	@Override
	public void run() throws OpenemsNamedException {
		final DispatchLease.Setpoints sp;
		synchronized (this) {
			sp = this.lease.applied(this.nanoClock.getAsLong());
			this.channel(ControllerEssLeasedDispatch.ChannelId.LEASE_EPOCH).setNextValue(this.lease.epoch());
		}
		final var live = sp.holder() != null;
		this.channel(ControllerEssLeasedDispatch.ChannelId.LEASE_LIVE).setNextValue(live);
		this.channel(ControllerEssLeasedDispatch.ChannelId.APPLIED_ACTIVE_POWER).setNextValue(sp.activePowerW());
		this.channel(ControllerEssLeasedDispatch.ChannelId.APPLIED_REACTIVE_POWER)
				.setNextValue(sp.reactivePowerVar());
		if (!live) {
			return; // lapsed or released: apply nothing, the battery is released
		}
		final var essId = this.config.ess_id();
		if (essId == null || essId.isBlank()) {
			// a clear error instead of a NullPointerException in the component manager
			throw new OpenemsException("ess.id is not configured for " + this.id());
		}
		ManagedSymmetricEss ess = this.componentManager.getComponent(essId);
		if (sp.activePowerW() != null) {
			ess.setActivePowerEqualsWithoutFilter(sp.activePowerW());
		}
		if (sp.reactivePowerVar() != null) {
			ess.setReactivePowerEqualsWithoutFilter(sp.reactivePowerVar());
		}
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest(METHOD_LEASE, call -> this.handleLease(call.getRequest()));
		builder.handleRequest(METHOD_YIELD, call -> this.handleYield(call.getRequest()));
		builder.handleRequest(METHOD_RELEASE, call -> this.handleRelease(call.getRequest()));
	}

	/* package-private for tests */
	synchronized JsonrpcResponse handleLease(JsonrpcRequest request) throws OpenemsException {
		final var params = request.getParams();
		final var r = this.lease.lease(holder(params), optInt(params, "activePowerW"),
				optInt(params, "reactivePowerVar"), this.nanoClock.getAsLong());
		final var result = new JsonObject();
		result.addProperty("granted", r.granted());
		result.addProperty("holder", r.holder());
		result.addProperty("epoch", r.epoch());
		result.addProperty("expiresInMillis", r.expiresInMillis());
		return new GenericJsonrpcResponseSuccess(request.getId(), result);
	}

	synchronized JsonrpcResponse handleYield(JsonrpcRequest request) throws OpenemsException {
		final var result = new JsonObject();
		result.addProperty("yielded", this.lease.yieldLease(holder(request.getParams()), this.nanoClock.getAsLong()));
		return new GenericJsonrpcResponseSuccess(request.getId(), result);
	}

	synchronized JsonrpcResponse handleRelease(JsonrpcRequest request) throws OpenemsException {
		final var result = new JsonObject();
		result.addProperty("released", this.lease.release(holder(request.getParams())));
		return new GenericJsonrpcResponseSuccess(request.getId(), result);
	}

	private static String holder(JsonObject params) throws OpenemsException {
		final JsonElement h = params == null ? null : params.get("holder");
		if (h == null || !h.isJsonPrimitive() || h.getAsString().isBlank()) {
			throw new OpenemsException("leased dispatch: 'holder' is required");
		}
		return h.getAsString();
	}

	private static Integer optInt(JsonObject params, String key) throws OpenemsException {
		final JsonElement e = params.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		try {
			return e.getAsInt();
		} catch (RuntimeException ex) {
			throw new OpenemsException("leased dispatch: '" + key + "' must be an integer");
		}
	}
}
