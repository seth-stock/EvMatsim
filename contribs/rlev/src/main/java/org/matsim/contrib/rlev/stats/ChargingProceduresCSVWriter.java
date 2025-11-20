/* *********************************************************************** *
 * project: org.matsim.*
 * Controler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
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

package org.matsim.contrib.rlev.stats;

import com.google.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.rlev.EvUnits;
import org.matsim.contrib.rlev.charging.ChargingEndEvent;
import org.matsim.contrib.rlev.charging.ChargingEventSequenceCollector;
import org.matsim.contrib.rlev.charging.ChargingStartEvent;
import org.matsim.contrib.rlev.charging.QueuedAtChargerEvent;
import org.matsim.contrib.rlev.charging.QuitQueueAtChargerEvent;
import org.matsim.contrib.rlev.infrastructure.Charger;
import org.matsim.contrib.rlev.infrastructure.ChargerSpecification;
import org.matsim.contrib.rlev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.misc.Time;
import org.matsim.vehicles.Vehicle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;

public final class ChargingProceduresCSVWriter implements IterationEndsListener {

	private static final Logger log = LogManager.getLogger(ChargingProceduresCSVWriter.class);

	@Inject
	ChargingEventSequenceCollector chargingEventSequenceCollector;
	@Inject
	private ChargingInfrastructureSpecification chargingInfrastructureSpecification;

	@Inject ChargingProceduresCSVWriter(){}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {

		try (CSVPrinter csvPrinter = new CSVPrinter(
			Files.newBufferedWriter(Paths.get(event.getServices().getControlerIO().getIterationFilename(event.getIteration(), "chargingStats.csv"))),
			CSVFormat.DEFAULT.withDelimiter(';')
				.withHeader("chargerId", "vehicleId", "linkId",
					"waitStartTime", "waitEndTime", "waitDuration",
					"chargeStartTime", "chargeEndTime", "chargingDuration",
					"energyTransmitted_kWh"))) {

			proccessChargingEventSequences(csvPrinter, chargingEventSequenceCollector.getCompletedSequences());
			proccessChargingEventSequences(csvPrinter, chargingEventSequenceCollector.getOnGoingSequences());

		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	private void proccessChargingEventSequences(CSVPrinter csvPrinter, Collection<ChargingEventSequenceCollector.ChargingSequence> chargingSequences) throws IOException {
		for (ChargingEventSequenceCollector.ChargingSequence sequence : chargingSequences) {
			Optional<QueuedAtChargerEvent> queuedAtCharger = sequence.getQueuedAtCharger();
			Optional<ChargingStartEvent> chargingStart = sequence.getChargingStart();
			Optional<ChargingEndEvent> chargingEnd = sequence.getChargingEnd();
			Optional<QuitQueueAtChargerEvent> quitQueue = sequence.getQuitQueueAtChargerEvent();

			Id<Charger> chargerId;
			Id<Vehicle> vehicleId;
			double waitStartTime = Double.NaN;
			if (queuedAtCharger.isPresent()) {
				chargerId = queuedAtCharger.get().getChargerId();
				vehicleId = queuedAtCharger.get().getVehicleId();
				waitStartTime = queuedAtCharger.get().getTime();
			} else if (chargingStart.isPresent()) {
				chargerId = chargingStart.get().getChargerId();
				vehicleId = chargingStart.get().getVehicleId();
			} else {
				log.warn("Skipping charging sequence without queue or start event: {}", sequence);
				continue;
			}

			ChargerSpecification chargerSpecification = chargingInfrastructureSpecification.getChargerSpecifications().get(chargerId);
			if (chargerSpecification == null) {
				log.warn("No charger specification found for id {}. Skipping sequence {}", chargerId, sequence);
				continue;
			}
			Id<Link> linkId = chargerSpecification.getLinkId();

			double waitEndTime = Double.NaN;
			if (quitQueue.isPresent()) {
				waitEndTime = quitQueue.get().getTime();
			} else if (chargingStart.isPresent()) {
				waitEndTime = chargingStart.get().getTime();
			} else {
				log.debug("No quit queue or charging start event found for sequence {}", sequence);
			}

			double startEnergy = Double.NaN;
			double startTime = Double.NaN;
			if (chargingStart.isPresent()) {
				startEnergy = chargingStart.get().getCharge();
				startTime = chargingStart.get().getTime();
			}

			double endEnergy = Double.NaN;
			double endTime = Double.NaN;
			if (chargingEnd.isPresent()) {
				endEnergy = chargingEnd.get().getCharge();
				endTime = chargingEnd.get().getTime();
			}

			double energyKWh = Double.NaN;
			if (!Double.isNaN(startEnergy) && !Double.isNaN(endEnergy)) {
				double energyTransmitted = endEnergy - startEnergy;
				energyKWh = Math.round(EvUnits.J_to_kWh(energyTransmitted) * 10.) / 10.;
			}

			double waitDuration = (!Double.isNaN(waitStartTime) && !Double.isNaN(waitEndTime)) ? waitEndTime - waitStartTime : Double.NaN;
			double chargeDuration = (!Double.isNaN(startTime) && !Double.isNaN(endTime)) ? endTime - startTime : Double.NaN;

			csvPrinter.printRecord(chargerId, vehicleId, linkId,
				Time.writeTime(waitStartTime), Time.writeTime(waitEndTime), Time.writeTime(waitDuration),
				Time.writeTime(startTime), Time.writeTime(endTime), Time.writeTime(chargeDuration),
				energyKWh);
		}
	}
}
