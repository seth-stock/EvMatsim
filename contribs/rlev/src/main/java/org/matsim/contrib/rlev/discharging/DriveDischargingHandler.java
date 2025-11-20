/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** */

package org.matsim.contrib.rlev.discharging;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.rlev.fleet.ElectricFleet;
import org.matsim.contrib.rlev.fleet.ElectricVehicle;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;
import org.matsim.vehicles.Vehicle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Because in QSim vehicles enter and leave traffic at the end of links, we skip the first link when
 * calculating the drive-related energy consumption. However, the time spent on the first link is used by the time-based
 * idle discharge process (see {@link IdleDischargingHandler}).
 */
public final class DriveDischargingHandler
		implements LinkLeaveEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler,
		MobsimScopeEventHandler, MobsimEngine {
	private static final Logger LOG = LogManager.getLogger(DriveDischargingHandler.class);

	private static class EvDrive {
		private final Id<Vehicle> vehicleId;
		private final ElectricVehicle ev;
		private double movedOverNodeTime;

		EvDrive(Id<Vehicle> vehicleId, ElectricVehicle ev) {
			this.vehicleId = vehicleId;
			this.ev = ev;
			this.movedOverNodeTime = Double.NaN;
		}
		boolean isOnFirstLink() { return Double.isNaN(movedOverNodeTime); }
	}

	private static final class LinkLeaveRecord {
		private final LinkLeaveEvent event;
		private final EvDrive evDrive;

		private LinkLeaveRecord(LinkLeaveEvent event, EvDrive evDrive) {
			this.event = event;
			this.evDrive = evDrive;
		}
	}

	private static final class TrafficLeaveRecord {
		private final VehicleLeavesTrafficEvent event;
		private final EvDrive evDrive;

		private TrafficLeaveRecord(VehicleLeavesTrafficEvent event, EvDrive evDrive) {
			this.event = event;
			this.evDrive = evDrive;
		}
	}

	private final Network network;
	private final EventsManager eventsManager;
	private final Map<Id<Vehicle>, ? extends ElectricVehicle> eVehicles;
	private final Map<Id<Vehicle>, EvDrive> evDrives;

	private final Queue<LinkLeaveRecord> linkLeaveEvents = new ConcurrentLinkedQueue<>();
	private final Queue<TrafficLeaveRecord> trafficLeaveEvents = new ConcurrentLinkedQueue<>();

	private final QSim qsim;

	@Inject
	DriveDischargingHandler(QSim qsim, ElectricFleet data, Network network, EventsManager eventsManager) {
		this.qsim = qsim;
		this.network = network;
		this.eventsManager = eventsManager;
		this.eVehicles = data.getElectricVehicles();
		this.evDrives = new ConcurrentHashMap<>(Math.max(16, eVehicles.size() / 10));
	}

	@Override public void handleEvent(VehicleEntersTrafficEvent event) {
		var ev = eVehicles.get(event.getVehicleId());
		if (ev != null) {
			evDrives.put(event.getVehicleId(), new EvDrive(event.getVehicleId(), ev));
		}
	}

	@Override public void handleEvent(LinkLeaveEvent event) {
		var evDrive = evDrives.get(event.getVehicleId());
		if (evDrive != null) {
			linkLeaveEvents.add(new LinkLeaveRecord(event, evDrive));
		}
	}

	@Override public void handleEvent(VehicleLeavesTrafficEvent event) {
		var evDrive = evDrives.get(event.getVehicleId());
		if (evDrive != null) {
			trafficLeaveEvents.add(new TrafficLeaveRecord(event, evDrive));
		}
	}

	@Override public void onPrepareSim() { }

	@Override public void afterSim() {
		// process remaining events
		doSimStep(this.qsim.getSimTimer().getTimeOfDay());
	}

	@Override public void setInternalInterface(InternalInterface internalInterface) { }

	@Override public void doSimStep(double time) {
		handleLinkLeaveEvents(linkLeaveEvents, time);
		handleLeavesTrafficEvents(trafficLeaveEvents, time);
	}

	private void handleLinkLeaveEvents(Queue<LinkLeaveRecord> queue, double now) {
		while (!queue.isEmpty()) {
			var record = queue.peek();
			var event = record.event;
			if (event.getTime() == now) break; // process only from previous timestep
			dischargeVehicle(record.evDrive, event.getLinkId(), event.getTime(), now);
			record.evDrive.movedOverNodeTime = event.getTime();
			queue.remove();
		}
	}

	private void handleLeavesTrafficEvents(Queue<TrafficLeaveRecord> queue, double now) {
		while (!queue.isEmpty()) {
			var record = queue.peek();
			var event = record.event;
			if (event.getTime() == now) break; // process only from previous timestep
			var evDrive = record.evDrive;
			if (evDrive == null) {
				LOG.error("Vehicle {} left traffic without drive state; telemetry lost for link {} at {}s.",
						event.getVehicleId(), event.getLinkId(), event.getTime());
			} else {
				dischargeVehicle(evDrive, event.getLinkId(), event.getTime(), now);
				evDrives.remove(evDrive.vehicleId);
			}
			queue.remove();
		}
	}

	private void dischargeVehicle(EvDrive evDrive, Id<Link> linkId, double eventTime, double now) {
		if (!evDrive.isOnFirstLink()) { // skip the first link
			Link link = network.getLinks().get(linkId);
			double tt = eventTime - evDrive.movedOverNodeTime;
			ElectricVehicle ev = evDrive.ev;

			double energy =
					ev.getDriveEnergyConsumption().calcEnergyConsumption(link, tt, eventTime - tt)
							+ ev.getAuxEnergyConsumption().calcEnergyConsumption(eventTime - tt, tt, linkId);

			// Energy consumption may be negative on links with negative slope
			ev.getBattery().dischargeEnergy(energy, missing ->
					eventsManager.processEvent(new MissingEnergyEvent(now, ev.getId(), link.getId(), missing)));

			eventsManager.processEvent(new DrivingEnergyConsumptionEvent(now, evDrive.vehicleId, linkId,
					energy, ev.getBattery().getCharge()));
		}
	}
}
