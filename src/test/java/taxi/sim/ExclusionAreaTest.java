package taxi.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import util.PathResolver;

/**
 * Non-road exclusion layer of {@link TaxiGeoValidator} (TASK-7, ruling P2): points inside
 * large parks, gardens, cemeteries and the Imperial Palace grounds are not valid taxi points.
 * Run with the checkout root as working directory (shapefile path is relative).
 */
class ExclusionAreaTest {

    static final String SHP_DIR = "src/shared/gm-jp/";
    static final double[] PALACE = {139.7528, 35.6852};
    static final double[] TOKYO_STATION = {139.7671, 35.6812};
    static final GeometryFactory GF = new GeometryFactory();

    static Geometry square(double lon, double lat, double half) {
        return GF.createPolygon(new Coordinate[] {
            new Coordinate(lon - half, lat - half), new Coordinate(lon + half, lat - half),
            new Coordinate(lon + half, lat + half), new Coordinate(lon - half, lat + half),
            new Coordinate(lon - half, lat - half)});
    }

    @Test
    void withoutALayerNothingIsExcluded() {
        TaxiGeoValidator v = new TaxiGeoValidator(SHP_DIR);
        assertFalse(v.isExclusionEnabled());
        assertFalse(v.isExcluded(PALACE[0], PALACE[1]));
    }

    @Test
    void pointsInsideAnExclusionPolygonAreExcludedAndInvalid() {
        TaxiGeoValidator v = new TaxiGeoValidator(SHP_DIR);
        boolean stationValidBefore = v.isValidLocation(TOKYO_STATION[0], TOKYO_STATION[1]);
        v.loadExclusionGeometries(List.of(square(PALACE[0], PALACE[1], 0.004)));
        assertTrue(v.isExclusionEnabled());
        assertTrue(v.isExcluded(PALACE[0], PALACE[1]));
        assertFalse(v.isValidLocation(PALACE[0], PALACE[1]));
        assertFalse(v.isExcluded(TOKYO_STATION[0], TOKYO_STATION[1]));
        assertEquals(stationValidBefore, v.isValidLocation(TOKYO_STATION[0], TOKYO_STATION[1]));
    }

    @Test
    void anEmptyPathLeavesTheLayerOff() {
        TaxiGeoValidator v = new TaxiGeoValidator(SHP_DIR);
        v.loadExclusionAreas("");
        assertFalse(v.isExclusionEnabled());
    }

    @Test
    void theBuiltTokyoLayerExcludesThePalaceButNotTokyoStation() {
        String path = PathResolver.resolve("${PFLOW_HOME}/data/spatial/tokyo_exclusion_areas.shp");
        assumeTrue(new File(path).exists(), "exclusion layer not built on this machine (data/ is not in git)");
        TaxiGeoValidator v = new TaxiGeoValidator(SHP_DIR);
        v.loadExclusionAreas(path);
        assertTrue(v.isExcluded(PALACE[0], PALACE[1]));
        assertFalse(v.isExcluded(TOKYO_STATION[0], TOKYO_STATION[1]));
    }
}
