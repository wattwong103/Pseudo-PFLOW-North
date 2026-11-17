package pseudo.pre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * TASK-17: PersonGenerator read only the first config.properties on the classpath, so
 * config.local.properties and -Dconfig.file could not move its output, and the committed
 * default writes into data/census/person/agent/ (gufm's input). It now reads the same layers
 * as the other generators (utils.ConfigLoader).
 *
 * These tests never call main(): on code without the fix that would write into the real
 * data/census/person/agent/.
 */
public class PersonGeneratorConfigTest {

	private static void write(Path file, String text) throws Exception {
		try (PrintWriter w = new PrintWriter(file.toFile(), "UTF-8")) {
			w.print(text);
		}
	}

	private static ClassLoader configDir(String base, String local) throws Exception {
		Path dir = Files.createTempDirectory("pg-config");
		dir.toFile().deleteOnExit();
		write(dir.resolve("config.properties"), base);
		if (local != null) {
			write(dir.resolve("config.local.properties"), local);
		}
		// parent null: see only this folder's config files, not the build's own
		return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
	}

	@Test
	public void localPropertiesOverrideTheBase() throws Exception {
		ClassLoader cl = configDir("legacy.stat.root=/base/census/\n", "legacy.stat.root=/run/census/\n");
		assertEquals("/run/census/", PersonGenerator.statRoot(PersonGenerator.loadConfig(cl)));
	}

	@Test
	public void configFilePropertyOverridesEverything() throws Exception {
		ClassLoader cl = configDir("legacy.stat.root=/base/census/\n", "legacy.stat.root=/run/census/\n");
		File ext = File.createTempFile("pg-ext", ".properties");
		ext.deleteOnExit();
		write(ext.toPath(), "legacy.stat.root=/ext/census/\n");
		String before = System.getProperty("config.file");
		System.setProperty("config.file", ext.getAbsolutePath());
		try {
			assertEquals("/ext/census/", PersonGenerator.statRoot(PersonGenerator.loadConfig(cl)));
		} finally {
			if (before == null) {
				System.clearProperty("config.file");
			} else {
				System.setProperty("config.file", before);
			}
		}
	}

	@Test
	public void missingStatRootStopsTheRun() throws Exception {
		Properties prop = new Properties();
		assertThrows(IllegalStateException.class, () -> PersonGenerator.statRoot(prop));
	}
}
