package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Trip zone labels in the export (TASK-7, ruling P6): of the zones whose circle contains a point,
 * the one with the nearest centre labels it, whatever the order of zones.csv.
 */
class ExportZoneLabelTest {

    static DestinationZone zone(String id, double lon, double lat, double radiusKm) {
        return new DestinationZone(id, "mixed", id, lon, lat, radiusKm, 0.5, 0.5, 0.5, 0.5, false);
    }

    @Test
    void theNearestContainingCentreWinsWhateverTheFileOrder() {
        DestinationZone wide = zone("WIDE", 139.700, 35.690, 2.0);   // listed first, centre ~0.9 km away
        DestinationZone near = zone("NEAR", 139.709, 35.690, 1.5);   // listed second, centre ~0.09 km away
        double lon = 139.710, lat = 35.690;
        assertEquals("NEAR", TaxiDataExporter.containingZoneNearestCentre(List.of(wide, near), lon, lat).getZoneId());
        assertEquals("NEAR", TaxiDataExporter.containingZoneNearestCentre(List.of(near, wide), lon, lat).getZoneId());
    }

    @Test
    void aPointOutsideEveryZoneHasNoLabel() {
        assertNull(TaxiDataExporter.containingZoneNearestCentre(
            List.of(zone("A", 139.70, 35.69, 1.0)), 139.80, 35.69));
    }
}
