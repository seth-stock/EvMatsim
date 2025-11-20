/*
 * *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2021 by the members listed in the COPYING,        *
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
 * *********************************************************************** *
 */

package org.matsim.contrib.rlev.charging;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import com.google.inject.Inject;
import org.matsim.contrib.rlev.infrastructure.Charger;
import org.matsim.contrib.rlev.infrastructure.ChargerSpecification;
import org.matsim.contrib.rlev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.vehicles.Vehicle;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Michal Maciejewski (michalm)
 */
public class ChargingEventSequenceCollector
		implements QueuedAtChargerEventHandler, QuitQueueAtChargerEventHandler, ChargingStartEventHandler, ChargingEndEventHandler {

	private static final Logger LOG = LogManager.getLogger(ChargingEventSequenceCollector.class);

	public static class ChargingSequence {
		public enum ChargerMode {
			STATIC, DYNAMIC
		}

		@Nullable //null if no queueing occurred
		private final QueuedAtChargerEvent queuedAtCharger;
		@Nullable //null if queue was never quit early, i.e. if chargingStartEvent != null;
		private QuitQueueAtChargerEvent quitQueueEvent;
		@Nullable //null if queue was quit early, i.e. if QuitQueueAtChargerEvent != null;
		private ChargingStartEvent chargingStartEvent;
		@Nullable //null if quitQueueEvent != null OR if charging was never completed
		private ChargingEndEvent chargingEndEvent;

		private final ChargerMode chargerMode;
		private long dynamicSequenceIndex = -1;
		private double dynamicStartTime = Double.NaN;
		private double dynamicStartCharge = Double.NaN;
		private double dynamicLaneOffset = Double.NaN;

		public ChargingSequence(@Nullable QueuedAtChargerEvent queuedAtCharger, ChargerMode chargerMode) {
			this.queuedAtCharger = queuedAtCharger;
			this.chargerMode = chargerMode;
		}

		public ChargerMode getChargerMode() {
			return chargerMode;
		}

		public boolean isDynamic() {
			return chargerMode == ChargerMode.DYNAMIC;
		}

		void markDynamicOrdering(double laneOffset, double startTime, double startCharge, long ordinal) {
			this.dynamicLaneOffset = laneOffset;
			this.dynamicStartTime = startTime;
			this.dynamicStartCharge = startCharge;
			this.dynamicSequenceIndex = ordinal;
		}

		public long getDynamicSequenceIndex() {
			return dynamicSequenceIndex;
		}

		public double getDynamicStartTime() {
			return dynamicStartTime;
		}

		public double getDynamicStartCharge() {
			return dynamicStartCharge;
		}

		public double getDynamicLaneOffset() {
			return dynamicLaneOffset;
		}

		@Nullable
		public Optional<QueuedAtChargerEvent> getQueuedAtCharger() {
			return Optional.ofNullable(queuedAtCharger);
		}

		@Nullable
		public Optional<QuitQueueAtChargerEvent> getQuitQueueAtChargerEvent() {
			return Optional.ofNullable(quitQueueEvent);
		}

		@Nullable
		public Optional<ChargingStartEvent> getChargingStart() {
			return Optional.ofNullable(chargingStartEvent);
		}

		@Nullable
		public Optional<ChargingEndEvent> getChargingEnd() {
			return Optional.ofNullable(chargingEndEvent);
		}
	}

	private final Map<Id<Vehicle>, ChargingSequence> ongoingSequences = new IdMap<>(Vehicle.class);
	private final List<ChargingSequence> completedSequences = new ArrayList<>();
	private final Map<Id<Charger>, Long> dynamicSequenceOrdinals = new IdMap<>(Charger.class);

	private final ChargingInfrastructureSpecification chargingInfrastructureSpecification;

	@Inject
	ChargingEventSequenceCollector(ChargingInfrastructureSpecification chargingInfrastructureSpecification) {
		this.chargingInfrastructureSpecification = chargingInfrastructureSpecification;
	}

	public List<ChargingSequence> getCompletedSequences() {
		return Collections.unmodifiableList(completedSequences);
	}

	public List<ChargingSequence> getCompletedDynamicSequences() {
		return completedSequences.stream().filter(ChargingSequence::isDynamic).collect(Collectors.toUnmodifiableList());
	}

	public List<ChargingSequence> getCompletedStaticSequences() {
		return completedSequences.stream().filter(sequence -> !sequence.isDynamic()).collect(Collectors.toUnmodifiableList());
	}

	public Set<ChargingSequence> getOnGoingSequences() {
		return ongoingSequences.values().stream().collect(Collectors.toUnmodifiableSet());
	}

	public Set<ChargingSequence> getOnGoingDynamicSequences() {
		return ongoingSequences.values().stream().filter(ChargingSequence::isDynamic).collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public void handleEvent(QueuedAtChargerEvent event) {
		var chargerMode = resolveMode(event.getChargerId());
		if (chargerMode == ChargingSequence.ChargerMode.DYNAMIC) {
			LOG.debug("Ignoring QueuedAtChargerEvent for dynamic charger {} and vehicle {}", event.getChargerId(), event.getVehicleId());
			return;
		}
		var previous = ongoingSequences.put(event.getVehicleId(), new ChargingSequence(event, chargerMode));
		if (previous != null) {
			if (previous.quitQueueEvent == null) {
				var chargerId = chargerIdOf(previous).orElse(event.getChargerId());
				previous.quitQueueEvent = new QuitQueueAtChargerEvent(event.getTime(), chargerId, event.getVehicleId());
			}
			LOG.warn("Vehicle {} queued at charger {} while previous sequence (probably at charger {}) was still open. Completing previous sequence.",
					event.getVehicleId(), event.getChargerId(), chargerIdOf(previous).orElse(null));
			completedSequences.add(previous);
		}
	}

	@Override
	public void handleEvent(QuitQueueAtChargerEvent event) {
		var chargerMode = resolveMode(event.getChargerId());
		if (chargerMode == ChargingSequence.ChargerMode.DYNAMIC) {
			LOG.debug("Ignoring QuitQueueAtChargerEvent for dynamic charger {} and vehicle {}", event.getChargerId(), event.getVehicleId());
			return;
		}
		var sequence = ongoingSequences.remove(event.getVehicleId());
		if (sequence == null) {
			LOG.warn("QuitQueueAtChargerEvent for vehicle {} at charger {} arrived without an active sequence. Ignoring.", event.getVehicleId(),
					event.getChargerId());
			return;
		}
		if (sequence.queuedAtCharger == null) {
			LOG.warn("QuitQueueAtChargerEvent for vehicle {} at charger {} arrived but no queue time was recorded.", event.getVehicleId(),
					event.getChargerId());
		}
		if (sequence.chargingStartEvent != null) {
			LOG.warn("QuitQueueAtChargerEvent for vehicle {} at charger {} arrived after charging started. Completing sequence anyway.",
					event.getVehicleId(), event.getChargerId());
		}
		sequence.quitQueueEvent = event;
		completedSequences.add(sequence);
	}

	@Override
	public void handleEvent(ChargingStartEvent event) {
		var chargerMode = resolveMode(event.getChargerId());
		var sequence = ongoingSequences.get(event.getVehicleId());
		if (sequence == null) {
			sequence = new ChargingSequence(null, chargerMode);
			ongoingSequences.put(event.getVehicleId(), sequence);
		} else if (sequence.chargingStartEvent != null) {
			LOG.warn("Vehicle {} received duplicate ChargingStartEvent at charger {}. Completing previous sequence before starting new one.", event.getVehicleId(),
					event.getChargerId());
			completeSequence(sequence);
			sequence = new ChargingSequence(null, chargerMode);
			ongoingSequences.put(event.getVehicleId(), sequence);
		} else if (sequence.getChargerMode() != chargerMode) {
			LOG.warn("Vehicle {} started charging at charger {} with mode {} but existing sequence is {}. Keeping original assignment.",
					event.getVehicleId(), event.getChargerId(), chargerMode, sequence.getChargerMode());
		}
		sequence.chargingStartEvent = event;
		if (sequence.isDynamic()) {
			long ordinal = nextDynamicOrdinal(event.getChargerId());
			sequence.markDynamicOrdering(ordinal, event.getTime(), event.getCharge(), ordinal);
		}
	}

	@Override
	public void handleEvent(ChargingEndEvent event) {
		var chargerMode = resolveMode(event.getChargerId());
		var sequence = ongoingSequences.remove(event.getVehicleId());
		if (sequence == null) {
			LOG.warn("ChargingEndEvent for vehicle {} at charger {} arrived without an active sequence. Creating synthetic sequence.",
					event.getVehicleId(), event.getChargerId());
			sequence = new ChargingSequence(null, chargerMode);
		} else if (sequence.getChargerMode() != chargerMode) {
			LOG.warn("ChargingEndEvent for vehicle {} at charger {} has mode {} but active sequence mode was {}. Continuing with recorded sequence.",
					event.getVehicleId(), event.getChargerId(), chargerMode, sequence.getChargerMode());
		}
		sequence.chargingEndEvent = event;
		completeSequence(sequence);
	}

	private void completeSequence(ChargingSequence sequence) {
		completedSequences.add(sequence);
	}

	@Override
	public void reset(int iteration) {
		ongoingSequences.clear();
		completedSequences.clear();
		dynamicSequenceOrdinals.clear();
	}

	private ChargingSequence.ChargerMode resolveMode(Id<Charger> chargerId) {
		if (chargerId == null) {
			return ChargingSequence.ChargerMode.STATIC;
		}
		ChargerSpecification specification = chargingInfrastructureSpecification.getChargerSpecifications().get(chargerId);
		if (specification == null) {
			LOG.warn("Unknown charger {} in sequence collector. Falling back to static mode.", chargerId);
			return ChargingSequence.ChargerMode.STATIC;
		}
		return "dynamic".equalsIgnoreCase(specification.getChargerType()) ? ChargingSequence.ChargerMode.DYNAMIC : ChargingSequence.ChargerMode.STATIC;
	}

	private long nextDynamicOrdinal(Id<Charger> chargerId) {
		return dynamicSequenceOrdinals.merge(chargerId, 1L, Long::sum);
	}

	private Optional<Id<Charger>> chargerIdOf(ChargingSequence sequence) {
		if (sequence.chargingStartEvent != null) {
			return Optional.of(sequence.chargingStartEvent.getChargerId());
		}
		if (sequence.queuedAtCharger != null) {
			return Optional.of(sequence.queuedAtCharger.getChargerId());
		}
		if (sequence.chargingEndEvent != null) {
			return Optional.of(sequence.chargingEndEvent.getChargerId());
		}
		if (sequence.quitQueueEvent != null) {
			return Optional.of(sequence.quitQueueEvent.getChargerId());
		}
		return Optional.empty();
	}
}
