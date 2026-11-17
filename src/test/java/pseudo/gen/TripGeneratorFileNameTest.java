package pseudo.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TASK-17: TripGenerator named its output from characters 9-13 of the input name, so the
 * person_13101.csv that ActivityGenerator writes became trip_101.c.csv.
 */
public class TripGeneratorFileNameTest {

	@Test
	public void activityGeneratorOutputNameGivesTheCityTripFile() {
		assertEquals("trip_13101.csv", TripGenerator.tripFileName("person_13101.csv"));
	}

	@Test
	public void activityPrefixGivesTheSameName() {
		assertEquals("trip_13101.csv", TripGenerator.tripFileName("activity_13101.csv"));
	}

	@Test
	public void otherNamesStopTheRunWithTheName() {
		for (String bad : new String[] {"person_1310.csv", "person_131011.csv", "person_13101.csv.bak",
				"13101.csv", "person_13101.txt", "person_ab101.csv"}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> TripGenerator.tripFileName(bad), bad);
			assertTrue(e.getMessage().contains(bad), e.getMessage());
		}
	}
}
