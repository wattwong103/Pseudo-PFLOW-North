package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Stand class of a hub zone (TASK-7, ruling P3): an explicit 12th zones.csv column chooses the
 * stand wait; without it the legacy name rule applies, so 11-column files are unchanged.
 */
class StandClassTest {

    static final double AIRPORT = 8.0, STATION = 12.0, OTHER = 6.0;

    @Test
    void anExplicitClassOverridesTheName() {
        assertEquals(STATION, ShiftSimulator.standMeanMinutes("station", "Shinjuku", AIRPORT, STATION, OTHER));
        assertEquals(AIRPORT, ShiftSimulator.standMeanMinutes("airport", "Haneda", AIRPORT, STATION, OTHER));
        assertEquals(OTHER, ShiftSimulator.standMeanMinutes("other", "Tokyo Station", AIRPORT, STATION, OTHER));
    }

    @Test
    void withoutAClassTheLegacyNameRuleApplies() {
        assertEquals(AIRPORT, ShiftSimulator.standMeanMinutes(null, "Haneda Airport", AIRPORT, STATION, OTHER));
        assertEquals(STATION, ShiftSimulator.standMeanMinutes(null, "Nippori Station Area", AIRPORT, STATION, OTHER));
        assertEquals(OTHER, ShiftSimulator.standMeanMinutes(null, "Shinjuku", AIRPORT, STATION, OTHER));
    }

    @Test
    void elevenColumnLinesParseWithoutAClass() {
        DestinationZone z = TaxiSimulation.parseZoneLine(
            "Z03,commercial,Shinjuku,139.6998,35.6903,2.0,0.286,0.221,0.393,0.688,1");
        assertEquals("Z03", z.getZoneId());
        assertNull(z.getStandClass());
    }

    @Test
    void twelveColumnLinesCarryTheClass() {
        DestinationZone z = TaxiSimulation.parseZoneLine(
            "Z03,commercial,Shinjuku,139.6998,35.6903,2.0,0.286,0.221,0.393,0.688,1,station");
        assertEquals("station", z.getStandClass());
    }

    @Test
    void otherColumnCountsAreSkippedAndUnknownClassesStopTheRun() {
        assertNull(TaxiSimulation.parseZoneLine("Z03,commercial,Shinjuku,139.6998,35.6903,2.0,0.286,0.221,0.393,0.688"));
        assertNull(TaxiSimulation.parseZoneLine(
            "Z03,commercial,Shinjuku,139.6998,35.6903,2.0,0.286,0.221,0.393,0.688,1,station,x"));
        assertThrows(IllegalArgumentException.class, () -> TaxiSimulation.parseZoneLine(
            "Z03,commercial,Shinjuku,139.6998,35.6903,2.0,0.286,0.221,0.393,0.688,1,harbour"));
    }
}
