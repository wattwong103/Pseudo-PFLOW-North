package pseudo.acs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.TimeZone;

import jp.ac.ut.csis.pflow.geom2.ILonLat;
import jp.ac.ut.csis.pflow.geom2.LonLat;
import pseudo.res.Activity;
import pseudo.res.EGender;
import pseudo.res.ELabor;
import pseudo.res.EPurpose;
import pseudo.res.ETransport;
import pseudo.res.GLonLat;
import pseudo.res.HouseHold;
import pseudo.res.Person;
import pseudo.res.SPoint;
import pseudo.res.Trip;

public class PersonAccessor{
	
	private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
	private static final long DAY_OF_DATE = 1601478000L;  //2020-10-01 00:00:00
	private static final long DAY_SECONDS = 86400L;
	private static final long TRIP_SEQ_MODULUS = 1000L;
	private static final int MAX_TRIPS_PER_PERSON = 999;
	/** trip_id written on a trajectory point whose trip cannot be identified. */
	private static final long UNKNOWN_TRIP_ID = 0L;
	private static final double STAY_HOME_COORD_EPS = 1e-8;

	
	public static List<HouseHold> load(String filename, int mfactor) {
		return load(filename, ELabor.values(), mfactor);
	}
	
	public static List<HouseHold> load(String filename, ELabor[] labors, int mfactor) {
		Set<ELabor> setLabors = new HashSet<>();
		List<HouseHold> data = new ArrayList<>();
		setLabors.addAll(Arrays.asList(labors));
		
		int counter = 0;
		try (BufferedReader br = new BufferedReader(new FileReader(filename));){
            // Person files are headerless (write() below and PersonGenerator write none): line 1 is a person.
            // A 9-column header row fails on its first number; a shorter one is skipped like any short row.
            String line;
            HouseHold household = null;
            while ((line = br.readLine()) != null) {
            	String[] items = line.split(",");
            	if (items.length < 9) {
            		System.err.println("PersonAccessor.load: skipping short row (" + items.length + " cols): " + line);
            		continue;
            	}
            	String householdId = String.valueOf(items[0]);
            	if (household == null || !householdId.equals(household.getId())) {
            		int family = Integer.valueOf(items[1]);
            		String gyousei = items[2];
            		double lon = Double.valueOf(items[3]);
            		double lat = Double.valueOf(items[4]);
                	
                	household = new HouseHold(
                			householdId, family, gyousei, new GLonLat(lon,lat,gyousei));
            		data.add(household);
            	}
            	
            	long personId = Long.valueOf(items[5]);
            	int age = Integer.valueOf(items[6]);
            	EGender gender = EGender.getType(Integer.valueOf(items[7]));
            	ELabor labor = ELabor.getType(Integer.valueOf(items[8]));
            	
            	if (setLabors.contains(labor)) {
            		if (counter++ % mfactor == 0) {
            			new Person(household, personId, age, gender, labor);
            		}
            	}
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load person data: " + filename, e);
        }
		return data;
	}
	
	public static void write(String filename, List<HouseHold> data) {
		try(BufferedWriter bw = new BufferedWriter(new FileWriter(filename));){
			for (HouseHold house : data) {
				ILonLat home = house.getHome();
				for (Person p : house.getListPersons()) {
					bw.write(
							String.format("%s,%d,%s,%f,%f,%d,%d,%d,%d", 
									house.getId(),
									house.getFamilyType(), 
									house.getGcode(), 
									home.getLon(),
									home.getLat(),
									p.getId(),
									p.getAge(),
									p.getGender().getId(),
									p.getLabor().getId())							
							);
					bw.newLine();		
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to write person data: " + filename, e);
		}
	}

	public static void writeActivities(String filename, List<HouseHold> data) {
		try(BufferedWriter bw = new BufferedWriter(new FileWriter(filename));){
			for (HouseHold house : data) {
				for (Person p : house.getListPersons()) {
					List<Activity> activities = p.getActivities();
					for (Activity act : activities) {
						bw.write(
							String.format("%d,%d,%d,%d,%d,%d,%d,%f,%f,%s", 
									p.getId(),
									p.getAge(),
									p.getGender().getId(),
									p.getLabor().getId(),
									act.getStartTime(),
									act.getDuration(),
									act.getPurpose().getId(),
									act.getX(),
									act.getY(),
									act.getGcode()
									));
						bw.newLine();	
					}					
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to write activities: " + filename, e);
		}
	}

	public static List<Person> loadActivity(String filename, int scale, Double carratio, Double bikeratio) {
		List<Person> res = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(filename));){
            String line = null;
            Person person = null;
            int preId = 0;
            int counter = 0;
            while ((line = br.readLine()) != null) {
            	String[] items = line.split(",");
            	if (items.length < 10) {
            		System.err.println("PersonAccessor.loadActivity: skipping short row (" + items.length + " cols): " + line);
            		continue;
            	}
            	int nextId = Integer.valueOf(items[0]);
            	int age = Integer.valueOf(items[1]);
            	EGender gender = EGender.getType(Integer.valueOf(items[2]));
            	ELabor labor = ELabor.getType(Integer.valueOf(items[3]));

            	if (nextId != preId) {
            		person = new Person(null, nextId, age, gender, labor);
					Random random = new Random();
					Boolean carownership = (age >= 20) && (random.nextDouble() < carratio);
					person.setCarOwner(carownership);
					Boolean bikeownership = random.nextDouble() < bikeratio;
					person.setBikeOwner(bikeownership);
            		if (counter++ % scale == 0) {
            			res.add(person);
            		}
            	}

            	long startTime = Long.valueOf(items[4]);
            	long duration = Long.valueOf(items[5]);
            	EPurpose purpose = EPurpose.getType(Integer.valueOf(items[6]));
            	double lon = Double.valueOf(items[7]);
            	double lat = Double.valueOf(items[8]);
            	String gcode = String.valueOf(items[9]);

            	Activity activity = new Activity(
            			new GLonLat(lon, lat, gcode), startTime, duration, purpose);
            	person.addAcitivity(activity);

            	preId = nextId;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load activity data: " + filename, e);
        }
		return res;
	}
	
	public static void writeTrips(String filename, List<Person> data) {
		writeTrips(filename, data, false);
	}

	public static long encodeTripId(long personId, int seq) {
		if (seq < 1 || seq > MAX_TRIPS_PER_PERSON) {
			throw new IllegalStateException(
					"Person " + personId + " trip seq " + seq + " exceeds ceiling " + MAX_TRIPS_PER_PERSON);
		}
		return personId * TRIP_SEQ_MODULUS + seq;
	}

	/**
	 * Collapse in-memory legs into one row per logical trip and assign
	 * {@code trip_id = person_id * 1000 + seq} (seq is 1-based, dep-time order).
	 */
	public static List<CollapsedTrip> collapseTrips(Person person) {
		List<Trip> legs = person.listTrips();
		Map<Long, List<Trip>> groups = new LinkedHashMap<>();
		int ordinal = 0;
		for (Trip t : legs) {
			// tripId==0 means the row was never given a generator id (legacy
			// 9-col load). Do not collapse those together — each row is a trip.
			long key = t.getTripId() == 0L ? Long.MIN_VALUE + ordinal : t.getTripId();
			ordinal++;
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
		}
		for (List<Trip> g : groups.values()) {
			g.sort(Comparator.comparingInt(Trip::getSubtripId));
		}
		List<List<Trip>> ordered = new ArrayList<>(groups.values());
		ordered.sort(Comparator.comparingLong(g -> g.get(0).getDepTime()));
		if (ordered.size() > MAX_TRIPS_PER_PERSON) {
			throw new IllegalStateException(
					"Person " + person.getId() + " has " + ordered.size()
					+ " trips; seq must be < " + TRIP_SEQ_MODULUS);
		}

		List<CollapsedTrip> out = new ArrayList<>(ordered.size());
		int n = ordered.size();
		for (int i = 0; i < n; i++) {
			List<Trip> g = ordered.get(i);
			Trip first = g.get(0);
			Trip last = g.get(g.size() - 1);
			int seq = i + 1;
			long uid = encodeTripId(person.getId(), seq);
			long dep = first.getDepTime();
			long nextDep = (i + 1 < n) ? ordered.get(i + 1).get(0).getDepTime() : DAY_SECONDS;
			long duration = Math.max(0L, nextDep - dep);

			ETransport mode = last.getRepMode();
			if (mode == null || mode == ETransport.MIX) {
				mode = ETransport.NOT_DEFINED;
				for (Trip t : g) {
					// computeRepMode ranks MIX below WALK, so a MIX leg is counted as the rail it stands for
					ETransport legMode = t.getTransport() == ETransport.MIX ? ETransport.TRAIN : t.getTransport();
					mode = Trip.computeRepMode(mode, legMode);
				}
			}

			long arr = last.getArrTime();
			boolean fallback = false;
			if (arr == 0L && !isStayHome(first, last)) {
				arr = dep;
				fallback = true;
			}

			out.add(new CollapsedTrip(
					person.getId(), uid, first.getTripId(),
					dep, arr, duration,
					first.getOrigin(), last.getDestination(),
					mode, last.getPurpose(), person.getLabor(),
					fallback));
		}
		return out;
	}

	private static boolean isStayHome(Trip first, Trip last) {
		double dlon = first.getOrigin().getLon() - last.getDestination().getLon();
		double dlat = first.getOrigin().getLat() - last.getDestination().getLat();
		return Math.abs(dlon) < STAY_HOME_COORD_EPS
				&& Math.abs(dlat) < STAY_HOME_COORD_EPS
				&& first.getTransport() == ETransport.NOT_DEFINED;
	}

	public static void writeTrips(String filename, List<Person> data, boolean append) {
		// Collapse the whole batch before opening the file: a person over the trip ceiling
		// throws here and leaves the file as it was, instead of a half-written batch.
		List<List<CollapsedTrip>> batch = new ArrayList<>(data.size());
		for (Person p : data) {
			batch.add(collapseTrips(p));
		}
		boolean warnedArr = false;
		try(BufferedWriter bw = new BufferedWriter(new FileWriter(filename, append));){
			for (List<CollapsedTrip> collapsed : batch) {
				for (CollapsedTrip trip : collapsed) {
					if (trip.arrTimeFallback && !warnedArr) {
						System.err.println("PersonAccessor.writeTrips: arrTime missing on moving trips; using depTime (zero travel). " + filename);
						warnedArr = true;
					}
					ILonLat o = trip.origin;
					ILonLat d = trip.destination;
					bw.write(
						String.format("%d,%d,%f,%f,%f,%f,%d,%d,%d,%d,%d,%d",
								trip.personId,
								trip.depTime,
								o.getLon(), o.getLat(),
								d.getLon(), d.getLat(),
								trip.mode.getId(),
								trip.purpose.getId(),
								trip.labor.getId(),
								trip.tripId,
								trip.arrTime,
								trip.duration
							));
					bw.newLine();
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to write trips: " + filename, e);
		}
	}


	/**
	 * Read a trip CSV: v3.3 rows (12 columns, ..., trip_id, arr_time, duration) or legacy 9-column rows.
	 * A legacy row is one trip and gets {@code trip_id = person_id * 1000 + seq} in file order, so a
	 * trajectory made from it can name its trip. v3.2 subtrip rows (12 columns ending trip_id,
	 * subtrip_id, rep_mode) are refused: their 11th column is not an arrival time.
	 */
	public static List<Person> loadTrips(String filename) {
		List<Person> res = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(filename));){
            String line = null;
            Person person = null;
            long preId = 0;
            int legacySeq = 0;
            V33Chain chain = new V33Chain();
            while ((line = br.readLine()) != null) {
            	String[] items = line.split(",");
            	if (items.length < 9) {
            		System.err.println("PersonAccessor.loadTrips: skipping short row (" + items.length + " cols): " + line);
            		continue;
            	}
            	long nextId = Long.parseLong(items[0]);

            	long depTime = Long.parseLong(items[1]);
            	double lon1 = Double.parseDouble(items[2]);
            	double lat1 = Double.parseDouble(items[3]);
            	double lon2 = Double.parseDouble(items[4]);
            	double lat2 = Double.parseDouble(items[5]);

            	ETransport mode = ETransport.getType(Integer.parseInt(items[6]));
            	EPurpose purpose = EPurpose.getType(Integer.parseInt(items[7]));
            	ELabor labor = ELabor.getType(Integer.parseInt(items[8]));

            	if (person == null || nextId != preId) {
            		person = new Person(null, nextId, 0, EGender.MALE, labor);
            		res.add(person);
            		legacySeq = 0;
            	}

            	Trip trip = new Trip(mode, purpose, depTime, new LonLat(lon1,lat1), new LonLat(lon2,lat2));
            	if (items.length >= 12) {
            		long tripId = Long.parseLong(items[9]);
            		long arrTime = Long.parseLong(items[10]);
            		// A v3.3 row has trip_id = pid*1000+seq and arrives no earlier than it departs;
            		// a v3.2 row's small trip counter or its subtrip_id (0-4, read as arr_time) breaks one.
            		if (tripId / TRIP_SEQ_MODULUS != nextId || tripId % TRIP_SEQ_MODULUS == 0 || arrTime < depTime) {
            			throw new IllegalArgumentException("person " + nextId + " row with trip_id " + tripId
            					+ ", dep " + depTime + ", arr " + arrTime + " is not a v3.3 row (trip_id = person_id*1000+seq,"
            					+ " arr_time >= dep_time): this looks like a v3.2 subtrip row (trip_id, subtrip_id,"
            					+ " rep_mode), which loadTrips does not read; it reads v3.3 rows (trip_id, arr_time,"
            					+ " duration) and 9-column rows");
            		}
            		chain.accept(nextId, tripId % TRIP_SEQ_MODULUS, depTime, Long.parseLong(items[11]));
            		trip.setTripId(tripId);
            		trip.setArrTime(arrTime);
            		trip.setSubtripId(0);
            		trip.setRepMode(mode);
            	} else {
            		trip.setTripId(encodeTripId(nextId, ++legacySeq));
            	}
            	person.addTrip(trip);

            	preId = nextId;
            }
            chain.finish();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load trips: " + filename, e);
        }
		return res;
	}

	/**
	 * How writeTrips chains a person's v3.3 rows: seq 1, 2, 3 in file order, each duration the gap to
	 * the next departure, the last one 86400 - dep_time (never below 0). One row alone can look like
	 * a v3.2 row (a midnight stay-home row with trip_id 1001); a person's rows together cannot.
	 */
	private static final class V33Chain {
		private boolean started = false;
		private long pid;
		private long seq;
		private long dep;
		private long duration;

		void accept(long nextPid, long nextSeq, long nextDep, long nextDuration) {
			if (started && nextPid == pid) {
				long gap = Math.max(0L, nextDep - dep);
				if (nextSeq != seq + 1 || duration != gap) {
					throw notV33("person " + pid + ": trip seq " + nextSeq + " after seq " + seq
							+ " with duration " + duration + " (gap to this departure " + gap + ")");
				}
			} else {
				finish();
				if (nextSeq != 1) {
					throw notV33("person " + nextPid + "'s first trip has seq " + nextSeq);
				}
			}
			started = true;
			pid = nextPid;
			seq = nextSeq;
			dep = nextDep;
			duration = nextDuration;
		}

		void finish() {
			if (started && duration != Math.max(0L, DAY_SECONDS - dep)) {
				throw notV33("person " + pid + "'s last trip (seq " + seq + ") has duration " + duration
						+ ", not " + Math.max(0L, DAY_SECONDS - dep) + " (86400 - dep_time)");
			}
		}

		private static IllegalArgumentException notV33(String what) {
			return new IllegalArgumentException(what + ": not a v3.3 trip file (a v3.2 subtrip file?)");
		}
	}

	public static final class CollapsedTrip {
		public final long personId;
		public final long tripId;
		public final long generatorTripId;
		public final long depTime;
		public final long arrTime;
		public final long duration;
		public final ILonLat origin;
		public final ILonLat destination;
		public final ETransport mode;
		public final EPurpose purpose;
		public final ELabor labor;
		public final boolean arrTimeFallback;

		CollapsedTrip(long personId, long tripId, long generatorTripId,
				long depTime, long arrTime, long duration,
				ILonLat origin, ILonLat destination,
				ETransport mode, EPurpose purpose, ELabor labor,
				boolean arrTimeFallback) {
			this.personId = personId;
			this.tripId = tripId;
			this.generatorTripId = generatorTripId;
			this.depTime = depTime;
			this.arrTime = arrTime;
			this.duration = duration;
			this.origin = origin;
			this.destination = destination;
			this.mode = mode;
			this.purpose = purpose;
			this.labor = labor;
			this.arrTimeFallback = arrTimeFallback;
		}
	}
	
	
	public static List<Person> loadTrajectory(String filename) {
		List<Person> res = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(filename));){
            String line = null;
            Person person = null;
            int preId = 0;
            while ((line = br.readLine()) != null) {
            	String[] items = line.split(",");
            	if (items.length < 8) {
            		System.err.println("PersonAccessor.loadTrajectory: skipping short row (" + items.length + " cols): " + line);
            		continue;
            	}
            	int nextId = Integer.valueOf(items[0]);

            	if (nextId != preId) {
            		person = new Person(null, nextId, 0, EGender.MALE, ELabor.UNDEFINED);
            		res.add(person);
            	}

            	long time = Long.valueOf(items[1]);

            	double lon = Double.valueOf(items[3]);
            	double lat = Double.valueOf(items[4]);
            	ETransport mode = ETransport.getType(Integer.valueOf(items[5]));
            	EPurpose purpose = EPurpose.getType(Integer.valueOf(items[6]));
            	String link = String.valueOf(items[7]);

            	SPoint point = new SPoint(lon, lat, new Date(time), mode, purpose);
            	point.setLink(link);
            	if (items.length >= 10) {
            		point.setTripId(Long.parseLong(items[9]));
            	}
            	person.addTrajectory(point);

            	preId = nextId;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load trajectory: " + filename, e);
        }
		return res;
	}

	public static int writeTrajectory(String filename, List<Person> persons) {
		return writeTrajectory(filename, persons, false);
	}

	public static int writeTrajectory(String filename, List<Person> persons, boolean append) {
		SimpleDateFormat sfd = new SimpleDateFormat(DATE_FORMAT);

		try(BufferedWriter bw = new BufferedWriter(new FileWriter(filename, append));){
			for (Person person : persons) {
				// generator trip id -> written trip_id. An id shared by several written trips
				// (legacy trips that were never given one, all 0) cannot name a trip: write 0.
				Map<Long, Long> genToUid = new HashMap<>();
				boolean hasTrips = person.listTrips() != null && !person.listTrips().isEmpty();
				if (hasTrips) {
					Set<Long> shared = new HashSet<>();
					for (CollapsedTrip c : collapseTrips(person)) {
						if (genToUid.put(c.generatorTripId, c.tripId) != null) {
							shared.add(c.generatorTripId);
						}
					}
					for (Long id : shared) {
						genToUid.put(id, UNKNOWN_TRIP_ID);
					}
				}
				List<SPoint> points = person.getTrajectory();
				if (points != null) {
					for (int i = 0; i < points.size(); i++) {
						SPoint p = points.get(i);
						String link = p.getLink();
						long unixtime = p.getTimeStamp().getTime();
						String date = sfd.format(unixtime);
						// No trips loaded (re-writing a trajectory file): keep the id the point carries.
						long uid = hasTrips ? genToUid.getOrDefault(p.getTripId(), UNKNOWN_TRIP_ID) : p.getTripId();

						bw.write(
								String.format("%d,%d,%s,%f,%f,%d,%d,%s,%s,%d",
									person.getId(),
									// time.getTime(),
									unixtime,
									// sfd.format(p.getTimeStamp()),
									date,
									p.getLon(),
									p.getLat(),
									p.getTransport().getId(),
									p.getPurpose().getId(),
									person.getLabor(),
									link != null ? link : "",
									uid
									));
						bw.newLine();
					}
				}
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to write trajectory: " + filename, e);
		}
		return 0;
	}
}
