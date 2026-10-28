package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integrity of the shipped Tokyo zone table (TASK-5). Guards against the copy that put
 * Z08 "Ueno" 11 m from Z31 Skytree, and against row-shape drift that the plain
 * split(",") readers would mis-parse. Run with the checkout root as working directory.
 */
class ZonesCsvIntegrityTest {

    static final String ZONES = "config/taxi/tokyo/zones.csv";
    /** TaxiSimulation.parseZoneLine accepts 11 columns, or 12 with the stand class (TASK-7). */
    static final int BASE_COLUMNS = 11;
    static final int WITH_STAND_CLASS = 12;
    static final double KM_PER_DEG = 111.32;
    static final double MIN_CENTRE_SEPARATION_M = 100.0;
    static final Set<String> TRANSPORT_HUBS = Set.of("Z01", "Z03", "Z04", "Z07", "Z08", "Z09", "Z13", "Z19",
            "Z31", "Z43", "Z51", "Z60", "ZS01", "ZS02", "ZR01", "ZR02", "ZP06", "ZP11");
    static final Set<String> STAND_CLASSES = Set.of("airport", "station", "other");

    static List<String> header;
    static List<String[]> rows;

    @BeforeAll
    static void load() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(ZONES), StandardCharsets.UTF_8);
        header = List.of(lines.get(0).trim().split(","));
        rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            // Parse exactly as TaxiSimulation's loader does: trim, skip blanks and comments, split(",").
            // split(",") drops trailing empty fields, so a row ending in "," has fewer than 11 columns.
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            rows.add(t.split(","));
        }
    }

    @Test
    void everyRowSplitsIntoAColumnCountTheLoaderAccepts() {
        assertEquals(WITH_STAND_CLASS, header.size(), "header");
        for (String[] r : rows) {
            assertTrue(r.length == BASE_COLUMNS || r.length == WITH_STAND_CLASS,
                    "row " + r[0] + " has " + r.length + " columns and would be skipped by the zone loader");
        }
    }

    @Test
    void everyHubHasAKnownStandClass() {
        int hub = header.indexOf("transport_hub");
        for (String[] r : rows) {
            if (!r[hub].trim().equals("1")) continue;
            assertEquals(WITH_STAND_CLASS, r.length, "hub " + r[0] + " has no stand_class");
            assertTrue(STAND_CLASSES.contains(r[WITH_STAND_CLASS - 1].trim()), "hub " + r[0] + " stand_class");
        }
    }

    @Test
    void zoneIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (String[] r : rows) {
            assertTrue(seen.add(r[0]), "duplicate zone id " + r[0]);
        }
    }

    @Test
    void noTwoCentresAreCloserThan100Metres() {
        int lon = header.indexOf("center_lon");
        int lat = header.indexOf("center_lat");
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                double lat1 = Double.parseDouble(rows.get(i)[lat]);
                double lat2 = Double.parseDouble(rows.get(j)[lat]);
                double dx = (Double.parseDouble(rows.get(j)[lon]) - Double.parseDouble(rows.get(i)[lon]))
                        * KM_PER_DEG * Math.cos(Math.toRadians((lat1 + lat2) / 2));
                double dy = (lat2 - lat1) * KM_PER_DEG;
                double metres = 1000.0 * Math.hypot(dx, dy);
                assertTrue(metres >= MIN_CENTRE_SEPARATION_M, String.format(
                        "%s and %s centres are %.0f m apart", rows.get(i)[0], rows.get(j)[0], metres));
            }
        }
    }

    @Test
    void transportHubsAreExactlyTheExpectedZones() {
        int hub = header.indexOf("transport_hub");
        Set<String> hubs = new HashSet<>();
        for (String[] r : rows) {
            if (r[hub].trim().equals("1")) hubs.add(r[0]);
        }
        assertEquals(TRANSPORT_HUBS, hubs);
    }
}
