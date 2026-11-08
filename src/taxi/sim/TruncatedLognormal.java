package taxi.sim;

import java.util.Random;

/**
 * Log-normal leg distances re-drawn into {@code [lo, hi]} (TASK-12).
 *
 * <p>Both leg samplers re-draw any draw that falls outside their bounds (B7.5), so the realized distribution is the
 * log-normal truncated to {@code [lo, hi]}. The closed-form calibration fixes the <em>mean of that bounded
 * distribution</em> (4.6 km loaded and 5.1 km empty for Tokyo FY2024). {@code mu = ln(m) - s^2/2} gives that mean
 * only without bounds: with {@code [0.3, 12]} km it yields 4.38 km for a 5.1 km target. {@link #muForBoundedMean}
 * solves for the location whose bounded mean is the target.
 */
final class TruncatedLognormal {

    /** Attempt budget of the re-draw loop before the clamp fallback (unchanged from B7.5). */
    static final int MAX_ATTEMPTS = 100;

    /** Minimum Simpson intervals over {@code [ln lo, ln hi]} (even). */
    private static final int MIN_INTERVALS = 4000;
    /** Grid points per log-scale sigma, so a narrow log-normal is still resolved (java-reviewer, TASK-12). */
    private static final int POINTS_PER_SIGMA = 50;

    private TruncatedLognormal() {
    }

    /**
     * Mean of {@code LN(mu, sigma^2)} truncated to {@code [lo, hi]}, by Simpson quadrature in log space.
     * Both integrands are scaled by their maximum exponent, so the ratio stays finite for any {@code mu}. The grid
     * has at least {@value #MIN_INTERVALS} intervals and {@value #POINTS_PER_SIGMA} per sigma; for the shipped
     * shapes (sigma 0.6-0.7 over [0.3, 30] km) that is 4,000 intervals and the relative error is below 1e-12.
     */
    static double boundedMean(double mu, double sigma, double lo, double hi) {
        validateShape(sigma, lo, hi);
        final double a = Math.log(lo);
        final double b = Math.log(hi);
        final int intervals = intervals(a, b, sigma);
        final double h = (b - a) / intervals;
        final double twoVar = 2.0 * sigma * sigma;
        // Exponent of the density in y = ln x is -(y-mu)^2/(2s^2); the mean's integrand adds y.
        final double yD = clamp(mu, a, b);
        final double yN = clamp(mu + sigma * sigma, a, b);
        final double maxD = -(yD - mu) * (yD - mu) / twoVar;
        final double maxN = -(yN - mu) * (yN - mu) / twoVar + yN;
        double sumD = 0.0;
        double sumN = 0.0;
        for (int i = 0; i <= intervals; i++) {
            final double y = a + i * h;
            final double w = (i == 0 || i == intervals) ? 1.0 : (i % 2 == 1 ? 4.0 : 2.0);
            final double e = -(y - mu) * (y - mu) / twoVar;
            sumD += w * Math.exp(e - maxD);
            sumN += w * Math.exp(e + y - maxN);
        }
        return Math.exp(maxN - maxD) * sumN / sumD;
    }

    /**
     * Location {@code mu} whose log-normal, truncated to {@code [lo, hi]}, has mean {@code mean}.
     * The bounded mean rises monotonically with {@code mu}, so the root is bracketed and then bisected.
     *
     * Supported range: {@code mean} strictly inside {@code (lo, hi)} and not within about 1e-6 of either bound,
     * where the numerical mean saturates and bracketing fails with an exception rather than a wrong answer.
     *
     * @throws IllegalArgumentException if {@code mean} is not strictly inside {@code (lo, hi)} (including NaN),
     *         or the shape or bounds are invalid
     */
    static double muForBoundedMean(double mean, double sigma, double lo, double hi) {
        validateShape(sigma, lo, hi);
        if (!(mean > lo && mean < hi)) {
            throw new IllegalArgumentException("bounded mean " + mean + " must lie strictly inside (" + lo + ", "
                + hi + ")");
        }
        final double start = Math.log(mean) - 0.5 * sigma * sigma;
        double low = start - 1.0;
        double high = start + 1.0;
        for (int i = 0; boundedMean(low, sigma, lo, hi) >= mean; i++) {
            if (i > 200) throw new IllegalArgumentException("cannot bracket mu for mean " + mean);
            low -= 1.0;
        }
        for (int i = 0; boundedMean(high, sigma, lo, hi) <= mean; i++) {
            if (i > 200) throw new IllegalArgumentException("cannot bracket mu for mean " + mean);
            high += 1.0;
        }
        for (int i = 0; i < 200 && high - low > 1e-13; i++) {
            final double mid = 0.5 * (low + high);
            if (boundedMean(mid, sigma, lo, hi) < mean) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * One distance from {@code LN(mu, sigma^2)}, re-drawn until it lies in {@code [lo, hi]} (B7.5). After
     * {@link #MAX_ATTEMPTS} draws it is clamped into the bounds (rare). One {@code nextGaussian()} per attempt, as
     * before TASK-12.
     */
    static double draw(Random random, double mu, double sigma, double lo, double hi) {
        double x;
        int attempts = 0;
        do {
            x = Math.exp(mu + sigma * random.nextGaussian());
            attempts++;
        } while ((x < lo || x > hi) && attempts < MAX_ATTEMPTS);
        if (x < lo || x > hi) {
            x = Math.max(lo, Math.min(hi, x));
        }
        return x;
    }

    private static void validateShape(double sigma, double lo, double hi) {
        if (!(sigma > 0.0) || Double.isInfinite(sigma)) {
            throw new IllegalArgumentException("sigma must be positive and finite: " + sigma);
        }
        if (!(lo > 0.0) || !(hi > lo) || Double.isInfinite(hi)) {
            throw new IllegalArgumentException("bounds must satisfy 0 < lo < hi < inf: [" + lo + ", " + hi + "]");
        }
    }

    private static int intervals(double a, double b, double sigma) {
        final int n = (int) Math.max(MIN_INTERVALS, Math.ceil((b - a) / sigma * POINTS_PER_SIGMA));
        return n % 2 == 0 ? n : n + 1;
    }

    private static double clamp(double v, double a, double b) {
        return Math.max(a, Math.min(b, v));
    }
}
