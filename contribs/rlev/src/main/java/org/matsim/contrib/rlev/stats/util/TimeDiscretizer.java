package org.matsim.contrib.rlev.stats.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.stream.IntStream;

import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.trafficmonitoring.TimeBinUtils;

public class TimeDiscretizer {
	private final int intervalCount;
	private final double timeInterval;
	private final int timeIntervalSeconds;
	private final int maxTime; // seconds (rounded up)

	public TimeDiscretizer(TravelTimeCalculatorConfigGroup ttcConfig) {
		// getMaxTime() can be double in newer MATSim; round up to int seconds
		this((int) Math.ceil(ttcConfig.getMaxTime()), ttcConfig.getTraveltimeBinSize());
	}

	public TimeDiscretizer(int maxTime, double timeInterval) {
		checkArgument(timeInterval > 0, "interval size must be positive");
		checkArgument(maxTime >= 0, "maxTime must not be negative");

		this.timeInterval = timeInterval;
		this.timeIntervalSeconds = toSeconds(timeInterval);
		this.maxTime = maxTime;
		this.intervalCount = TimeBinUtils.getTimeBinCount(maxTime, timeIntervalSeconds);
	}

	private static int toSeconds(double timeInterval) {
		int rounded = (int) Math.round(timeInterval);
		checkArgument(Math.abs(timeInterval - rounded) < 1e-6,
				"MATSim 15.x expects whole-second intervals but got %s", timeInterval);
		return rounded;
	}

	public int getIdx(double time) {
		checkArgument(time >= 0, "time must be >= 0");
		checkArgument(time <= maxTime, "time must be <= maxTime");
		return TimeBinUtils.getTimeBinIndex(time, timeIntervalSeconds, intervalCount);
	}

	public double discretize(double time) { return getIdx(time) * timeInterval; }

	public double getTimeInterval() { return timeInterval; }

	public int getIntervalCount() { return intervalCount; }

	public double[] getTimes() {
		return IntStream.range(0, intervalCount).mapToDouble(i -> i * timeInterval).toArray();
	}

	public interface TimeBinConsumer { void accept(int bin, double time); }

	public void forEach(TimeBinConsumer consumer) {
		for (int i = 0; i < intervalCount; i++) consumer.accept(i, i * timeInterval);
	}
}
