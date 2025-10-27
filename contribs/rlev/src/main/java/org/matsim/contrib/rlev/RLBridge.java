package org.matsim.contrib.rlev;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Java bridge for Python&lt;-&gt;MATSim via JPype.
 * Load once, set chargers per call, run one iteration, return rewards.
 */
public final class RLBridge {

	private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

	private static Path baseFolder;
	private static Path workingFolder;
	private static Config baseConfig;
	private static Scenario baseScenario;

	// Tunables
	private static int endTimeSeconds = -1;  // -1 = full day

	private RLBridge() {}

	/** One-time init. workingDir must be writable temp. */
	public static synchronized void init(String configXmlPath, String workingDir, int endTimeSec) throws Exception {
		if (INITIALIZED.get()) return;

		baseFolder = Paths.get(configXmlPath).toAbsolutePath().getParent();
		workingFolder = Paths.get(workingDir).toAbsolutePath();
		Files.createDirectories(workingFolder);

		URL cfgUrl = Paths.get(configXmlPath).toUri().toURL();
		baseConfig = ConfigUtils.createConfig();
		new ConfigReader(baseConfig).parse(cfgUrl);

		// Speed-ups and low I/O
		baseConfig.controler().setLastIteration(0);
		baseConfig.controler().setWriteEventsInterval(0);
		baseConfig.controler().setWritePlansInterval(0);
		baseConfig.controler().setCreateGraphs(false);
		baseConfig.controler().setDumpDataAtEnd(false);
		baseConfig.qsim().setNumberOfThreads(1);
		baseConfig.global().setNumberOfThreads(1);
		if (endTimeSec > 0) {
			baseConfig.qsim().setEndTime(endTimeSec);
		}

		// Force output into working folder
		baseConfig.controler().setOutputDirectory(workingFolder.resolve("output").toString());

		baseScenario = ScenarioUtils.loadScenario(baseConfig);
		INITIALIZED.set(true);
		System.out.println("[RLBridge] Initialized with config=" + configXmlPath
				+ " workingDir=" + workingDir + " endTime=" + endTimeSec);
	}

	/** Caller sets charger choices for each link (MultiDiscrete actions). */
	public static synchronized double[] runOnce(int[] chargerActions) throws Exception {
		if (!INITIALIZED.get()) {
			throw new IllegalStateException("RLBridge.init() must be called first");
		}

		// (A) Create chargers.xml for this step into working folder
		Path chargersPath = workingFolder.resolve("chargers.xml");
		ChargerXMLWriter.writeChargers(chargersPath, chargerActions);
		// If your scenario expects a specific config param for chargers, set it in (B) below.

		// (B) Build a step config by cloning modules from baseConfig
		Path stepCfg = workingFolder.resolve("step_config.xml");
		Config step = ConfigUtils.createConfig();

		for (ConfigGroup group : baseConfig.getModules().values()) {
			if (group != null) {
				step.addModule(group);
			}
		}

		// Override output directory for this step
		step.controler().setOutputDirectory(workingFolder.resolve("output").toString());

		// If you need to point a config parameter to chargersPath, do it here, e.g.:
		// step.setParam("ev", "chargersFile", chargersPath.toString());

		ConfigUtils.writeConfig(step, stepCfg.toString());

		// (C) Run MATSim with RewardProbe
		RewardProbe probe = new RewardProbe();
		Controler controler = new Controler(ConfigUtils.loadConfig(stepCfg.toString()));
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override public void install() {
				addControlerListenerBinding().toInstance(probe);
				addEventHandlerBinding().toInstance(probe);
			}
		});
		controler.run();

		// (D) Compute rewards from probe (no file reads needed)
		double timeReward = probe.getAvgLegDurationSec() / 86400.0;
		double chargeReward = probe.getChargeIntegralProxy(); // replace with real EV metric when ready

		// (E) Clean working output for next step (keep directory)
		deleteDirectoryQuietly(workingFolder.resolve("output"));

		return new double[]{chargeReward, timeReward};
	}

	/** Optional helper: clear caches / outputs between episodes */
	public static synchronized void resetWorkingDir() {
		try { deleteDirectoryQuietly(workingFolder.resolve("output")); } catch (Exception ignore) {}
	}

	private static void deleteDirectoryQuietly(Path dir) {
		if (dir == null) return;
		try {
			if (!Files.exists(dir)) return;
			Files.walk(dir)
					.sorted((a, b) -> b.getNameCount() - a.getNameCount())
					.forEach(p -> {
						try { Files.deleteIfExists(p); } catch (IOException ignored) {}
					});
		} catch (IOException ignored) {}
	}
}
