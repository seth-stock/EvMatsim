package org.matsim.contrib.rlev;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal main() that runs a single-iteration MATSim Controler with
 * "fast" options (low IO) and an optional output directory override.
 *
 * Usage:
 *   java -cp <your-jar> org.matsim.contrib.rlev.EmbeddedControlerMain <config.xml> [<output_dir>]
 */
public final class EmbeddedControlerMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: EmbeddedControlerMain <config.xml> [<output_dir>]");
            System.exit(2);
        }
        final Path cfgPath = Paths.get(args[0]).toAbsolutePath();
        final String outputDirOverride = (args.length == 2) ? args[1] : null;

        // Read config
        Config cfg = ConfigUtils.createConfig();
        URL cfgUrl = cfgPath.toUri().toURL();
        new ConfigReader(cfg).parse(cfgUrl);

        // "fast" options, same as we do server-side
        cfg.controler().setLastIteration(0);
        cfg.controler().setWriteEventsInterval(0);
        cfg.controler().setWritePlansInterval(0);
        cfg.controler().setCreateGraphs(false);
        cfg.controler().setDumpDataAtEnd(false);
        cfg.qsim().setNumberOfThreads(1);
        cfg.global().setNumberOfThreads(1);

        if (outputDirOverride != null && !outputDirOverride.isBlank()) {
            cfg.controler().setOutputDirectory(outputDirOverride);
        }

        Controler controler = new Controler(cfg);
        controler.run();
    }
}
