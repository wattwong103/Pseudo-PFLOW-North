package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The optional spatial.exclusion.areas.path key (TASK-7); empty keeps the exclusion layer off. */
class TaxiConfigExclusionKeyTest {

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
    void anAbsentKeyMeansNoLayer() throws Exception {
        assertEquals("", load("city.name=Tokyo\n").getExclusionAreasPath());
    }

    @Test
    void thePflowHomeTokenIsResolved() throws Exception {
        String path = load("city.name=Tokyo\nspatial.exclusion.areas.path=${PFLOW_HOME}/data/spatial/x.shp\n")
            .getExclusionAreasPath();
        assertFalse(path.contains("${PFLOW_HOME}"));
        assertTrue(path.endsWith("/data/spatial/x.shp"), path);
    }

    @Test
    void aReloadWithoutTheKeyTurnsTheLayerOffAgain() throws Exception {
        load("city.name=Tokyo\nspatial.exclusion.areas.path=/x.shp\n");
        assertEquals("", load("city.name=Tokyo\n").getExclusionAreasPath());
    }

    @Test
    void theShippedOsakaConfigHasNoLayer() throws Exception {
        TaxiConfig config = TaxiConfig.getInstance();
        assertTrue(config.loadFromFile("config/taxi/osaka/taxi_config.properties"));
        assertEquals("", config.getExclusionAreasPath());
    }
}
