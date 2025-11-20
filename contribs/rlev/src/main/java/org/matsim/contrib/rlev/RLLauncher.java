package org.matsim.contrib.rlev;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.contrib.rlev.temperature.TemperatureChangeConfigGroup;

import com.google.inject.Inject;

import java.util.Set;
import com.google.inject.Provider;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.rlev.charging.ChargingPower;
import org.matsim.contrib.rlev.discharging.AuxEnergyConsumption;
import org.matsim.contrib.rlev.discharging.OhdeSlaskiAuxEnergyConsumption;
import org.matsim.contrib.rlev.discharging.DriveEnergyConsumption;
import org.matsim.contrib.rlev.discharging.OhdeSlaskiDriveEnergyConsumption;
import org.matsim.contrib.rlev.fleet.ElectricFleet;
import org.matsim.contrib.rlev.fleet.ElectricFleetSpecification;
import org.matsim.contrib.rlev.fleet.ElectricFleetUtils;
import org.matsim.contrib.rlev.routing.EvNetworkRoutingProvider;
import org.matsim.contrib.rlev.scoring.EvScoringFunctionFactory;
import org.matsim.core.controler.AbstractModule;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;


/**
 * Minimal launcher that:
 *  1) Accepts (configPath, outputDir, fastOptsFlag)
 *  2) Pre-cleans the XML to remove legacy/unsupported modules (currently: <module name="counts">)
 *  3) Loads the cleaned config and runs MATSim
 *
 * This avoids using removed/renamed APIs such as CountsConfigGroup.getCountsFile()/getInputFile()
 * and ControllerConfigGroup.OverwriteFileSetting, which changed in recent MATSim.
 */
public final class RLLauncher {

    public static final int DEFAULT_SIM_THREADS = 12;

    private RLLauncher() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: RLLauncher <config.xml> <outputDir> <fastOpts:true|false>");
            System.exit(2);
        }

        System.setProperty("com.sun.media.imageio.disableCodecLib", "true");

        final String originalConfigPath = args[0];
        final String outputDir = args[1];
        final boolean fastOpts = Boolean.parseBoolean(args[2]);

        System.out.println("[RLLauncher] config=" + originalConfigPath);
        System.out.println("[RLLauncher] outputDir=" + outputDir);
        System.out.println("[RLLauncher] fastOpts=" + fastOpts + " endTime=null");

        // 1) Pre-clean the config to strip legacy/problematic modules (counts_v1.dtd etc.)
        final Path cleanedConfig = writeCleanedConfig(originalConfigPath);

        // 2) Load cleaned config
        final Config config = ConfigUtils.loadConfig(
                cleanedConfig.toString(),
                new EvConfigGroup(),
                new TemperatureChangeConfigGroup());

        configureExecution(config, outputDir, fastOpts);

        

        // 3) Launch MATSim
		removeLegacyActivityParams(config.planCalcScore(), Set.of("home", "work", "leisure"));
		ensureActivityParams(config.planCalcScore(), "h", 12 * 3600);
		ensureActivityParams(config.planCalcScore(), "w", 8 * 3600);

        final Controler controler = createControler(config);
        controler.run();
    }

    public static void configureExecution(Config config, String outputDir, boolean fastOpts) {
        double targetTimeStep = 3.0;
        config.qsim().setTimeStepSize(targetTimeStep);

        // Force EV stats writers for PPO reward extraction
        EvConfigGroup evCfg = ConfigUtils.addOrGetModule(config, EvConfigGroup.class);
        evCfg.timeProfiles = true;
        evCfg.chargerPowerTimeProfiles = true;
        int discreteStep = Math.max(1, (int)Math.round(targetTimeStep));
        evCfg.chargeTimeStep = discreteStep;
        evCfg.auxDischargeTimeStep = discreteStep;

        // Ensure output directory is what Python side requested
        config.controler().setOutputDirectory(outputDir);
        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        int targetThreads = DEFAULT_SIM_THREADS;
        if (!hasExplicitParam(config.getModule(GlobalConfigGroup.GROUP_NAME), "numberOfThreads")) {
            config.global().setNumberOfThreads(targetThreads);
        }
        if (!hasExplicitParam(config.getModule(QSimConfigGroup.GROUP_NAME), "numberOfThreads")) {
            config.qsim().setNumberOfThreads(targetThreads);
        }

        if (!fastOpts) {
            ConfigGroup eventsModule = config.getModule("parallelEventHandling");
            if (eventsModule == null) {
                eventsModule = new ConfigGroup("parallelEventHandling");
                config.addModule(eventsModule);
            }
            eventsModule.addParam("oneThreadPerHandler", "true");
            eventsModule.addParam("synchronizeOnSimSteps", "false");

            String existingQueue = eventsModule.getValue("eventsQueueSize");
            long queueSize = 0;
            if (existingQueue != null) {
                try {
                    queueSize = Long.parseLong(existingQueue.trim());
                } catch (NumberFormatException ignored) {
                    queueSize = 0;
                }
            }
            if (queueSize < 10_000_000L) {
                eventsModule.addParam("eventsQueueSize", "1000000");
            }

            eventsModule.getParams().remove("numberOfThreads");
        } else {
            ConfigGroup eventsModule = config.getModule("parallelEventHandling");
            if (eventsModule != null && !hasExplicitParam(eventsModule, "numberOfThreads")) {
                eventsModule.addParam("numberOfThreads", Integer.toString(targetThreads));
            }
        }
    }

    public static Controler createControler(Config config) {
        final Controler controler = new Controler(config);
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                bind(DriveEnergyConsumption.Factory.class).toInstance(ev -> new OhdeSlaskiDriveEnergyConsumption());
                bind(AuxEnergyConsumption.Factory.class).to(OhdeSlaskiAuxEnergyConsumption.Factory.class).in(Singleton.class);

                bind(ElectricFleet.class).toProvider(new Provider<>() {
                    @Inject private ElectricFleetSpecification fleetSpecification;
                    @Inject private DriveEnergyConsumption.Factory driveConsumptionFactory;
                    @Inject private AuxEnergyConsumption.Factory auxConsumptionFactory;
                    @Inject private ChargingPower.Factory chargingPowerFactory;

                    @Override
                    public ElectricFleet get() {
                        return ElectricFleetUtils.createDefaultFleet(
                                fleetSpecification,
                                driveConsumptionFactory,
                                auxConsumptionFactory,
                                chargingPowerFactory
                        );
                    }
                }).asEagerSingleton();

                install(new EvModule());
                addRoutingModuleBinding(TransportMode.car).toProvider(new EvNetworkRoutingProvider(TransportMode.car));
            }
        });
        return controler;
    }

    private static boolean hasExplicitParam(ConfigGroup module, String paramName) {
        return module != null && module.getValue(paramName) != null;
    }

    /**
     * Ensures the plan scoring configuration contains default parameters for the given activity type.
     */
	static void ensureActivityParams(PlanCalcScoreConfigGroup planCalcScore, String type, double typicalDurationSeconds) {
		if (planCalcScore.getActivityParams(type) == null) {
			PlanCalcScoreConfigGroup.ActivityParams params = new PlanCalcScoreConfigGroup.ActivityParams(type);
			params.setTypicalDuration(typicalDurationSeconds);
			planCalcScore.addActivityParams(params);
			System.out.println("[RLLauncher] Added default planCalcScore params for activity type '" + type + "'.");
		} else {
			System.out.println("[RLLauncher] Using existing planCalcScore params for activity type '" + type + "'.");
		}
	}

	static void removeLegacyActivityParams(PlanCalcScoreConfigGroup planCalcScore, Set<String> deprecatedTypes) {
		for (String type : deprecatedTypes) {
			PlanCalcScoreConfigGroup.ActivityParams params = planCalcScore.getActivityParams(type);
			if (params != null) {
				planCalcScore.getActivityParams().remove(params);
				System.out.println("[RLLauncher] Removed legacy planCalcScore params for activity type '" + type + "'.");
			}
		}
	}

    /**
     * Reads the original config XML and strips legacy/problematic modules.
     *
     * Currently removes the entire <module name="counts">...</module> block (counts_v1.dtd).
     */
    private static Path writeCleanedConfig(String originalConfigPath) throws IOException {
        final String xml = Files.readString(Path.of(originalConfigPath), StandardCharsets.UTF_8);

        // Remove any <module name="counts"> ... </module> (DOTALL)
        String cleaned = xml.replaceAll("(?is)<\\s*module\\s+name\\s*=\\s*\"counts\"\\s*>.*?<\\s*/\\s*module\\s*>", "");

        cleaned = cleaned.replace("module name=\"controller\"", "module name=\"controler\"");

        // Older configs often keep a boolean "enable" flag inside modules such as parallelEventHandling.
        // That attribute no longer exists in current MATSim, so strip it proactively to avoid parser errors.
        cleaned = cleaned.replaceAll("(?is)<\\s*param\\s+name\\s*=\\s*\"enable\"\\s+value\\s*=\\s*\"(true|false)\"\\s*/?\\s*>", "");

        final Path tempDir = Files.createTempDirectory("rlev_cfg_");
        final Path cleanedPath = tempDir.resolve("cleaned_config.xml");
        Files.writeString(cleanedPath, cleaned, StandardCharsets.UTF_8);

        final Path originalDir = Path.of(originalConfigPath).toAbsolutePath().getParent();
        if (originalDir != null) {
            try (Stream<Path> files = Files.list(originalDir)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().equals("cleaned_config.xml"))
                        .forEach(p -> {
                            final Path target = tempDir.resolve(p.getFileName());
                            try {
                                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                                if (target.getFileName().toString().endsWith(".xml")) {
                                    String xmlBody = Files.readString(target, StandardCharsets.UTF_8);
                                    String sanitized = xmlBody.replace(
                                            "<!DOCTYPE network SYSTEM \"http://www.matsim.org/files/dtd/network_v2.dtd\">",
                                            "<!DOCTYPE network SYSTEM \"http://www.matsim.org/files/dtd/network_v2.dtd\" [\n<!ATTLIST link slopes CDATA #IMPLIED>\n]>");
                                    if (!sanitized.equals(xmlBody)) {
                                        Files.writeString(target, sanitized, StandardCharsets.UTF_8);
                                    }
                                }
                            } catch (IOException e) {
                                System.err.println("[RLLauncher] WARN: failed to copy " + p + " -> " + target + ": " + e);
                            }
                        });
            }
        }

        System.out.println("[RLLauncher] Using cleaned config at " + cleanedPath);
        return cleanedPath;
    }
}
