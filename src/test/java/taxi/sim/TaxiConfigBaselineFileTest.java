package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The optional validation.baseline.file key; empty keeps taxi_baseline_<city.name>.csv. */
class TaxiConfigBaselineFileTest {

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
    void absentKeyKeepsTheCityBaseline() throws Exception {
        assertEquals("config/taxi/validation/taxi_baseline_tokyo.csv",
            load("city.name=Tokyo\n").getValidationBaselineFile());
        assertEquals("config/taxi/validation/taxi_baseline_osaka.csv",
            load("city.name=Osaka\n").getValidationBaselineFile());
    }

    @Test
    void blankKeyKeepsTheCityBaseline() throws Exception {
        assertEquals("config/taxi/validation/taxi_baseline_tokyo.csv",
            load("city.name=Tokyo\nvalidation.baseline.file=   \n").getValidationBaselineFile());
    }

    @Test
    void theKeySelectsAnotherBaseline() throws Exception {
        Path fy2023 = Files.createFile(dir.resolve("taxi_baseline_tokyo_fy2023.csv"));
        String path = fy2023.toString().replace('\\', '/');
        assertEquals(path, load("city.name=Tokyo\nvalidation.baseline.file=" + path + "\n")
            .getValidationBaselineFile());
    }

    @Test
    void aReloadWithoutTheKeyFallsBackToTheCityBaseline() throws Exception {
        Path other = Files.createFile(dir.resolve("other.csv"));
        load("city.name=Tokyo\nvalidation.baseline.file=" + other.toString().replace('\\', '/') + "\n");
        assertEquals("config/taxi/validation/taxi_baseline_tokyo.csv",
            load("city.name=Tokyo\n").getValidationBaselineFile());
    }

    @Test
    void aKeyNamingAMissingFileFailsFastAndLeavesNoStalePath() throws Exception {
        Path other = Files.createFile(dir.resolve("other.csv"));
        load("city.name=Tokyo\nvalidation.baseline.file=" + other.toString().replace('\\', '/') + "\n");
        String missing = dir.resolve("no_such_baseline.csv").toString().replace('\\', '/');
        assertThrows(IllegalArgumentException.class,
            () -> load("city.name=Tokyo\nvalidation.baseline.file=" + missing + "\n"));
        assertEquals("config/taxi/validation/taxi_baseline_tokyo.csv",
            TaxiConfig.getInstance().getValidationBaselineFile());
    }

    @Test
    void aKeyNamingAMalformedBaselineFailsAtLoadNotAfterTheRun() throws Exception {
        Path bad = dir.resolve("bad_baseline.csv");
        Files.write(bad, ("metric_name,category,survey_value,unit,tolerance_pct,source,notes,priority,tier,bound\n"
            + "Avg Fare,REVENUE,2219,yen,25,src,a, b,HIGH,B,two_sided\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
            () -> load("city.name=Tokyo\nvalidation.baseline.file=" + bad.toString().replace('\\', '/') + "\n"));
    }
}
