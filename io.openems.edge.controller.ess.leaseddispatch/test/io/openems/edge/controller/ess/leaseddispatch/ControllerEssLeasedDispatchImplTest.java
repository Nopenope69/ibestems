package io.openems.edge.controller.ess.leaseddispatch;

import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.APPLIED_ACTIVE_POWER;
import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.APPLIED_REACTIVE_POWER;
import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.LEASE_EPOCH;
import static io.openems.edge.controller.ess.leaseddispatch.ControllerEssLeasedDispatch.ChannelId.LEASE_LIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.jsonrpc.base.GenericJsonrpcRequest;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;

class ControllerEssLeasedDispatchImplTest {

	private static final long S = 1_000_000_000L;

	private static GenericJsonrpcRequest req(String method, String holder, Integer p, Integer q) {
		var params = new JsonObject();
		params.addProperty("holder", holder);
		if (p != null) {
			params.addProperty("activePowerW", p);
		}
		if (q != null) {
			params.addProperty("reactivePowerVar", q);
		}
		return new GenericJsonrpcRequest(method, params);
	}

	private static JsonObject result(Object response) {
		return ((GenericJsonrpcResponseSuccess) response).getResult();
	}

	@Test
	void appliesOnlyTheLiveHoldersSetpoints() throws Exception {
		var clock = new AtomicLong(0);
		var sut = new ControllerEssLeasedDispatchImpl();
		sut.setNanoClock(clock::get);
		var ess = new DummyManagedSymmetricEss("ess0");
		new ControllerTest(sut) //
				.addReference("componentManager", new DummyComponentManager().addComponent(ess)) //
				.addComponent(ess) //
				.activate(MyConfig.create() //
						.setId("ctrlLeasedDispatch0") //
						.setEssId("ess0") //
						.setLeaseTimeoutSeconds(10) //
						.build()) //
				.next(new TestCase("no lease yet: nothing applied") //
						.output(LEASE_LIVE, false) //
						.output(APPLIED_ACTIVE_POWER, null)) //
				.next(new TestCase("A granted") //
						.onBeforeProcessImage(() -> {
							var r = result(sut.handleLease(req("leaseDispatch", "A", 3000, 200)));
							assertEquals(true, r.get("granted").getAsBoolean());
							assertEquals(1, r.get("epoch").getAsLong());
						}) //
						.output(LEASE_LIVE, true) //
						.output(LEASE_EPOCH, 1L) //
						.output(APPLIED_ACTIVE_POWER, 3000) //
						.output(APPLIED_REACTIVE_POWER, 200)) //
				.next(new TestCase("B refused; A's setpoint stays") //
						.onBeforeProcessImage(() -> {
							clock.set(2 * S);
							var r = result(sut.handleLease(req("leaseDispatch", "B", -2000, null)));
							assertEquals(false, r.get("granted").getAsBoolean());
							assertEquals("A", r.get("holder").getAsString());
						}) //
						.output(APPLIED_ACTIVE_POWER, 3000)) //
				.next(new TestCase("A silent past the timeout: battery released") //
						.onBeforeProcessImage(() -> clock.set(11 * S)) //
						.output(LEASE_LIVE, false) //
						.output(APPLIED_ACTIVE_POWER, null)) //
				.next(new TestCase("B takes over with a new epoch") //
						.onBeforeProcessImage(() -> {
							var r = result(sut.handleLease(req("leaseDispatch", "B", -2000, null)));
							assertEquals(true, r.get("granted").getAsBoolean());
							assertEquals(2, r.get("epoch").getAsLong());
						}) //
						.output(LEASE_EPOCH, 2L) //
						.output(APPLIED_ACTIVE_POWER, -2000) //
						.output(APPLIED_REACTIVE_POWER, null)) //
				.next(new TestCase("B yields, A is granted at once") //
						.onBeforeProcessImage(() -> {
							clock.set(12 * S);
							assertEquals(true,
									result(sut.handleYield(req("yieldDispatch", "B", null, null))).get("yielded")
											.getAsBoolean());
							assertEquals(true, result(sut.handleLease(req("leaseDispatch", "A", 2500, null)))
									.get("granted").getAsBoolean());
						}) //
						.output(LEASE_EPOCH, 3L) //
						.output(APPLIED_ACTIVE_POWER, 2500)) //
				.next(new TestCase("A releases: nothing applied") //
						.onBeforeProcessImage(() -> assertEquals(true,
								result(sut.handleRelease(req("releaseDispatch", "A", null, null))).get("released")
										.getAsBoolean())) //
						.output(LEASE_LIVE, false) //
						.output(APPLIED_ACTIVE_POWER, null)) //
				.deactivate();
	}

	@Test
	void holderIsRequired() throws Exception {
		var sut = new ControllerEssLeasedDispatchImpl();
		new ControllerTest(sut) //
				.addReference("componentManager", new DummyComponentManager()) //
				.activate(MyConfig.create().setId("ctrl0").setEssId("ess0").build());
		var params = new JsonObject();
		params.addProperty("activePowerW", 100);
		assertThrows(OpenemsException.class,
				() -> sut.handleLease(new GenericJsonrpcRequest("leaseDispatch", params)));
	}
}
