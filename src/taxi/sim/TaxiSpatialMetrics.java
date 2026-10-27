package taxi.sim;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Measured spatial and temporal metrics. They replace the two validation rows
 * that used to be a config echo (Nearby Trip Ratio) and a constant (Trips
 * Within City Bounds), and add the operating-zone constraint shares.
 * Percentages; NaN when there is nothing to measure.
 */
final class TaxiSpatialMetrics {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private TaxiSpatialMetrics() {}

    /**
     * Percent of passenger legs whose pickup lies within radiusKm (great-circle)
     * of the same taxi's previous passenger drop-off. Each taxi's first
     * passenger leg has no previous drop-off and is skipped; empty legs are
     * ignored. Walks {@link TaxiAgent#getAssignedTrips()} in time order.
     */
    static double nearbyTripRatio(List<TaxiAgent> taxis, double radiusKm) {
        long eligible = 0;
        long near = 0;
        for (TaxiAgent taxi : taxis) {
            TaxiTrip previous = null;
            for (TaxiTrip trip : taxi.getAssignedTrips()) {
                if (!trip.isPassengerIn()) continue;
                if (previous != null) {
                    eligible++;
                    if (greatCircleKm(previous.getDropoffLongitude(), previous.getDropoffLatitude(),
                            trip.getPickupLongitude(), trip.getPickupLatitude()) <= radiusKm) {
                        near++;
                    }
                }
                previous = trip;
            }
        }
        return eligible == 0 ? Double.NaN : 100.0 * near / eligible;
    }

    /** Percent of passenger legs whose pickup satisfies inside(lon, lat). */
    static double pickupInsideShare(List<TaxiTrip> trips, BiPredicate<Double, Double> inside) {
        long n = 0;
        long hit = 0;
        for (TaxiTrip t : trips) {
            if (!t.isPassengerIn()) continue;
            n++;
            if (inside.test(t.getPickupLongitude(), t.getPickupLatitude())) hit++;
        }
        return n == 0 ? Double.NaN : 100.0 * hit / n;
    }

    /** Percent of passenger legs with both pickup and drop-off outside. */
    static double bothEndsOutsideShare(List<TaxiTrip> trips, BiPredicate<Double, Double> inside) {
        long n = 0;
        long hit = 0;
        for (TaxiTrip t : trips) {
            if (!t.isPassengerIn()) continue;
            n++;
            if (!inside.test(t.getPickupLongitude(), t.getPickupLatitude())
                    && !inside.test(t.getDropoffLongitude(), t.getDropoffLatitude())) {
                hit++;
            }
        }
        return n == 0 ? Double.NaN : 100.0 * hit / n;
    }

    /** Percent of passenger legs with both ends inside the lon/lat box. */
    static double bothEndsInBoxShare(List<TaxiTrip> trips,
                                     double minLon, double maxLon, double minLat, double maxLat) {
        BiPredicate<Double, Double> inBox = (lon, lat) ->
            lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
        long n = 0;
        long hit = 0;
        for (TaxiTrip t : trips) {
            if (!t.isPassengerIn()) continue;
            n++;
            if (inBox.test(t.getPickupLongitude(), t.getPickupLatitude())
                    && inBox.test(t.getDropoffLongitude(), t.getDropoffLatitude())) {
                hit++;
            }
        }
        return n == 0 ? Double.NaN : 100.0 * hit / n;
    }

    /**
     * Time-based vacancy (Chu 2017 uses distance; this is its time analogue):
     * percent of scheduled shift time not spent with a passenger on board.
     */
    static double vacancyTimeShare(List<TaxiAgent> taxis) {
        double shiftSeconds = 0.0;
        double loadedSeconds = 0.0;
        for (TaxiAgent taxi : taxis) {
            shiftSeconds += taxi.getShiftEndTime() - taxi.getShiftStartTime();
            for (TaxiTrip trip : taxi.getAssignedTrips()) {
                if (trip.isPassengerIn()) loadedSeconds += trip.getDropoffTime() - trip.getPickupTime();
            }
        }
        return shiftSeconds <= 0 ? Double.NaN : 100.0 * (1.0 - loadedSeconds / shiftSeconds);
    }

    static double greatCircleKm(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
