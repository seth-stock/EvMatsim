/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2016 by the members listed in the COPYING,
 *                   LICENSE and WARRANTY file.
 * email           : info at matsim dot org
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *   See also COPYING, LICENSE and WARRANTY file
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.rlev.stats;

import org.matsim.contrib.common.timeprofile.TimeProfileCharts.ChartType;
import org.matsim.contrib.common.timeprofile.TimeProfileCollector;
import org.matsim.contrib.common.timeprofile.TimeProfileCollector.ProfileCalculator;
import org.matsim.contrib.rlev.charging.ChargingLogic;
import org.matsim.contrib.rlev.charging.ChargingWithAssignmentLogic;
import org.matsim.contrib.rlev.infrastructure.Charger;
import org.matsim.contrib.rlev.infrastructure.ChargingInfrastructure;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.mobsim.framework.listeners.MobsimListener;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Provider;

public final class ChargerOccupancyTimeProfileCollectorProvider implements Provider<MobsimListener> {
	private final ChargingInfrastructure chargingInfrastructure;
	private final MatsimServices matsimServices;

	@Inject
	ChargerOccupancyTimeProfileCollectorProvider(ChargingInfrastructure chargingInfrastructure, MatsimServices matsimServices) {
		this.chargingInfrastructure = chargingInfrastructure;
		this.matsimServices = matsimServices;
	}

	@Override
	public MobsimListener get() {
		ProfileCalculator calculator = new ProfileCalculator() {
			private final ImmutableList<String> header =
					ImmutableList.of("plugged", "queued", "assigned");

			@Override
			public ImmutableList<String> getHeader() {
				return header;
			}

			@Override
			public ImmutableMap<String, Double> calcValues() {
				int plugged = 0;
				int queued = 0;
				int assigned = 0;
				for (Charger charger : chargingInfrastructure.getChargers().values()) {
					ChargingLogic logic = charger.getLogic();
					plugged += logic.getPluggedVehicles().size();
					queued += logic.getQueuedVehicles().size();
					if (logic instanceof ChargingWithAssignmentLogic assignmentLogic) {
						assigned += assignmentLogic.getAssignedVehicles().size();
					}
				}
				return ImmutableMap.of(
						"plugged", (double) plugged,
						"queued", (double) queued,
						"assigned", (double) assigned);
			}
		};

		var collector = new TimeProfileCollector(calculator, 60, "charger_occupancy_time_profiles", matsimServices);
		if (matsimServices.getConfig().controler().isCreateGraphs()) {
			collector.setChartTypes(ChartType.Line, ChartType.StackedArea);
		} else {
			collector.setChartTypes();
		}
		return collector;
	}
}
