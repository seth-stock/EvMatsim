package org.matsim.contrib.rlev;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.IterationEndsEvent;

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
        IterationStartsListener,
        IterationEndsListener {

    // Track last departure time per person
    private final Map<Id<Person>, Double> lastDepartureTime = new HashMap<>();

    private double totalLegDurationSec = 0.0;
    private long   legCount = 0L;

    // Placeholder until EV charging signal is available
    private double chargeIntegralProxy = 0.0;

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

    // ---------- Public metrics ----------

    /** Average leg duration in seconds (0 if no legs). */
    public double getAvgLegDurationSec() {
        return (legCount > 0) ? (totalLegDurationSec / (double) legCount) : 0.0;
    }

    /** Placeholder for charging proxy (wire to EV events if/when available). */
    public double getChargeIntegralProxy() {
        return chargeIntegralProxy;
    }

    /** If you later wire EV events, expose a setter/update here. */
    public void setChargeIntegralProxy(double value) {
        this.chargeIntegralProxy = value;
    }

    // ---------- Helpers ----------

    private void reset() {
        lastDepartureTime.clear();
        totalLegDurationSec = 0.0;
        legCount = 0L;
        // keep chargeIntegralProxy as-is unless you also want to reset it here
        // chargeIntegralProxy = 0.0;
    }
}
