// File: src/main/java/org/matsim/contrib/rlev/ChargerXMLWriter.java
package org.matsim.contrib.rlev;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/** Minimal placeholder – write whatever format your scenario expects. */
final class ChargerXMLWriter {
    private ChargerXMLWriter() {}
    static void writeChargers(Path out, int[] actions) throws IOException {
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("<chargers>\n");
            for (int i = 0; i < actions.length; i++) {
                w.write(String.format("  <charger linkId=\"%d\" type=\"%d\"/>\n", i, actions[i]));
            }
            w.write("</chargers>\n");
        }
    }
}
