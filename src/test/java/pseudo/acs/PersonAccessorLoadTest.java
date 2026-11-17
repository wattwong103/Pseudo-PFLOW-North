package pseudo.acs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import pseudo.res.HouseHold;
import pseudo.res.Person;

/**
 * TASK-17: load() read and dropped the first line of a person file, but person files are
 * headerless (PersonGenerator writes them with write()), so the first person was lost.
 */
public class PersonAccessorLoadTest {

	// household_id, family_type, gcode, home_lon, home_lat, person_id, age, gender_id, labor_id
	private static final String[] ROWS = {
			"13101_1,4,13101,139.741445,35.683175,1,80,2,23",
			"13101_1,4,13101,139.741445,35.683175,2,78,1,23",
			"13101_2,1,13101,139.750000,35.690000,3,35,1,21",
	};

	private static File personFile(String... lines) throws Exception {
		File f = File.createTempFile("person_13101", ".csv");
		f.deleteOnExit();
		try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
			for (String line : lines) {
				w.println(line);
			}
		}
		return f;
	}

	private static List<Long> personIds(List<HouseHold> households) {
		List<Long> ids = new ArrayList<>();
		for (HouseHold h : households) {
			for (Person p : h.getListPersons()) {
				ids.add(p.getId());
			}
		}
		return ids;
	}

	@Test
	public void firstRowIsAPerson() throws Exception {
		List<HouseHold> households = PersonAccessor.load(personFile(ROWS).getAbsolutePath(), 1);
		assertEquals(List.of(1L, 2L, 3L), personIds(households));
		assertEquals("13101_1", households.get(0).getId());
	}

	@Test
	public void writeThenLoadKeepsEveryPerson() throws Exception {
		List<HouseHold> households = PersonAccessor.load(personFile(ROWS).getAbsolutePath(), 1);
		File out = File.createTempFile("person_round", ".csv");
		out.deleteOnExit();
		PersonAccessor.write(out.getAbsolutePath(), households);
		assertEquals(List.of(1L, 2L, 3L), personIds(PersonAccessor.load(out.getAbsolutePath(), 1)));
	}

	@Test
	public void headerRowFailsLoudly() throws Exception {
		File f = personFile("household_id,family_type,gcode,home_lon,home_lat,person_id,age,gender_id,labor_id",
				ROWS[0]);
		assertThrows(RuntimeException.class, () -> PersonAccessor.load(f.getAbsolutePath(), 1));
	}
}
