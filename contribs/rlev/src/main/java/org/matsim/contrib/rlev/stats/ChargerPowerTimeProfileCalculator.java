package org.matsim.contrib.rlev.stats;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.rlev.stats.util.TimeDiscretizer;
import org.matsim.contrib.rlev.EvConfigGroup;
import org.matsim.contrib.rlev.EvUnits;
import org.matsim.contrib.rlev.charging.ChargingEndEvent;
import org.matsim.contrib.rlev.charging.ChargingEndEventHandler;
import org.matsim.contrib.rlev.charging.ChargingStartEvent;
import org.matsim.contrib.rlev.charging.ChargingStartEventHandler;
import org.matsim.contrib.rlev.infrastructure.Charger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Inject;

public final class ChargerPowerTimeProfileCalculator implements ChargingStartEventHandler, ChargingEndEventHandler {
	private static final Logger LOG = LogManager.getLogger(ChargerPowerTimeProfileCalculator.class);
	private static final double MIN_DURATION_SECONDS = 1.0;
	private static final int MAX_DETAILED_WARNINGS = 10;

	private final Map<Id<Charger>, double[]> chargerProfiles = new HashMap<>();
	private final Map<Id<Vehicle>, Double> chargingStartTimeMap = new HashMap<>();
	private final Map<Id<Vehicle>, Double> chargingStartEnergyMap = new HashMap<>();
	private long missingStartSkipCount = 0;
	private long shortDurationSkipCount = 0;
	private int missingStartDetailedWarnings = 0;
	private int shortDurationDetailedWarnings = 0;

	private final TimeDiscretizer timeDiscretizer;
	private final double qsimEndTime;

	/**
	 * Calculation of average power output for each charging station for each charging event. Charging stations without any charging events will not
	 * be present in the output file. Implementation does only work when the Qsim end time is defined in the config, i.e., will not work for
	 * extensions drt and taxi or others depending on the ev contrib without a defined Qsim end time.
	 * @author mattiasingelstrom
	 */
	@Inject
	ChargerPowerTimeProfileCalculator(Config config) {
		int chargeTimeStep = ConfigUtils.addOrGetModule(config, EvConfigGroup.class).chargeTimeStep;
		qsimEndTime = ConfigUtils.addOrGetModule(config, QSimConfigGroup.class).getEndTime().orElse(0.0);
		timeDiscretizer = new TimeDiscretizer((int)Math.ceil(qsimEndTime), chargeTimeStep);
	}


	public Map<Id<Charger>, double[]> getChargerProfiles() {
		return chargerProfiles;
	}

	@Override
	public void handleEvent(ChargingStartEvent event) {
		chargingStartTimeMap.put(event.getVehicleId(), event.getTime());
		chargingStartEnergyMap.put(event.getVehicleId(), EvUnits.J_to_kWh(event.getCharge()));
	}

	@Override

	public void handleEvent(ChargingEndEvent event) {
		Double chargingStartTime = chargingStartTimeMap.remove(event.getVehicleId());
		Double chargingStartEnergy = chargingStartEnergyMap.remove(event.getVehicleId());
		if (chargingStartTime == null || chargingStartEnergy == null) {
			recordMissingStart(event);
			return;
		}
		double chargingDurationSeconds = event.getTime() - chargingStartTime;
		if (chargingDurationSeconds <= MIN_DURATION_SECONDS) {
			recordShortDuration(event, chargingDurationSeconds);
			return;
		}
		double chargingTimeIn_h = chargingDurationSeconds / 3600.0;
		double averagePowerIn_kW = (EvUnits.J_to_kWh(event.getCharge()) - chargingStartEnergy) / chargingTimeIn_h;
		increment(averagePowerIn_kW, event.getChargerId(), chargingStartTime, event.getTime());
	}
	private void increment(double averagePower, Id<Charger> chargerId, double chargingStartTime, double chargingEndTime) {

		 //If Qsim end time is undefined in config, qsimEndTime will be 0.0 and will therefore not proceed in calculating the power curves.
		if (chargingStartTime == chargingEndTime || chargingStartTime >= qsimEndTime || qsimEndTime == 0.0) {
			return;
		}
		chargingEndTime = Math.min(chargingEndTime, qsimEndTime);

		int fromIdx = timeDiscretizer.getIdx(chargingStartTime);
		int toIdx = timeDiscretizer.getIdx(chargingEndTime);

		for (int i = fromIdx; i < toIdx; i++) {
			double[] chargingVector = chargerProfiles.computeIfAbsent(chargerId, c -> new double[timeDiscretizer.getIntervalCount()]);
			chargingVector[i] += averagePower;
		}
	}

	public TimeDiscretizer getTimeDiscretizer() {
		return timeDiscretizer;
	}

	private void recordMissingStart(ChargingEndEvent event) {
		missingStartSkipCount++;
		if (missingStartDetailedWarnings < MAX_DETAILED_WARNINGS) {
			LOG.warn("ChargingEndEvent for vehicle {} at charger {} arrived without matching start information. Skipping power profile update.",
					event.getVehicleId(), event.getChargerId());
		} else if (missingStartDetailedWarnings == MAX_DETAILED_WARNINGS) {
			LOG.warn("Further missing ChargingStartEvent warnings suppressed.");
		}
		missingStartDetailedWarnings++;
	}

	private void recordShortDuration(ChargingEndEvent event, double durationSeconds) {
		shortDurationSkipCount++;
		if (shortDurationDetailedWarnings < MAX_DETAILED_WARNINGS) {
			LOG.warn("ChargingEndEvent for vehicle {} at charger {} has duration {}s (<= {}s). Skipping.", event.getVehicleId(), event.getChargerId(),
					Math.round(durationSeconds * 10.0) / 10.0, MIN_DURATION_SECONDS);
		} else if (shortDurationDetailedWarnings == MAX_DETAILED_WARNINGS) {
			LOG.warn("Further short charging duration warnings suppressed.");
		}
		shortDurationDetailedWarnings++;
	}

	@Override
	public void reset(int iteration) {
		if (missingStartSkipCount > 0) {
			LOG.warn("Iteration {}: skipped {} charging end events without matching start information.", iteration, missingStartSkipCount);
		}
		if (shortDurationSkipCount > 0) {
			LOG.warn("Iteration {}: skipped {} charging sessions shorter than {}s.", iteration, shortDurationSkipCount, MIN_DURATION_SECONDS);
		}
		missingStartSkipCount = 0;
		shortDurationSkipCount = 0;
		missingStartDetailedWarnings = 0;
		shortDurationDetailedWarnings = 0;
		chargingStartTimeMap.clear();
		chargingStartEnergyMap.clear();
	}
}
