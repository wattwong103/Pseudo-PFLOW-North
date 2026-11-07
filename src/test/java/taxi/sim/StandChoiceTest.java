package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stand-hub choice (TASK-9, ruling P9): "nearest" keeps today's rule; "huff" draws an eligible
 * hub with probability proportional to station ridership / road km^2 (Huff, exponent 2).
 */
class StandChoiceTest {

    @TempDir
    Path dir;

    static final double LON = 139.70, LAT = 35.69, KM = 1 / (111.32 * Math.cos(Math.toRadians(35.69)));

    static DestinationZone hub(String id, double eastKm) {
        return new DestinationZone(id, "hub", id, LON + eastKm * KM, LAT, 1.0, 0.5, 0.5, 0.5, 0.5, true);
    }

    @Test
    void huffSplitsEvenlyWhenRidershipOverDistanceSquaredIsEqual() {
        // A: 100 riders at 1 km; B: 400 riders at 2 km -> equal weights (distance ratio 2 survives the Manhattan factor)
        List<DestinationZone> hubs = List.of(hub("A", 1.0), hub("B", 2.0));
        Map<String, Double> riders = Map.of("A", 100.0, "B", 400.0);
        assertEquals("A", TaxiSimulation.huffPick(hubs, riders, LON, LAT, 0.49).getZoneId());
        assertEquals("B", TaxiSimulation.huffPick(hubs, riders, LON, LAT, 0.51).getZoneId());
    }

    @Test
    void ineligibleHubsAreNeverChosen() {
        DestinationZone big = hub("BIG", 0.5);
        big.setServiceEligible(false);
        List<DestinationZone> hubs = List.of(big, hub("SMALL", 3.0));
        Map<String, Double> riders = Map.of("BIG", 1e9, "SMALL", 1.0);
        for (double r = 0.0; r < 1.0; r += 0.05) {
            assertEquals("SMALL", TaxiSimulation.huffPick(hubs, riders, LON, LAT, r).getZoneId());
        }
    }

    @Test
    void aHubAtTheTaxiPositionHasAFiniteWeight() {
        List<DestinationZone> hubs = List.of(hub("HERE", 0.0), hub("FAR", 5.0));
        Map<String, Double> riders = Map.of("HERE", 10.0, "FAR", 1e6);
        // distance floored at 0.1 km: HERE 10/0.01 = 1000, FAR 1e6/(5*1.4)^2 ~ 20408 -> FAR is more likely, HERE possible
        assertEquals("HERE", TaxiSimulation.huffPick(hubs, riders, LON, LAT, 0.0).getZoneId());
        assertNotEquals("HERE", TaxiSimulation.huffPick(hubs, riders, LON, LAT, 0.99).getZoneId());
    }

    @Test
    void theRidershipTableLoadsAndAMissingFileStopsTheRun() throws Exception {
        Path csv = dir.resolve("tokyo_hub_ridership.csv");
        Files.write(csv, "zone_id,riders,stations\nZ03,3179159.0,6\nZ07,145598.0,3\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Double> m = TaxiSimulation.loadHubRidership(csv.toString());
        assertEquals(3179159.0, m.get("Z03"));
        assertEquals(2, m.size());
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.loadHubRidership(dir.resolve("none.csv").toString()));
        Path empty = Files.createFile(dir.resolve("empty.csv"));
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.loadHubRidership(empty.toString()));
        Path blankRows = dir.resolve("blank.csv");
        Files.write(blankRows, "zone_id,riders,stations\n\n \n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.loadHubRidership(blankRows.toString()));
        Path infinite = dir.resolve("inf.csv");
        Files.write(infinite, "zone_id,riders,stations\nZ03,Infinity,6\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.loadHubRidership(infinite.toString()));
        Path headerless = dir.resolve("headerless.csv");
        Files.write(headerless, "Z03,3179159.0,6\nZ07,145598.0,3\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.loadHubRidership(headerless.toString()));
        Path duplicate = dir.resolve("dup.csv");
        Files.write(duplicate, "zone_id,riders,stations\nZ03,1.0,1\nZ03,2.0,1\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> TaxiSimulation.loadHubRidership(duplicate.toString()));
    }

    @Test
    void huffReturnsNullWhenNoHubQualifiesAndTheLastHubAtTheTopOfTheRange() {
        DestinationZone a = hub("A", 1.0), b = hub("B", 2.0);
        Map<String, Double> riders = Map.of("A", 100.0, "B", 400.0);
        assertEquals(null, TaxiSimulation.huffPick(List.of(), riders, LON, LAT, 0.5));
        assertEquals(null, TaxiSimulation.huffPick(List.of(a, b), Map.of(), LON, LAT, 0.5));
        assertEquals("B", TaxiSimulation.huffPick(List.of(a, b), riders, LON, LAT, Math.nextDown(1.0)).getZoneId());
        DestinationZone plain = new DestinationZone("P", "mixed", "P", LON, LAT, 1.0, 0.5, 0.5, 0.5, 0.5, false);
        assertEquals(null, TaxiSimulation.huffPick(List.of(plain), Map.of("P", 1e9), LON, LAT, 0.5));
        assertEquals("A", TaxiSimulation.huffPick(List.of(plain, a), Map.of("P", 1e9, "A", 1.0), LON, LAT, 0.0).getZoneId());
    }

    @Test
    void theChoiceKeyDefaultsToNearest() throws Exception {
        Path file = dir.resolve("taxi_config.properties");
        TaxiConfig config = TaxiConfig.getInstance();
        try {
            Files.write(file, "city.name=Tokyo\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(config.loadFromFile(file.toString()));
            assertEquals("nearest", config.getStandChoice());
            Files.write(file, "city.name=Tokyo\nstand.choice=huff\nstand.ridership.path=/x.csv\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(config.loadFromFile(file.toString()));
            assertEquals("huff", config.getStandChoice());
            assertEquals("/x.csv", config.getStandRidershipPath());
            Files.write(file, "city.name=Tokyo\nstand.choice=Huff\n".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> config.loadFromFile(file.toString()));
        } finally {
            Files.write(file, "city.name=Tokyo\n".getBytes(StandardCharsets.UTF_8));
            config.loadFromFile(file.toString());
        }
    }
}
