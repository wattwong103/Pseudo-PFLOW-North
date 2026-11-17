package pseudo.pre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * TASK-17: PersonGenerator read only the first config.properties on the classpath, so
 * config.local.properties and -Dconfig.file could not move its output, and the committed
 * default writes into data/census/person/agent/ (gufm's input). It now reads the same layers
 * as the other generators (utils.ConfigLoader).
 *
 * These tests never call main(): on code without the fix that would write into the real
 * data/census/person/agent/.
 */
@ResourceLock("config.file")
public class PersonGeneratorConfigTest {

	@TempDir
	Path tmp;

	private String savedConfigFile;

	@BeforeEach
	void clearConfigFile() {
		savedConfigFile = System.getProperty("config.file");
		System.clearProperty("config.file");
	}

	@AfterEach
	void restoreConfigFile() {
		if (savedConfigFile == null) {
			System.clearProperty("config.file");
		} else {
			System.setProperty("config.file", savedConfigFile);
		}
	}

	private static void write(Path file, String text) throws Exception {
		try (PrintWriter w = new PrintWriter(file.toFile(), "UTF-8")) {
			w.print(text);
		}
	}

	/** A loader that sees only tmp's config files (parent null: not the build's own). */
	private URLClassLoader configDir(String base, String local) throws Exception {
		write(tmp.resolve("config.properties"), base);
		if (local != null) {
			write(tmp.resolve("config.local.properties"), local);
		}
		return new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null);
	}

	@Test
	public void baseLayerAlone() throws Exception {
		try (URLClassLoader cl = configDir("legacy.stat.root=/base/census/\n", null)) {
			assertEquals("/base/census/", PersonGenerator.statRoot(PersonGenerator.loadConfig(cl)));
		}
	}

	@Test
	public void localPropertiesOverrideTheBase() throws Exception {
		try (URLClassLoader cl = configDir("legacy.stat.root=/base/census/\n", "legacy.stat.root=/run/census/\n")) {
			assertEquals("/run/census/", PersonGenerator.statRoot(PersonGenerator.loadConfig(cl)));
		}
	}

	@Test
	public void configFilePropertyOverridesEverything() throws Exception {
		Path ext = tmp.resolve("ext.properties");
		write(ext, "legacy.stat.root=/ext/census/\n");
		System.setProperty("config.file", ext.toString());
		try (URLClassLoader cl = configDir("legacy.stat.root=/base/census/\n", "legacy.stat.root=/run/census/\n")) {
			assertEquals("/ext/census/", PersonGenerator.statRoot(PersonGenerator.loadConfig(cl)));
		}
	}

	@Test
	public void pflowHomeTokenIsResolved() {
		Properties prop = new Properties();
		prop.setProperty("legacy.stat.root", "${PFLOW_HOME}/data/census/");
		String root = PersonGenerator.statRoot(prop);
		assertEquals(util.PathResolver.getPflowHome() + "/data/census/", root);
	}

	@Test
	public void missingStatRootStopsTheRun() {
		assertThrows(IllegalStateException.class, () -> PersonGenerator.statRoot(new Properties()));
	}
}
