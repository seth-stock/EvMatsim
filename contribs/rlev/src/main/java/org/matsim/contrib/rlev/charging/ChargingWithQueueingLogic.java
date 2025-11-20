/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2016 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.rlev.charging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.rlev.fleet.ElectricVehicle;
import org.matsim.contrib.rlev.infrastructure.ChargerSpecification;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.vehicles.Vehicle;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public class ChargingWithQueueingLogic implements ChargingLogic {
	private static final ChargingListener NO_OP_LISTENER = new ChargingListener() {
	};
	private static final Logger LOG = LogManager.getLogger(ChargingWithQueueingLogic.class);
	protected final ChargerSpecification charger;
	private final ChargingStrategy chargingStrategy;
	private final EventsManager eventsManager;

	private final Map<Id<Vehicle>, ElectricVehicle> pluggedVehicles = new LinkedHashMap<>();
	private final Queue<ElectricVehicle> queuedVehicles = new LinkedList<>();
	private final Queue<ElectricVehicle> arrivingVehicles = new LinkedBlockingQueue<>();
	private final Map<Id<Vehicle>, ChargingListener> listeners = new LinkedHashMap<>();

	public ChargingWithQueueingLogic(ChargerSpecification charger, ChargingStrategy chargingStrategy, EventsManager eventsManager) {
		this.chargingStrategy = Objects.requireNonNull(chargingStrategy);
		this.charger = Objects.requireNonNull(charger);
		this.eventsManager = Objects.requireNonNull(eventsManager);
	}

	@Override
	public void chargeVehicles(double chargePeriod, double now) {
		Iterator<ElectricVehicle> evIter = pluggedVehicles.values().iterator();
		while (evIter.hasNext()) {
			ElectricVehicle ev = evIter.next();
			// with fast charging, we charge around 4% of SOC per minute,
			// so when updating SOC every 10 seconds, SOC increases by less then 1%
			double oldCharge = ev.getBattery().getCharge();
			double energy = ev.getChargingPower().calcChargingPower(charger) * chargePeriod;
			double newCharge = Math.min(oldCharge + energy, ev.getBattery().getCapacity());
			ev.getBattery().setCharge(newCharge);
			eventsManager.processEvent(new EnergyChargedEvent(now, charger.getId(), ev.getId(), newCharge - oldCharge, newCharge));

			if (chargingStrategy.isChargingCompleted(ev)) {
				evIter.remove();
				eventsManager.processEvent(new ChargingEndEvent(now, charger.getId(), ev.getId(), ev.getBattery().getCharge()));
				removeListenerOrFallback(ev.getId(), "end charging").notifyChargingEnded(ev, now);
			}
		}

		int queuedToPluggedCount = Math.min(queuedVehicles.size(), charger.getPlugCount() - pluggedVehicles.size());
		for (int i = 0; i < queuedToPluggedCount; i++) {
			ElectricVehicle queuedEv = queuedVehicles.poll();
			if (queuedEv == null) {
				LOG.warn("Charger {} expected queued vehicle to plug at t={} but queue was empty. Skipping.", charger.getId(), now);
				break;
			}
			plugVehicle(queuedEv, now);
		}

		ElectricVehicle arrivingEv;
		while ((arrivingEv = arrivingVehicles.poll()) != null) {
			if (pluggedVehicles.size() < charger.getPlugCount()) {
				plugVehicle(arrivingEv, now);
			} else {
				queueVehicle(arrivingEv, now);
			}
		}
	}

	@Override
	public void addVehicle(ElectricVehicle ev, double now) {
		addVehicle(ev, new ChargingListener() {
		}, now);
	}

	@Override
	public void addVehicle(ElectricVehicle ev, ChargingListener chargingListener, double now) {
		ChargingListener effectiveListener = chargingListener != null ? chargingListener : NO_OP_LISTENER;
		if (listeners.containsKey(ev.getId())) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Vehicle {} already managed by charger {}. Ignoring duplicate add request at t={}.",
						ev.getId(), charger.getId(), now);
			}
			return;
		}
		listeners.put(ev.getId(), effectiveListener);
		arrivingVehicles.add(ev);
	}

	@Override
	public void removeVehicle(ElectricVehicle ev, double now) {
		if (pluggedVehicles.remove(ev.getId()) != null) {// successfully removed
			eventsManager.processEvent(new ChargingEndEvent(now, charger.getId(), ev.getId(), ev.getBattery().getCharge()));
			removeListenerOrFallback(ev.getId(), "end charging (forced)").notifyChargingEnded(ev, now);

			if (!queuedVehicles.isEmpty()) {
				ElectricVehicle queuedEv = queuedVehicles.poll();
				if (queuedEv != null) {
					plugVehicle(queuedEv, now);
				}
			}
		} else if (queuedVehicles.remove(ev)) {
			eventsManager.processEvent(new QuitQueueAtChargerEvent(now, charger.getId(), ev.getId()));
			listeners.remove(ev.getId());
		} else if (arrivingVehicles.remove(ev)) {
			ChargingListener listener = listeners.remove(ev.getId());
			if (listener != null) {
				LOG.debug("Vehicle {} removed from arriving queue of charger {} before being queued/plugged (t={}).",
						ev.getId(), charger.getId(), now);
			}
		} else {
			ChargingListener listener = listeners.remove(ev.getId());
			if (listener != null) {
				LOG.warn("Vehicle {} requested removal from charger {} but was not queued or plugged anymore (t={}). "
						+ "Treating as already handled.", ev.getId(), charger.getId(), now);
			} else {
				LOG.warn("Vehicle {} requested removal from charger {} but no listener or state entry was found (t={}). "
						+ "This can happen when re-registering after a completed charge.", ev.getId(), charger.getId(),
						now);
			}
		}
	}

	private void queueVehicle(ElectricVehicle ev, double now) {
		queuedVehicles.add(ev);
		eventsManager.processEvent(new QueuedAtChargerEvent(now, charger.getId(), ev.getId()));
		getListenerOrFallback(ev.getId(), "queue").notifyVehicleQueued(ev, now);
	}

	private void plugVehicle(ElectricVehicle ev, double now) {
		if (pluggedVehicles.put(ev.getId(), ev) != null) {
			throw new IllegalArgumentException();
		}
		eventsManager.processEvent(new ChargingStartEvent(now, charger.getId(), ev.getId(), ev.getBattery().getCharge()));
		getListenerOrFallback(ev.getId(), "start charging").notifyChargingStarted(ev, now);
	}

	private ChargingListener getListenerOrFallback(Id<Vehicle> vehicleId, String context) {
		ChargingListener listener = listeners.get(vehicleId);
		if (listener == null) {
			LOG.warn("Vehicle {} has no registered charging listener when attempting to {} at charger {}. Using NO-OP listener.",
					vehicleId, context, charger.getId());
			return NO_OP_LISTENER;
		}
		return listener;
	}

	private ChargingListener removeListenerOrFallback(Id<Vehicle> vehicleId, String context) {
		ChargingListener listener = listeners.remove(vehicleId);
		if (listener == null) {
			LOG.warn("Vehicle {} has no registered charging listener when attempting to {} at charger {}. Using NO-OP listener.",
					vehicleId, context, charger.getId());
			return NO_OP_LISTENER;
		}
		return listener;
	}

	private final Collection<ElectricVehicle> unmodifiablePluggedVehicles = Collections.unmodifiableCollection(pluggedVehicles.values());

	@Override
	public Collection<ElectricVehicle> getPluggedVehicles() {
		return unmodifiablePluggedVehicles;
	}

	private final Collection<ElectricVehicle> unmodifiableQueuedVehicles = Collections.unmodifiableCollection(queuedVehicles);

	@Override
	public Collection<ElectricVehicle> getQueuedVehicles() {
		return unmodifiableQueuedVehicles;
	}

	@Override
	public ChargingStrategy getChargingStrategy() {
		return chargingStrategy;
	}
}
