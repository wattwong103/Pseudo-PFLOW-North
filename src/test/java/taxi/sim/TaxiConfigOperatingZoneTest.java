package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Config keys for the optional operating zone (operating.zone.*, home.max.attempts). */
class TaxiConfigOperatingZoneTest {

    @TempDir
    Path dir;

    private TaxiConfig load(String body) throws Exception {
        Path file = dir.resolve("taxi_config.properties");
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile(file.toString()));
        return config;
    }

    @Test
    void codesAreParsedTrimmedAndInOrder() throws Exception {
        TaxiConfig config = load("operating.zone.adm.codes= 13101, 13102 ,13203\n");
        assertEquals(Arrays.asList("13101", "13102", "13203"), config.getOperatingZoneAdmCodes());
    }

    @Test
    void absentKeyMeansDisabled() throws Exception {
        TaxiConfig config = load("taxi.fleet.size=10\n");
        assertTrue(config.getOperatingZoneAdmCodes().isEmpty());
    }

    @Test
    void attemptDefaults() throws Exception {
        TaxiConfig config = load("taxi.fleet.size=10\n");
        assertEquals(20, config.getOperatingZoneMaxAttempts());
        assertEquals(200, config.getHomeMaxAttempts());
    }

    @Test
    void attemptOverrides() throws Exception {
        TaxiConfig config = load("operating.zone.pickup.max.attempts=7\nhome.max.attempts=33\n");
        assertEquals(7, config.getOperatingZoneMaxAttempts());
        assertEquals(33, config.getHomeMaxAttempts());
    }
}
