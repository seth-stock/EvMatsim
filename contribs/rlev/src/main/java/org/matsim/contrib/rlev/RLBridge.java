package org.matsim.contrib.rlev;

import static org.matsim.contrib.rlev.RLLauncher.DEFAULT_SIM_THREADS;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.contrib.rlev.temperature.TemperatureChangeConfigGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JPype-friendly entry point that mirrors RLLauncher configuration and returns RewardMetrics.
 */
public final class RLBridge {

    private RLBridge() {}

    public static RewardMetrics run(String configXmlPath,
                                    String outputDir,
                                    boolean fastOpts,
                                    int endTimeSec,
                                    long seed) throws Exception {
        Path cfg = Path.of(configXmlPath).toAbsolutePath();
        Path outDir = Path.of(outputDir).toAbsolutePath();
        Files.createDirectories(outDir);

        Config config = ConfigUtils.loadConfig(cfg.toString(), new EvConfigGroup(), new TemperatureChangeConfigGroup());
        configureForSingleIteration(config, outDir.toString(), fastOpts, endTimeSec, seed);

        RLLauncher.ensureActivityParams(config.planCalcScore(), "h", 12 * 3600);
        RLLauncher.ensureActivityParams(config.planCalcScore(), "home", 12 * 3600);
        RLLauncher.ensureActivityParams(config.planCalcScore(), "work", 8 * 3600);
        RLLauncher.ensureActivityParams(config.planCalcScore(), "leisure", 2 * 3600);
        Controler controler = RLLauncher.createControler(config);

        RewardProbe probe = new RewardProbe();
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                bind(RewardProbe.class).toInstance(probe);
                addControlerListenerBinding().toInstance(probe);
                addEventHandlerBinding().toInstance(probe);
            }
        });

        MatsimRandom.reset(seed);
        controler.run();

        deleteDirectoryQuietly(outDir.resolve("output"));

        return new RewardMetrics(
                probe.getEnergyChargedKWh(),
                probe.getAvgLegDurationSec(),
                probe.getAvgQueueTimeSec(),
                probe.getAvgChargingDurationSec(),
                probe.getCompletedCharges()
        );
    }

    private static void configureForSingleIteration(Config config,
                                                    String outputDir,
                                                    boolean fastOpts,
                                                    int endTimeSec,
                                                    long seed) {
        config.controler().setLastIteration(0);
        config.controler().setWriteEventsInterval(0);
        config.controler().setWritePlansInterval(0);
        config.controler().setCreateGraphs(false);
        config.controler().setDumpDataAtEnd(false);
        config.controler().setOutputDirectory(outputDir);
        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        config.global().setRandomSeed(seed);
        config.qsim().setNumberOfThreads(DEFAULT_SIM_THREADS);
        config.global().setNumberOfThreads(DEFAULT_SIM_THREADS);
        if (endTimeSec > 0) {
            config.qsim().setEndTime(endTimeSec);
        }

        if (config.counts() != null) {
            config.counts().setInputFile(null);
            config.counts().setWriteCountsInterval(0);
        }

        RLLauncher.configureExecution(config, outputDir, fastOpts);
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            Files.walk(dir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignore) {
            // ignored
        }
    }
}
