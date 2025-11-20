package org.matsim.contrib.rlev;

/**
 * Minimal data container returned to the JPype bridge.
 */
public class RewardMetrics {
    private final double energyChargedKWh;
    private final double avgLegDurationSec;
    private final double avgQueueTimeSec;
    private final double avgChargingDurationSec;
    private final long completedCharges;

    public RewardMetrics(double energyChargedKWh,
                         double avgLegDurationSec,
                         double avgQueueTimeSec,
                         double avgChargingDurationSec,
                         long completedCharges) {
        this.energyChargedKWh = energyChargedKWh;
        this.avgLegDurationSec = avgLegDurationSec;
        this.avgQueueTimeSec = avgQueueTimeSec;
        this.avgChargingDurationSec = avgChargingDurationSec;
        this.completedCharges = completedCharges;
    }

    public double getEnergyChargedKWh() {
        return energyChargedKWh;
    }

    public double getAvgLegDurationSec() {
        return avgLegDurationSec;
    }

    public double getAvgQueueTimeSec() {
        return avgQueueTimeSec;
    }

    public double getAvgChargingDurationSec() {
        return avgChargingDurationSec;
    }

    public long getCompletedCharges() {
        return completedCharges;
    }
}
