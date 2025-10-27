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

import static java.util.stream.Collectors.averagingDouble;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import org.matsim.contrib.common.timeprofile.TimeProfileCollector;
import org.matsim.contrib.common.timeprofile.TimeProfileCollector.ProfileCalculator;
import org.matsim.contrib.rlev.EvUnits;
import org.matsim.contrib.rlev.fleet.ElectricFleet;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.mobsim.framework.listeners.MobsimListener;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Provider;

public class VehicleTypeAggregatedChargeTimeProfileCollectorProvider implements Provider<MobsimListener> {
	private final ElectricFleet evFleet;
	private final MatsimServices matsimServices;

	@Inject
	public VehicleTypeAggregatedChargeTimeProfileCollectorProvider(ElectricFleet evFleet, MatsimServices matsimServices) {
		this.evFleet = evFleet;
		this.matsimServices = matsimServices;
	}

	@Override
	public MobsimListener get() {
		ProfileCalculator calculator = new ProfileCalculator() {
			private final ImmutableList<String> header = buildHeader();

			@Override
			public ImmutableList<String> getHeader() {
				return header;
			}

			@Override
			public ImmutableMap<String, Double> calcValues() {
				Map<String, Double> averageSocByType = evFleet.getElectricVehicles()
						.values()
						.stream()
						.collect(groupingBy(ev -> ev.getVehicleSpecification().getMatsimVehicle().getType().getId() + "",
									mapping(ev -> EvUnits.J_to_kWh(ev.getBattery().getCharge()), averagingDouble(v -> v))));

				double averageSoc = evFleet.getElectricVehicles()
						.values()
						.stream()
						.mapToDouble(ev -> EvUnits.J_to_kWh(ev.getBattery().getCharge()))
						.average()
						.orElse(Double.NaN);

				Map<String, Double> ordered = new LinkedHashMap<>();
				for (String key : header) {
					if (key.equals("all vehicles")) {
						ordered.put(key, averageSoc);
					} else {
						ordered.put(key, averageSocByType.getOrDefault(key, Double.NaN));
					}
				}
				return ImmutableMap.copyOf(ordered);
			}

			private ImmutableList<String> buildHeader() {
				var vehicleTypes = evFleet.getElectricVehicles()
						.values()
						.stream()
						.map(ev -> ev.getVehicleSpecification().getMatsimVehicle().getType().getId() + "")
						.collect(Collectors.toCollection(LinkedHashSet::new));
				vehicleTypes.add("all vehicles");
				return ImmutableList.copyOf(vehicleTypes);
			}
		};

		return new TimeProfileCollector(calculator, 60, "average_charge_time_profiles", matsimServices);
	}
}
