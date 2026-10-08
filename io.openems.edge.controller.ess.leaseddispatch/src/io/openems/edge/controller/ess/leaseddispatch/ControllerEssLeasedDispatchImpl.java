package io.openems.edge.controller.ess.leaseddispatch;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;

/**
 * Leased dispatch for redundant EMS pollers (EMS-ADDONS SOR clause (j),
 * 2026-10-08). The rules are in {@link DispatchLease}; this class is the OSGi
 * wrapper: it serves the lease over componentJsonApi and applies the holder's
 * setpoints every cycle while the lease is live.
 *
 * <p>
 * JSON-RPC (componentJsonApi, componentId = this id):
 * <ul>
 * <li>{@code leaseDispatch {holder, activePowerW?, reactivePowerVar?}} →
 * {@code {granted, holder, epoch, expiresInMillis}}
 * <li>{@code releaseDispatch {holder}} → {@code {released}}
 * </ul>
 *
 * <p>
 * Scheduler order: operator manual controllers (ctrlFixActivePower0,
 * ctrlFixReactivePower0) must run BEFORE this controller, so manual beats
 * automatic — the same rule as ADR-0004.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.LeasedDispatch", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("ess")
public class ControllerEssLeasedDispatchImpl extends AbstractOpenemsComponent
		implements ControllerEssLeasedDispatch, Controller, OpenemsComponent, ComponentJsonApi {

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.ess_id})(enabled=true))")
	private ManagedSymmetricEss ess;

	private DispatchLease lease = new DispatchLease(30_000);

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
		this.applyConfig(config);
	}

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.applyConfig(config);
	}

	private synchronized void applyConfig(Config config) {
		// A reconfiguration drops any lease: both pollers re-acquire within one tick.
		this.lease = new DispatchLease(Math.max(1, config.leaseTimeoutSeconds()) * 1000L);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private static long nowMillis() {
		return System.nanoTime() / 1_000_000L; // monotonic: wall-clock steps cannot extend a lease
	}

	@Override
	public void run() throws OpenemsNamedException {
		final Integer p;
		final Integer q;
		final String holder;
		final long epoch;
		synchronized (this) {
			final var now = nowMillis();
			p = this.lease.activePowerToApply(now);
			q = this.lease.reactivePowerToApply(now);
			holder = this.lease.holder(now);
			epoch = this.lease.epoch();
		}
		this.channel(ControllerEssLeasedDispatch.ChannelId.HOLDER).setNextValue(holder);
		this.channel(ControllerEssLeasedDispatch.ChannelId.EPOCH).setNextValue(epoch);
		this.channel(ControllerEssLeasedDispatch.ChannelId.LEASE_ACTIVE).setNextValue(holder != null);
		this.channel(ControllerEssLeasedDispatch.ChannelId.APPLIED_ACTIVE_POWER).setNextValue(p);
		this.channel(ControllerEssLeasedDispatch.ChannelId.APPLIED_REACTIVE_POWER).setNextValue(q);
		if (p != null) {
			this.ess.setActivePowerEqualsWithoutFilter(p);
		}
		if (q != null) {
			this.ess.setReactivePowerEqualsWithoutFilter(q);
		}
	}

	@Override
	public void buildJsonApiRoutes(JsonApiBuilder builder) {
		builder.handleRequest("leaseDispatch", call -> {
			final var params = call.getRequest().getParams();
			final var holder = requiredString(params, "holder");
			final var p = optionalInt(params, "activePowerW");
			final var q = optionalInt(params, "reactivePowerVar");
			final DispatchLease.Result r;
			synchronized (this) {
				r = this.lease.request(holder, p, q, nowMillis());
			}
			final var result = new JsonObject();
			result.addProperty("granted", r.granted());
			result.addProperty("holder", r.holder());
			result.addProperty("epoch", r.epoch());
			result.addProperty("expiresInMillis", r.expiresInMillis());
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
		builder.handleRequest("releaseDispatch", call -> {
			final var holder = requiredString(call.getRequest().getParams(), "holder");
			final boolean released;
			synchronized (this) {
				released = this.lease.release(holder);
			}
			final var result = new JsonObject();
			result.addProperty("released", released);
			return new GenericJsonrpcResponseSuccess(call.getRequest().getId(), result);
		});
	}

	private static String requiredString(JsonObject params, String key) throws OpenemsException {
		final JsonElement e = params.get(key);
		if (e == null || !e.isJsonPrimitive() || e.getAsString().isBlank()) {
			throw new OpenemsException("leaseDispatch: '" + key + "' is required");
		}
		return e.getAsString();
	}

	private static Integer optionalInt(JsonObject params, String key) throws OpenemsException {
		final JsonElement e = params.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		try {
			return e.getAsInt();
		} catch (RuntimeException ex) {
			throw new OpenemsException("leaseDispatch: '" + key + "' must be an integer");
		}
	}
}
