package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Loaded-leg outcomes by pickup municipality group (WP0 Q3: northern/eastern border effects). */
class LoadedLegBorderStatsTest {

    @Test
    void wardsMapToTheirBorderGroup() {
        assertEquals("north", LoadedLegBorderStats.group("13121"));  // Adachi
        assertEquals("north", LoadedLegBorderStats.group("13117"));  // Kita
        assertEquals("north", LoadedLegBorderStats.group("13119"));  // Itabashi
        assertEquals("north", LoadedLegBorderStats.group("13120"));  // Nerima
        assertEquals("east", LoadedLegBorderStats.group("13122"));   // Katsushika
        assertEquals("east", LoadedLegBorderStats.group("13123"));   // Edogawa
        assertEquals("rest", LoadedLegBorderStats.group("13101"));   // Chiyoda
        assertEquals("unknown", LoadedLegBorderStats.group(null));
    }

    @Test
    void ratesAreComputedPerGroup() {
        LoadedLegBorderStats s = new LoadedLegBorderStats();
        s.record("13121", LoadedLegBorderStats.Outcome.DIRECT);
        s.record("13121", LoadedLegBorderStats.Outcome.PROJECTED);
        s.record("13121", LoadedLegBorderStats.Outcome.FAILED);
        s.record("13121", LoadedLegBorderStats.Outcome.FAILED);
        s.record("13101", LoadedLegBorderStats.Outcome.DIRECT);
        Map<String, Double> rows = s.toDashboardRows();
        assertEquals(4.0, rows.get("LOADED_LEGS.north_samples"));
        assertEquals(25.0, rows.get("LOADED_LEGS.north_projected_pct"), 1e-9);
        assertEquals(50.0, rows.get("LOADED_LEGS.north_failed_pct"), 1e-9);
        assertEquals(0.0, rows.get("LOADED_LEGS.rest_failed_pct"), 1e-9);
        assertTrue(Double.isNaN(rows.get("LOADED_LEGS.east_failed_pct")));
    }
}
