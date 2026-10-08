package io.openems.edge.controller.ess.leaseddispatch;

import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.APPLIED_ACTIVE_POWER;
import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.APPLIED_REACTIVE_POWER;
import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.HOLDER;
import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.LEASE_ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponse;
import io.openems.edge.common.jsonapi.Call;
import io.openems.edge.common.jsonapi.JsonApiBuilder;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;

class ControllerEssLeasedDispatchImplTest {

	private static JsonObject call(JsonApiBuilder routes, String method, JsonObject params) {
		final var call = new Call<JsonrpcRequest, JsonrpcResponse>(new GenericJsonrpcRequest(method, params));
		routes.handle(call);
		return call.getResponse().toJsonObject().getAsJsonObject("result");
	}

	private static JsonObject lease(String holder, Integer p, Integer q) {
		final var params = new JsonObject();
		params.addProperty("holder", holder);
		if (p != null) {
			params.addProperty("activePowerW", p);
		}
		if (q != null) {
			params.addProperty("reactivePowerVar", q);
		}
		return params;
	}

	@Test
	void testHolderIsAppliedAndRivalRefused() throws Exception {
		final var sut = new ControllerEssLeasedDispatchImpl();
		final var routes = new JsonApiBuilder();
		final var test = new ControllerTest(sut) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0")) //
				.activate(MyConfig.create() //
						.setId("ctrlLeasedDispatch0") //
						.setEssId("ess0") //
						.setLeaseTimeoutSeconds(30) //
						.build());
		sut.buildJsonApiRoutes(routes);

		// No lease yet: nothing applied.
		test.next(new TestCase() //
				.output(LEASE_ACTIVE, false) //
				.output(APPLIED_ACTIVE_POWER, null));

		final var a = call(routes, "leaseDispatch", lease("poller-a", 5000, -1200));
		assertTrue(a.get("granted").getAsBoolean());
		assertEquals("poller-a", a.get("holder").getAsString());

		// The rival's setpoint is refused and never applied.
		final var b = call(routes, "leaseDispatch", lease("poller-b", -9000, null));
		assertFalse(b.get("granted").getAsBoolean());
		assertEquals("poller-a", b.get("holder").getAsString());

		test.next(new TestCase() //
				.output(LEASE_ACTIVE, true) //
				.output(HOLDER, "poller-a") //
				.output(APPLIED_ACTIVE_POWER, 5000) //
				.output(APPLIED_REACTIVE_POWER, -1200));

		// Holder releases: battery released, rival may now take over.
		assertTrue(call(routes, "releaseDispatch", lease("poller-a", null, null)).get("released").getAsBoolean());
		test.next(new TestCase() //
				.output(LEASE_ACTIVE, false) //
				.output(APPLIED_ACTIVE_POWER, null));
		final var b2 = call(routes, "leaseDispatch", lease("poller-b", -9000, null));
		assertTrue(b2.get("granted").getAsBoolean());
		test.next(new TestCase() //
				.output(HOLDER, "poller-b") //
				.output(APPLIED_ACTIVE_POWER, -9000));
		test.deactivate();
	}

	@Test
	void testYieldHandsOverWithoutAGap() throws Exception {
		final var sut = new ControllerEssLeasedDispatchImpl();
		final var routes = new JsonApiBuilder();
		final var test = new ControllerTest(sut) //
				.addReference("ess", new DummyManagedSymmetricEss("ess0")) //
				.activate(MyConfig.create() //
						.setId("ctrlLeasedDispatch0") //
						.setEssId("ess0") //
						.setLeaseTimeoutSeconds(30) //
						.build());
		sut.buildJsonApiRoutes(routes);
		assertTrue(call(routes, "leaseDispatch", lease("poller-a", 5000, null)).get("granted").getAsBoolean());
		assertTrue(call(routes, "yieldDispatch", lease("poller-a", null, null)).get("yielded").getAsBoolean());
		// Yielded but still applied: the battery is never left uncommanded.
		test.next(new TestCase() //
				.output(LEASE_ACTIVE, true) //
				.output(APPLIED_ACTIVE_POWER, 5000));
		assertTrue(call(routes, "leaseDispatch", lease("poller-b", 4800, null)).get("granted").getAsBoolean());
		test.next(new TestCase() //
				.output(HOLDER, "poller-b") //
				.output(APPLIED_ACTIVE_POWER, 4800));
		test.deactivate();
	}
}
