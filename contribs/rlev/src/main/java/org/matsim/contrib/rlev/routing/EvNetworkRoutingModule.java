/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2015 by the members listed in the COPYING,        *
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
package org.matsim.contrib.rlev.routing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.common.util.StraightLineKnnFinder;
import org.matsim.contrib.rlev.EvConfigGroup;
import org.matsim.contrib.rlev.charging.VehicleChargingHandler;
import org.matsim.contrib.rlev.discharging.AuxEnergyConsumption;
import org.matsim.contrib.rlev.discharging.DriveEnergyConsumption;
import org.matsim.contrib.rlev.fleet.ElectricFleet;
import org.matsim.contrib.rlev.fleet.ElectricFleetSpecification;
import org.matsim.contrib.rlev.fleet.ElectricFleetUtils;
import org.matsim.contrib.rlev.fleet.ElectricVehicle;
import org.matsim.contrib.rlev.fleet.ElectricVehicleSpecification;
import org.matsim.contrib.rlev.infrastructure.ChargerSpecification;
import org.matsim.contrib.rlev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.router.LinkWrapperFacility;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.RoutingRequest;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.facilities.Facility;
import org.matsim.vehicles.Vehicle;
import com.google.common.collect.ImmutableMap;

import java.util.*;
import java.util.stream.Stream;

/**
 * This network Routing module adds stages for re-charging into the Route.
 * This wraps a "computer science" {@link LeastCostPathCalculator}, which routes from a node to another node, into something that
 * routes from a {@link Facility} to another {@link Facility}, as we need in MATSim.
 *
 * @author jfbischoff
 */

final class EvNetworkRoutingModule implements RoutingModule {

	private static final Logger log = LogManager.getLogger(EvNetworkRoutingModule.class);

	private final String mode;

	private final Network network;
	private final RoutingModule delegate;
	private final ImmutableMap<Id<Vehicle>, ElectricVehicle> electricFleet;
	private final ElectricFleetSpecification electricFleetSpecifications;
	private final ChargingInfrastructureSpecification chargingInfrastructureSpecification;
	private final Random random = MatsimRandom.getLocalInstance();
	private final TravelTime travelTime;
	private final DriveEnergyConsumption.Factory driveConsumptionFactory;
	private final AuxEnergyConsumption.Factory auxConsumptionFactory;
	private final String stageActivityModePrefix;
	private final String vehicleSuffix;
	private final EvConfigGroup evConfigGroup;
	private final long numDynamicChargers;
	private final long numStaticChargers;
	private final long numTotalChargers;


	EvNetworkRoutingModule(final String mode, final Network network, RoutingModule delegate,
			ElectricFleet electricFleet, ElectricFleetSpecification electricFleetSpecifications,
			ChargingInfrastructureSpecification chargingInfrastructureSpecification, TravelTime travelTime,
			DriveEnergyConsumption.Factory driveConsumptionFactory, AuxEnergyConsumption.Factory auxConsumptionFactory,
			EvConfigGroup evConfigGroup) {
		this.travelTime = travelTime;
		Gbl.assertNotNull(network);
		this.delegate = delegate;
		this.network = network;
		this.mode = mode;
		this.electricFleet = electricFleet.getElectricVehicles();
		this.electricFleetSpecifications = electricFleetSpecifications;
		this.chargingInfrastructureSpecification = chargingInfrastructureSpecification;
		this.driveConsumptionFactory = driveConsumptionFactory;
		this.auxConsumptionFactory = auxConsumptionFactory;
		stageActivityModePrefix = mode + VehicleChargingHandler.CHARGING_IDENTIFIER;
		this.evConfigGroup = evConfigGroup;
		this.vehicleSuffix = mode.equals(TransportMode.car) ? "" : "_" + mode;
		this.numDynamicChargers = chargingInfrastructureSpecification.getChargerSpecifications()
				.values()
				.stream()
				.filter(spec -> "dynamic".equals(spec.getChargerType()))
				.count();
		this.numTotalChargers = chargingInfrastructureSpecification.getChargerSpecifications().size();
		this.numStaticChargers = this.numTotalChargers - this.numDynamicChargers;
	}

	@Override
	public List<? extends PlanElement> calcRoute(RoutingRequest request) {
		final Facility fromFacility = request.getFromFacility();
		final Facility toFacility = request.getToFacility();
		final double departureTime = request.getDepartureTime();
		final Person person = request.getPerson();

		List<? extends PlanElement> basicRoute = delegate.calcRoute(request);
		Id<Vehicle> evId = Id.create(person.getId() + vehicleSuffix, Vehicle.class);
		if (!electricFleetSpecifications.getVehicleSpecifications().containsKey(evId) || this.numTotalChargers == 0) {
			return basicRoute;
		} else {
			Leg basicLeg = (Leg)basicRoute.get(0);
			ElectricVehicleSpecification evspecs = electricFleetSpecifications.getVehicleSpecifications().get(evId);
			ElectricVehicle ev = electricFleet.get(evId);
			Map<Link, Double> estimatedEnergyConsumption = estimateConsumption(evspecs, basicLeg);
			double estimatedOverallConsumption = estimatedEnergyConsumption.values()
					.stream()
					.mapToDouble(Number::doubleValue)
					.sum();
			double batteryCapacity = evspecs.getBatteryCapacity();
 			double charge = drawConsumptionBudget(ev.getBattery().getCharge(), batteryCapacity);

			double numberOfStops = Math.floor(estimatedOverallConsumption / charge);
			if (numberOfStops < 1) {
				return basicRoute;
			} else {
				List<Link> stopLocations = new ArrayList<>();
				double currentConsumption = 0;
				for (Map.Entry<Link, Double> e : estimatedEnergyConsumption.entrySet()) {
					currentConsumption += e.getValue();
					if (currentConsumption > charge) {
						stopLocations.add(e.getKey());
						currentConsumption = 0;
						charge = drawConsumptionBudget(batteryCapacity, batteryCapacity);
					}
				}
				List<PlanElement> stagedRoute = new ArrayList<>();
				Facility lastFrom = fromFacility;
				double lastArrivaltime = departureTime;
				for (Link stopLocation : stopLocations) {
					StraightLineKnnFinder<Link, ChargerSpecification> straightLineKnnFinder = new StraightLineKnnFinder<>(
							2, Link::getCoord, s -> network.getLinks().get(s.getLinkId()).getCoord());

					Stream<ChargerSpecification> staticCandidates = chargingInfrastructureSpecification.getChargerSpecifications()
							.values()
							.stream()
							.filter(charger -> ev.getChargerTypes().contains(charger.getChargerType())
									&& !"dynamic".equals(charger.getChargerType()));

					List<ChargerSpecification> nearestChargers = straightLineKnnFinder.findNearest(stopLocation, staticCandidates);

					if (nearestChargers.isEmpty()) {
						// fall back to any compatible charger (including dynamic) so we at least keep the plan valid
						Stream<ChargerSpecification> anyCandidates = chargingInfrastructureSpecification.getChargerSpecifications()
								.values()
								.stream()
								.filter(charger -> ev.getChargerTypes().contains(charger.getChargerType()));
						nearestChargers = straightLineKnnFinder.findNearest(stopLocation, anyCandidates);
					}

					if (nearestChargers.isEmpty()) {
						log.warn("No compatible charger found near link {} for vehicle {}. Skipping this charging stop.", stopLocation.getId(), evId);
						continue;
					}

					ChargerSpecification selectedCharger = nearestChargers.get(0);
					Link selectedChargerLink = network.getLinks().get(selectedCharger.getLinkId());
					Facility nexttoFacility = new LinkWrapperFacility(selectedChargerLink);
					if (nexttoFacility.getLinkId().equals(lastFrom.getLinkId())) {
						continue;
					}

					List<? extends PlanElement> routeSegment = delegate.calcRoute(DefaultRoutingRequest.of(lastFrom, nexttoFacility,
							lastArrivaltime, person, request.getAttributes()));

					if (routeSegment.isEmpty()) {
						log.warn("Routing from {} to charger {} produced an empty leg list. Skipping this charging stop.", lastFrom.getLinkId(),
								selectedCharger.getLinkId());
						continue;
					}

					for (PlanElement planElement : routeSegment) {
						if (planElement instanceof Leg) {
							Leg leg = (Leg) planElement;
							lastArrivaltime = leg.getDepartureTime().seconds() + leg.getTravelTime().seconds();
						}
						stagedRoute.add(planElement);
					}

					Activity chargeAct = PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(selectedChargerLink.getCoord(),
							selectedChargerLink.getId(), stageActivityModePrefix);
					chargeAct = PopulationUtils.createActivity(chargeAct);
					double maxPowerEstimate = Math.min(selectedCharger.getPlugPower(), evspecs.getBatteryCapacity() / 3600);
					double estimatedChargingTime = (evspecs.getBatteryCapacity() * 1.5) / maxPowerEstimate;
					chargeAct.setMaximumDuration(Math.max(evConfigGroup.minimumChargeTime, estimatedChargingTime));
					lastArrivaltime += chargeAct.getMaximumDuration().seconds();
					stagedRoute.add(chargeAct);
					lastFrom = nexttoFacility;
				}
				stagedRoute.addAll(delegate.calcRoute(DefaultRoutingRequest.of(lastFrom, toFacility, lastArrivaltime, person, request.getAttributes())));

				return stagedRoute;

			}

		}
	}

	private double drawConsumptionBudget(double availableEnergy, double batteryCapacity) {
		double triggerFraction = 0.2 + random.nextDouble() * 0.15;
		double targetResidual = batteryCapacity * triggerFraction;
		double budget = availableEnergy - targetResidual;
		double minBudget = Math.max(batteryCapacity * 0.05, 1.0);
		return Math.max(budget, minBudget);
	}

	private Map<Link, Double> estimateConsumption(ElectricVehicleSpecification ev, Leg basicLeg) {
		Map<Link, Double> consumptions = new LinkedHashMap<>();
		NetworkRoute route = (NetworkRoute)basicLeg.getRoute();
		List<Link> links = NetworkUtils.getLinks(network, route.getLinkIds());
		ElectricVehicle pseudoVehicle = ElectricFleetUtils.create(ev, driveConsumptionFactory, auxConsumptionFactory,
				v -> charger -> {
					throw new UnsupportedOperationException();
				} );
		DriveEnergyConsumption driveEnergyConsumption = pseudoVehicle.getDriveEnergyConsumption();
		AuxEnergyConsumption auxEnergyConsumption = pseudoVehicle.getAuxEnergyConsumption();
		double linkEnterTime = basicLeg.getDepartureTime().seconds();
		for (Link l : links) {
			double travelT = travelTime.getLinkTravelTime(l, basicLeg.getDepartureTime().seconds(), null, null);

			double consumption = driveEnergyConsumption.calcEnergyConsumption(l, travelT, linkEnterTime)
					+ auxEnergyConsumption.calcEnergyConsumption(basicLeg.getDepartureTime().seconds(), travelT, l.getId());
			// to accomodate for ERS, where energy charge is directly implemented in the consumption model
			consumptions.put(l, consumption);
			linkEnterTime += travelT;
		}
		return consumptions;
	}

	@Override
	public String toString() {
		return "[NetworkRoutingModule: mode=" + this.mode + "]";
	}

}
