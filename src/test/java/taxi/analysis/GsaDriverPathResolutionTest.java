package taxi.analysis;

import org.junit.jupiter.api.Test;
import util.PathResolver;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GsaDriverPathResolutionTest {

    @Test
    void morrisAndSobolDriversResolveTokyoOutputViaPathResolver() {
        Path expected = Paths.get(PathResolver.getPflowHome(), "output", "trips", "taxi", "tokyo");
        assertEquals(expected, MorrisDriver.resolveTokyoTripsDir());
        assertEquals(expected, SobolDriver.resolveTokyoTripsDir());

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("win") && System.getenv("PFLOW_HOME") == null) {
            assertFalse(
                MorrisDriver.resolveTokyoTripsDir().toString().startsWith("H:"),
                "Non-Windows hosts must not fall back to a literal H: drive prefix"
            );
        }
    }
}
