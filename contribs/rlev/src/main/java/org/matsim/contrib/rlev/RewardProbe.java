package org.matsim.contrib.rlev;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.rlev.charging.ChargingEndEvent;
import org.matsim.contrib.rlev.charging.ChargingEndEventHandler;
import org.matsim.contrib.rlev.charging.ChargingListener;
import org.matsim.contrib.rlev.charging.ChargingStartEvent;
import org.matsim.contrib.rlev.charging.ChargingStartEventHandler;
import org.matsim.contrib.rlev.charging.EnergyChargedEvent;
import org.matsim.contrib.rlev.charging.EnergyChargedEventHandler;
import org.matsim.contrib.rlev.charging.QueuedAtChargerEvent;
import org.matsim.contrib.rlev.charging.QueuedAtChargerEventHandler;
import org.matsim.contrib.rlev.charging.QuitQueueAtChargerEvent;
import org.matsim.contrib.rlev.charging.QuitQueueAtChargerEventHandler;
import org.matsim.contrib.rlev.fleet.ElectricVehicle;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.vehicles.Vehicle;

/**
 * Minimal in-memory probe:
 *  - Computes average leg duration from PersonDeparture/PersonArrival.
 *  - Keeps a placeholder "charge integral proxy" (0.0 unless you wire real EV events).
 *
 * Bind this with MATSim's AbstractModule:
 *   addControlerListenerBinding().toInstance(probe);
 *   addEventHandlerBinding().toInstance(probe);
 */
public class RewardProbe implements
        PersonDepartureEventHandler,
        PersonArrivalEventHandler,
        EnergyChargedEventHandler,
        ChargingStartEventHandler,
        ChargingEndEventHandler,
        QueuedAtChargerEventHandler,
        QuitQueueAtChargerEventHandler,
        IterationStartsListener,
        IterationEndsListener,
        ChargingListener {

    // Track last departure time per person
    private final Map<Id<Person>, Double> lastDepartureTime = new HashMap<>();
    private final Map<Id<Vehicle>, Double> queueStartTimes = new HashMap<>();
    private final Map<Id<Vehicle>, Double> chargingStartTimes = new HashMap<>();

    private double totalLegDurationSec = 0.0;
    private long   legCount = 0L;

    private double totalEnergyChargedJ = 0.0;
    private double totalQueueSeconds = 0.0;
    private double totalChargingSeconds = 0.0;
    private long queueSamples = 0L;
    private long chargingSamples = 0L;

    public RewardProbe() {}

    // ---------- Event handlers ----------

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        // Store departure time
        lastDepartureTime.put(event.getPersonId(), event.getTime());
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        // On arrival, compute duration if we saw a departure
        Double dep = lastDepartureTime.remove(event.getPersonId());
        if (dep != null) {
            double dt = event.getTime() - dep.doubleValue();
            if (dt > 0) {
                totalLegDurationSec += dt;
                legCount += 1;
            }
        }
    }

    @Override
    public void handleEvent(EnergyChargedEvent event) {
        totalEnergyChargedJ += event.getEnergy();
    }

    @Override
    public void handleEvent(ChargingStartEvent event) {
        recordChargingStarted(event.getVehicleId(), event.getTime());
    }

    @Override
    public void handleEvent(ChargingEndEvent event) {
        recordChargingEnded(event.getVehicleId(), event.getTime());
    }

    @Override
    public void handleEvent(QueuedAtChargerEvent event) {
        recordQueueStart(event.getVehicleId(), event.getTime());
    }

    @Override
    public void handleEvent(QuitQueueAtChargerEvent event) {
        queueStartTimes.remove(event.getVehicleId());
    }

    // ---------- Controler listeners ----------

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        // Reset counters each (single) iteration
        reset();
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        // Nothing special; values are available via getters after run()
    }

    // ---------- ChargingListener ----------

    @Override
    public void notifyVehicleQueued(ElectricVehicle ev, double now) {
        recordQueueStart(ev.getId(), now);
    }

    @Override
    public void notifyChargingStarted(ElectricVehicle ev, double now) {
        recordChargingStarted(ev.getId(), now);
    }

    @Override
    public void notifyChargingEnded(ElectricVehicle ev, double now) {
        recordChargingEnded(ev.getId(), now);
    }

    // ---------- Public metrics ----------

    /** Average leg duration in seconds (0 if no legs). */
    public double getAvgLegDurationSec() {
        return (legCount > 0) ? (totalLegDurationSec / (double) legCount) : 0.0;
    }

    public double getEnergyChargedKWh() {
        return EvUnits.J_to_kWh(totalEnergyChargedJ);
    }

    public double getAvgQueueTimeSec() {
        return queueSamples > 0 ? totalQueueSeconds / (double) queueSamples : 0.0;
    }

    public double getAvgChargingDurationSec() {
        return chargingSamples > 0 ? totalChargingSeconds / (double) chargingSamples : 0.0;
    }

    public long getCompletedCharges() {
        return chargingSamples;
    }

    // ---------- Helpers ----------

    private void recordQueueStart(Id<Vehicle> vehicleId, double time) {
        queueStartTimes.put(vehicleId, time);
    }

    private void recordChargingStarted(Id<Vehicle> vehicleId, double time) {
        Double queuedAt = queueStartTimes.remove(vehicleId);
        if (queuedAt != null && time >= queuedAt) {
            totalQueueSeconds += time - queuedAt;
            queueSamples += 1;
        }
        chargingStartTimes.put(vehicleId, time);
    }

    private void recordChargingEnded(Id<Vehicle> vehicleId, double time) {
        Double start = chargingStartTimes.remove(vehicleId);
        if (start != null && time >= start) {
            totalChargingSeconds += time - start;
            chargingSamples += 1;
        }
    }

    private void reset() {
        lastDepartureTime.clear();
        queueStartTimes.clear();
        chargingStartTimes.clear();
        totalLegDurationSec = 0.0;
        legCount = 0L;
        totalEnergyChargedJ = 0.0;
        totalQueueSeconds = 0.0;
        totalChargingSeconds = 0.0;
        queueSamples = 0L;
        chargingSamples = 0L;
    }
}
