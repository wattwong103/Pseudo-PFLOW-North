package pseudo.acs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.Test;

import jp.ac.ut.csis.pflow.geom2.LonLat;
import pseudo.res.EGender;
import pseudo.res.ELabor;
import pseudo.res.EPurpose;
import pseudo.res.ETransport;
import pseudo.res.Person;
import pseudo.res.SPoint;
import pseudo.res.Trip;

/**
 * v3.3 trip CSV: one row per logical trip, unique trip_id, explicit arr_time + duration.
 */
public class PersonAccessorTripFormatTest {

	private static final long PID_A = 33922891L;
	private static final long PID_B = 33922892L;

	private static Person person(long id) {
		return new Person(null, id, 40, EGender.MALE, ELabor.WORKER);
	}

	private static Trip leg(ETransport mode, EPurpose purpose, long dep, long arr,
			double olon, double olat, double dlon, double dlat,
			long generatorTripId, int subtripId, ETransport repMode) {
		Trip t = new Trip(mode, purpose, dep, new LonLat(olon, olat), new LonLat(dlon, dlat));
		t.setTripId(generatorTripId);
		t.setSubtripId(subtripId);
		t.setRepMode(repMode);
		t.setArrTime(arr);
		return t;
	}

	private static File tempFile(String prefix) throws Exception {
		File f = File.createTempFile(prefix, ".csv");
		f.deleteOnExit();
		return f;
	}

	private static List<String[]> readRows(File f) throws Exception {
		List<String[]> rows = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(f))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (!line.isEmpty()) {
					rows.add(line.split(",", -1));
				}
			}
		}
		return rows;
	}

	@Test
	public void mixedThreeLegsCollapseToOneRowWithTrainMode() throws Exception {
		Person p = person(PID_A);
		p.addTrip(leg(ETransport.WALK, EPurpose.OFFICE, 28000, 28500,
				139.74, 35.68, 139.75, 35.68, 1, 0, ETransport.TRAIN));
		p.addTrip(leg(ETransport.TRAIN, EPurpose.OFFICE, 28500, 28700,
				139.75, 35.68, 139.76, 35.69, 1, 1, ETransport.TRAIN));
		p.addTrip(leg(ETransport.WALK, EPurpose.OFFICE, 28700, 28800,
				139.76, 35.69, 139.77, 35.70, 1, 2, ETransport.TRAIN));

		File f = tempFile("trip-mixed");
		PersonAccessor.writeTrips(f.getAbsolutePath(), Arrays.asList(p));
		List<String[]> rows = readRows(f);

		assertEquals("mixed legs must collapse to one CSV row", 1, rows.size());
		String[] r = rows.get(0);
		assertEquals(12, r.length);
		assertEquals(Long.toString(PID_A), r[0]);
		assertEquals("28000", r[1]);
		assertEquals(139.74, Double.parseDouble(r[2]), 1e-5);
		assertEquals(35.68, Double.parseDouble(r[3]), 1e-5);
		assertEquals(139.77, Double.parseDouble(r[4]), 1e-5);
		assertEquals(35.70, Double.parseDouble(r[5]), 1e-5);
		assertEquals("representative mode is TRAIN", "4", r[6]);
		assertEquals(Integer.toString(EPurpose.OFFICE.getId()), r[7]);
		assertEquals(Integer.toString(ELabor.WORKER.getId()), r[8]);
		assertEquals(Long.toString(PID_A * 1000 + 1), r[9]);
		assertEquals("28800", r[10]);
		assertEquals(Long.toString(86400 - 28000), r[11]);
	}

	@Test
	public void twoPersonsSeqOneGetDifferentUids() throws Exception {
		Person a = person(PID_A);
		a.addTrip(leg(ETransport.WALK, EPurpose.HOME, 3600, 3700,
				139.74, 35.68, 139.75, 35.68, 1, 0, ETransport.WALK));
		Person b = person(PID_B);
		b.addTrip(leg(ETransport.WALK, EPurpose.HOME, 3600, 3700,
				139.74, 35.68, 139.75, 35.68, 1, 0, ETransport.WALK));

		File f = tempFile("trip-uids");
		PersonAccessor.writeTrips(f.getAbsolutePath(), Arrays.asList(a, b));
		List<String[]> rows = readRows(f);
		assertEquals(2, rows.size());
		assertEquals(Long.toString(PID_A * 1000 + 1), rows.get(0)[9]);
		assertEquals(Long.toString(PID_B * 1000 + 1), rows.get(1)[9]);
	}

	@Test
	public void stayHomeWritesOneRowDurationFullDay() throws Exception {
		Person p = person(50L);
		p.addTrip(leg(ETransport.NOT_DEFINED, EPurpose.HOME, 0, 0,
				139.74, 35.68, 139.74, 35.68, 1, 0, ETransport.NOT_DEFINED));

		File f = tempFile("trip-stay");
		PersonAccessor.writeTrips(f.getAbsolutePath(), Arrays.asList(p));
		String[] r = readRows(f).get(0);
		assertEquals(12, r.length);
		assertEquals("0", r[1]);
		assertEquals("0", r[6]);
		assertEquals(Long.toString(50L * 1000 + 1), r[9]);
		assertEquals("0", r[10]);
		assertEquals("86400", r[11]);
	}

	@Test
	public void durationEqualsGapToNextDep() throws Exception {
		Person p = person(PID_A);
		p.addTrip(leg(ETransport.TRAIN, EPurpose.OFFICE, 28000, 28800,
				139.74, 35.68, 139.77, 35.70, 1, 0, ETransport.TRAIN));
		p.addTrip(leg(ETransport.TRAIN, EPurpose.HOME, 61200, 62000,
				139.77, 35.70, 139.74, 35.68, 2, 0, ETransport.TRAIN));

		File f = tempFile("trip-dur");
		PersonAccessor.writeTrips(f.getAbsolutePath(), Arrays.asList(p));
		List<String[]> rows = readRows(f);
		assertEquals(2, rows.size());
		assertEquals("33200", rows.get(0)[11]); // 61200 - 28000
		assertEquals("25200", rows.get(1)[11]); // 86400 - 61200
		assertEquals(Long.toString(PID_A * 1000 + 1), rows.get(0)[9]);
		assertEquals(Long.toString(PID_A * 1000 + 2), rows.get(1)[9]);
	}

	@Test
	public void writeThenLoadPreservesUidArrDuration() throws Exception {
		Person p = person(PID_A);
		p.addTrip(leg(ETransport.CAR, EPurpose.SHOPPING, 40000, 40600,
				139.74, 35.68, 139.80, 35.70, 7, 0, ETransport.CAR));
		p.addTrip(leg(ETransport.CAR, EPurpose.HOME, 50000, 50600,
				139.80, 35.70, 139.74, 35.68, 8, 0, ETransport.CAR));

		File f = tempFile("trip-round");
		PersonAccessor.writeTrips(f.getAbsolutePath(), Arrays.asList(p));
		List<Person> loaded = PersonAccessor.loadTrips(f.getAbsolutePath());
		assertEquals(1, loaded.size());
		assertEquals(PID_A, loaded.get(0).getId());
		assertEquals(2, loaded.get(0).listTrips().size());

		Trip t0 = loaded.get(0).listTrips().get(0);
		assertEquals(PID_A * 1000 + 1, t0.getTripId());
		assertEquals(40000L, t0.getDepTime());
		assertEquals(40600L, t0.getArrTime());
		assertEquals(ETransport.CAR, t0.getTransport());

		File f2 = tempFile("trip-round2");
		PersonAccessor.writeTrips(f2.getAbsolutePath(), loaded);
		List<String[]> again = readRows(f2);
		assertEquals(readRows(f).size(), again.size());
		assertEquals(readRows(f).get(0)[9], again.get(0)[9]);
		assertEquals(readRows(f).get(0)[10], again.get(0)[10]);
		assertEquals(readRows(f).get(0)[11], again.get(0)[11]);
	}

	@Test
	public void thousandTripsThrows() throws Exception {
		Person p = person(PID_A);
		for (int i = 1; i <= 1000; i++) {
			p.addTrip(leg(ETransport.WALK, EPurpose.FREE, i * 10L, i * 10L + 5,
					139.74, 35.68, 139.75, 35.68, i, 0, ETransport.WALK));
		}
		File f = tempFile("trip-too-many");
		try {
			PersonAccessor.writeTrips(f.getAbsolutePath(), Arrays.asList(p));
			fail("expected IllegalStateException for seq >= 1000");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("1000") || e.getMessage().contains("seq"));
		}
	}

	@Test
	public void trajectoryAppendsTripUid() throws Exception {
		Person p = person(PID_A);
		p.addTrip(leg(ETransport.WALK, EPurpose.OFFICE, 28000, 28800,
				139.74, 35.68, 139.77, 35.70, 3, 0, ETransport.WALK));
		SPoint pt = new SPoint(139.74, 35.68, new Date(28000_000L), ETransport.WALK, EPurpose.OFFICE);
		pt.setTripId(3);
		p.addTrajectory(pt);

		File f = tempFile("traj");
		PersonAccessor.writeTrajectory(f.getAbsolutePath(), Arrays.asList(p));
		List<String[]> rows = readRows(f);
		assertEquals(1, rows.size());
		assertTrue("trajectory row must append trip_id", rows.get(0).length >= 10);
		assertEquals(Long.toString(PID_A * 1000 + 1), rows.get(0)[9]);
	}
}
