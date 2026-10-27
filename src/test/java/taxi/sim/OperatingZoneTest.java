package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dcity.aggr.ShpLoader;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;

/**
 * Operating-zone layer of {@link TaxiGeoValidator}: Tokyo's Special Wards zone
 * (23 wards + Musashino + Mitaka) built from the adm_code field of polbnda_jpn.
 * Run with the checkout root as working directory (shapefile path is relative).
 */
class OperatingZoneTest {

    static final String SHP_DIR = "src/shared/gm-jp/";
    static final Set<String> SPECIAL_WARDS_ZONE = specialWardsZone();

    static TaxiGeoValidator validator;

    static Set<String> specialWardsZone() {
        Set<String> codes = new TreeSet<>();
        for (int c = 13101; c <= 13123; c++) codes.add(Integer.toString(c));
        codes.add("13203");
        codes.add("13204");
        return codes;
    }

    @BeforeAll
    static void loadZone() {
        validator = new TaxiGeoValidator(SHP_DIR);
        validator.loadOperatingZone(SPECIAL_WARDS_ZONE);
    }

    @Test
    void chiyodaIsInside() {
        assertTrue(validator.isInOperatingZone(139.7528, 35.6940));
    }

    @Test
    void musashinoIsInside() {
        assertTrue(validator.isInOperatingZone(139.5660, 35.7180));
    }

    @Test
    void hanedaZoneCentroidIsInside() {
        // Z07 Haneda Airport centre in config/taxi/tokyo/zones.csv
        assertTrue(validator.isInOperatingZone(139.7798, 35.5494));
    }

    @Test
    void kawasakiIsOutside() {
        assertFalse(validator.isInOperatingZone(139.7029, 35.5308));
    }

    @Test
    void hachiojiIsOutside() {
        assertFalse(validator.isInOperatingZone(139.3160, 35.6664));
    }

    @Test
    void loadsAll25CodesAs37Polygons() {
        assertTrue(validator.isOperatingZoneEnabled());
        assertEquals(SPECIAL_WARDS_ZONE, validator.getOperatingZoneCodes());
        assertEquals(37, validator.getOperatingZonePolygonCount());
        assertNotNull(validator.getOperatingZoneEnvelope());
    }

    @Test
    void aConfiguredCodeMissingFromTheShapefileIsFatal() {
        TaxiGeoValidator partial = new TaxiGeoValidator(SHP_DIR);
        Set<String> codes = new TreeSet<>(SPECIAL_WARDS_ZONE);
        codes.add("99999");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> partial.loadOperatingZone(codes));
        assertFalse(partial.isOperatingZoneEnabled());
    }

    @Test
    void emptyCodeSetDisablesTheZone() {
        TaxiGeoValidator disabled = new TaxiGeoValidator(SHP_DIR);
        disabled.loadOperatingZone(Collections.emptySet());
        assertFalse(disabled.isOperatingZoneEnabled());
        assertTrue(disabled.isInOperatingZone(139.3160, 35.6664));
        assertTrue(disabled.isInOperatingZone(0.0, 0.0));
    }

    /**
     * Two points 40 m apart across the Setagaya (13112, inside) / Komae (13219,
     * outside) border that share the same 3-decimal (~100 m) key: a coarse
     * cache like isWithinCity's would classify them identically.
     */
    @Test
    void pointsFortyMetresApartAcrossTheBorderDiffer() throws Exception {
        Geometry setagaya = municipality("13112");
        Geometry komae = municipality("13219");
        Geometry border = setagaya.getBoundary().intersection(komae.getBoundary());
        Coordinate inC = setagaya.getInteriorPoint().getCoordinate();
        Coordinate outC = komae.getInteriorPoint().getCoordinate();

        Coordinate[] pair = null;
        for (Coordinate b : border.getCoordinates()) {
            Coordinate in = towards(b, inC, 20.0);
            Coordinate out = towards(b, outC, 20.0);
            boolean truthDiffers = setagaya.covers(point(in)) && komae.covers(point(out));
            if (truthDiffers && key(in).equals(key(out))) {
                pair = new Coordinate[] {in, out};
                break;
            }
        }
        assertNotNull(pair, "no border pair sharing a 3-decimal key was found");
        assertTrue(validator.isInOperatingZone(pair[0].x, pair[0].y));
        assertFalse(validator.isInOperatingZone(pair[1].x, pair[1].y));
    }

    private static Geometry municipality(String admCode) throws Exception {
        List<SimpleFeature> f = ShpLoader.load(SHP_DIR + "polbnda_jpn.shp", "adm_code",
                Collections.singleton(admCode), null);
        Geometry union = null;
        for (SimpleFeature s : f) {
            Geometry g = (Geometry) s.getDefaultGeometry();
            union = union == null ? g : union.union(g);
        }
        assertNotNull(union, "municipality " + admCode + " not found");
        return union;
    }

    private static Coordinate towards(Coordinate from, Coordinate to, double metres) {
        double cosLat = Math.cos(Math.toRadians(from.y));
        double dx = (to.x - from.x) * 111_320.0 * cosLat;
        double dy = (to.y - from.y) * 111_320.0;
        double len = Math.hypot(dx, dy);
        return new Coordinate(from.x + (dx / len) * metres / (111_320.0 * cosLat),
                from.y + (dy / len) * metres / 111_320.0);
    }

    private static org.locationtech.jts.geom.Point point(Coordinate c) {
        return new org.locationtech.jts.geom.GeometryFactory().createPoint(c);
    }

    private static String key(Coordinate c) {
        return String.format("%.3f,%.3f", c.x, c.y);
    }
}
