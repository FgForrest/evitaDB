/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Writes the release upgrade fixtures with the RELEASED engines into `<testDataDir>`:
 *
 * - `<testDataDir>/release_upgrade_fixtures/<fixture>` - one storage directory per recipe of
 *   `evita_test/evita_test_support/src/main/java/io/evitadb/test/upgrade`, written by the v2026.1.20 engine
 *   (`Release2026_1FixtureRecipes`) or the v2026.2.18 engine (`Release2026_2FixtureRecipes` and its siblings). The
 *   2026.2 run then opens the 2026.1 fixtures that 2026.2 is to upgrade;
 * - `<testDataDir>/released_sort_2026_1/*.bin` - single sort storage parts written by the v2026.1.20 engine
 *   (`tools/fixtures/GenerateRelease2026_1SortParts.java`).
 *
 * The functional tests' build runs this program in `generate-test-resources`, with its test output directory's
 * `testData` as `<testDataDir>` (see `evita_test/evita_functional_tests/pom.xml`); the bytes are never committed. It
 * is a Java program rather than a shell script so that the build needs nothing beyond Maven and the JDK on any
 * operating system: the build starts it with the JDK that runs Maven, in source-file mode, and that JDK compiles the
 * recipes and runs the releases too.
 *
 * A run is skipped while its inputs - this program, the recipes and the sort-part generator - are unchanged and the
 * output in `<testDataDir>` is still the one written from them, compared by the name and size of each file. The output
 * is assembled in a staging folder inside `<testDataDir>`, so that moving it into place never crosses a file system,
 * and replaces the previous one only once complete. Class files, the throw-away poms and the engines' work folders go
 * to the work directory. A run that writes wipes both before and after.
 *
 * The recipes compile against the release jars because they use only the API the releases and the current engine
 * share. This program compiles all of them against the 2026.2 class path, and against the 2026.1 class path only the
 * ones that name nothing newer (`RECIPES_2026_1`), together with the sort-part generator.
 *
 * Release class paths: by default `mvn dependency:build-classpath` over a throw-away pom depending on
 * `io.evitadb:evita_db` and `io.evitadb:evita_export_fs` of the release version, which downloads the published release
 * artifacts on first use. The options hand that nested Maven the calling build's Maven installation, local repository,
 * offline mode and settings files; the build passes its own. An online run resolves offline first, so the network is
 * needed only until the local repository holds the release; when it cannot be reached, the build fails with a message
 * naming `-DreleaseFixtures.skip=true`, which builds without the fixtures and leaves the upgrade tests failing on the
 * fixture they miss. To use jars built from a tag instead (`git archive <tag> | tar -x -C <dir>`, then
 * `mvn package -DskipTests` there with `-Dmaven.repo.local=<scratch>` - never `install` a release tree into the shared
 * repository), put the class path into a file and pass it as `RELEASE_2026_1_CLASSPATH` / `RELEASE_2026_2_CLASSPATH`;
 * such a run always writes the fixtures and never marks them reusable, because the jars a supplied class path names
 * may change under the same name.
 *
 * Usage:
 *
 * ```
 * java tools/fixtures/GenerateReleaseFixtures.java --project-root=<dir> --work-dir=<dir> [--maven-home=<dir>]
 *      [--maven-repo-local=<dir>] [--maven-offline=true|false] [--maven-settings=<file>]
 *      [--maven-global-settings=<file>] <testDataDir>
 * ```
 */
public final class GenerateReleaseFixtures {
	/** The 2026.1 release whose engine writes the 2026.1 fixtures and sort parts. */
	private static final String RELEASE_2026_1 = "2026.1.20";
	/** The 2026.2 release whose engine writes the 2026.2 fixtures and upgrades the 2026.1 ones. */
	private static final String RELEASE_2026_2 = "2026.2.18";
	/** The goal that writes a release's class path, its plugin pinned so that the nested Maven resolves no version. */
	private static final String BUILD_CLASSPATH_GOAL =
		"org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath";
	/** Folder of the recipes, relative to the project root. */
	private static final String RECIPES = "evita_test/evita_test_support/src/main/java/io/evitadb/test/upgrade";
	/** This program, relative to the project root. */
	private static final String PROGRAM = "tools/fixtures/GenerateReleaseFixtures.java";
	/** The sort-part generator, relative to the project root. */
	private static final String SORT_PART_GENERATOR = "tools/fixtures/GenerateRelease2026_1SortParts.java";
	/** The recipes that name nothing newer than 2026.1, the only ones the 2026.1 engine compiles. */
	private static final List<String> RECIPES_2026_1 = List.of(
		"ReleaseFixtureOrigin.java",
		"ReleaseFixtureProbe.java",
		"ReleaseFixtureRecipe.java",
		"ReleaseFixtureValues.java",
		"ReleaseFixtureWriter.java",
		"Release2026_1FixtureRecipes.java",
		"Release2026_1FixtureGenerator.java"
	);
	/** Output folder of the catalogs, inside `<testDataDir>`. */
	private static final String FIXTURES = "release_upgrade_fixtures";
	/** Output folder of the sort parts, inside `<testDataDir>`. */
	private static final String SORT_PARTS = "released_sort_2026_1";
	/** The stamp of the output, inside `<testDataDir>`. */
	private static final String STAMP = ".release-fixtures.sha256";
	/** The staging folder the output is assembled in, inside `<testDataDir>`. */
	private static final String STAGING = ".release-fixtures-staging";
	/** Whether the program runs on Windows, where Maven starts from `mvn.cmd` and the JDK from `java.exe`. */
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

	/** Entry point only. */
	private GenerateReleaseFixtures() {
	}

	/**
	 * Writes the fixtures, or reports that they are up to date. Exits with 2 on a malformed command line, printing the
	 * usage, and with 1 when a step fails, printing why; the build fails on either.
	 *
	 * @param args the options and `<testDataDir>`, see the class documentation
	 */
	public static void main(String[] args) {
		final Options options;
		try {
			options = Options.parse(args);
		} catch (IllegalArgumentException ex) {
			System.err.println(ex.getMessage());
			System.exit(2);
			return;
		}
		try {
			generate(options);
		} catch (GenerationFailure | IOException | UncheckedIOException ex) {
			System.err.println(ex.getMessage());
			System.exit(1);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			System.err.println("Writing the release upgrade fixtures was interrupted.");
			System.exit(1);
		}
	}

	/**
	 * Writes the fixtures into `<testDataDir>` unless the stamp shows them up to date.
	 *
	 * @param options the parsed options
	 * @throws IOException          when a file cannot be read or written
	 * @throws InterruptedException when waiting for a child process is interrupted
	 */
	private static void generate(Options options) throws IOException, InterruptedException {
		final Path testData = Files.createDirectories(options.testDataDir()).toAbsolutePath().normalize();
		final Path work = options.workDir().toAbsolutePath().normalize();
		if (options.projectRoot().startsWith(work) || testData.startsWith(work) || work.startsWith(testData)) {
			throw new GenerationFailure(
				"The work directory " + work + " is or contains the project root or " + testData + ", or lies " +
					"inside the latter, and every run that writes deletes it: pass a folder that holds nothing else."
			);
		}
		final Path stamp = testData.resolve(STAMP);
		final String supplied2026_1 = suppliedClasspath("RELEASE_2026_1_CLASSPATH");
		final String supplied2026_2 = suppliedClasspath("RELEASE_2026_2_CLASSPATH");
		final boolean classpathSupplied = supplied2026_1 != null || supplied2026_2 != null;
		final String inputsDigest = hashInputs(options.projectRoot());
		if (!classpathSupplied && Files.isRegularFile(stamp) &&
			Files.readString(stamp).equals(currentStamp(inputsDigest, testData))) {
			System.out.println("The release upgrade fixtures in " + testData + " are up to date.");
			return;
		}

		final Path staging = testData.resolve(STAGING);
		deleteRecursively(work);
		deleteRecursively(staging);
		try {
			final Path fixtures = Files.createDirectories(staging.resolve(FIXTURES));
			final Path sortParts = Files.createDirectories(staging.resolve(SORT_PARTS));
			final String classpath2026_1 = resolveClasspath(options, work, RELEASE_2026_1, supplied2026_1);
			final String classpath2026_2 = resolveClasspath(options, work, RELEASE_2026_2, supplied2026_2);

			final Path recipes = options.projectRoot().resolve(RECIPES);
			final Path classes2026_1 = compile(
				classpath2026_1, RECIPES_2026_1.stream().map(recipes::resolve).toList(), work.resolve("classes-2026-1")
			);
			final Path classes2026_2 = compile(classpath2026_2, listRecipes(recipes), work.resolve("classes-2026-2"));
			final Path classesSort2026_1 = compile(
				classpath2026_1,
				List.of(options.projectRoot().resolve(SORT_PART_GENERATOR)),
				work.resolve("classes-sort-2026-1")
			);

			System.out.println("Writing the 2026.1 fixtures with v" + RELEASE_2026_1 + " ...");
			runJava(
				work, List.of(), classes2026_1 + File.pathSeparator + classpath2026_1,
				"io.evitadb.test.upgrade.Release2026_1FixtureGenerator",
				List.of("write", fixtures.toString(), work.resolve("work-2026-1").toString())
			);
			System.out.println(
				"Writing the 2026.2 fixtures and opening the 2026.1 ones with v" + RELEASE_2026_2 + " ..."
			);
			runJava(
				work, List.of(), classes2026_2 + File.pathSeparator + classpath2026_2,
				"io.evitadb.test.upgrade.Release2026_2FixtureGenerator",
				List.of("write", fixtures.toString(), work.resolve("work-2026-2").toString())
			);
			System.out.println("Writing the 2026.1 sort parts with v" + RELEASE_2026_1 + " ...");
			runJava(
				work,
				List.of("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED"),
				classesSort2026_1 + File.pathSeparator + classpath2026_1,
				"GenerateRelease2026_1SortParts",
				List.of(sortParts.toString())
			);

			// the stamp goes first and returns last, so an interrupted replacement is written again by the next run
			Files.deleteIfExists(stamp);
			deleteRecursively(testData.resolve(FIXTURES));
			deleteRecursively(testData.resolve(SORT_PARTS));
			Files.move(fixtures, testData.resolve(FIXTURES));
			Files.move(sortParts, testData.resolve(SORT_PARTS));
			if (!classpathSupplied) {
				Files.writeString(stamp, currentStamp(inputsDigest, testData), StandardCharsets.UTF_8);
			}
		} finally {
			deleteRecursively(staging);
			deleteRecursively(work);
		}

		reportWritten(testData);
	}

	/**
	 * Reports what a run that writes has published: how many catalogs and sort parts `<testDataDir>` now holds.
	 *
	 * @param testData the output directory
	 * @throws IOException when the output cannot be listed
	 */
	private static void reportWritten(Path testData) throws IOException {
		try (Stream<Path> catalogs = Files.list(testData.resolve(FIXTURES));
		     Stream<Path> parts = Files.list(testData.resolve(SORT_PARTS))) {
			System.out.println(
				"Wrote " + catalogs.filter(Files::isDirectory).count() + " release upgrade fixtures and " +
					parts.filter(it -> it.getFileName().toString().endsWith(".bin")).count() + " sort parts into " +
					testData + "."
			);
		}
	}

	/**
	 * Returns the class path file named by the environment variable, or null when the variable is unset or empty.
	 *
	 * @param variable the environment variable naming a file that holds a release's class path
	 * @return the file name, or null
	 */
	private static String suppliedClasspath(String variable) {
		final String value = System.getenv(variable);
		return value == null || value.isEmpty() ? null : value;
	}

	/**
	 * Returns the hex SHA-256 digest of everything the output is written from: this program, the sort-part generator
	 * and the recipes, in a fixed order.
	 *
	 * @param projectRoot the project root
	 * @return the digest
	 * @throws IOException when an input cannot be read
	 */
	private static String hashInputs(Path projectRoot) throws IOException {
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new GenerationFailure("The JDK provides no SHA-256 digest.");
		}
		final List<Path> inputs = new ArrayList<>();
		inputs.add(projectRoot.resolve(PROGRAM));
		inputs.add(projectRoot.resolve(SORT_PART_GENERATOR));
		inputs.addAll(listRecipes(projectRoot.resolve(RECIPES)));
		for (Path input : inputs) {
			digest.update(Files.readAllBytes(input));
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Returns the stamp of the current output: the input digest, then every output file with its size, so that output
	 * removed or truncated since it was written is written again.
	 *
	 * @param inputsDigest the input digest
	 * @param testData     the output directory
	 * @return the stamp text
	 * @throws IOException when the output cannot be listed
	 */
	private static String currentStamp(String inputsDigest, Path testData) throws IOException {
		final StringBuilder stamp = new StringBuilder(inputsDigest).append('\n');
		for (String folder : List.of(FIXTURES, SORT_PARTS)) {
			final Path root = testData.resolve(folder);
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(root)) {
				final List<Path> sorted = files
					.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(it -> relativeName(testData, it)))
					.toList();
				for (Path file : sorted) {
					stamp.append(Files.size(file)).append(' ').append(relativeName(testData, file)).append('\n');
				}
			}
		}
		return stamp.toString();
	}

	/**
	 * Returns the path of the file relative to the base, with `/` separators on every operating system.
	 *
	 * @param base the base directory
	 * @param file the file inside it
	 * @return the relative name
	 */
	private static String relativeName(Path base, Path file) {
		return base.relativize(file).toString().replace(File.separatorChar, '/');
	}

	/**
	 * Lists the recipe sources, sorted by file name.
	 *
	 * @param recipes the recipe folder
	 * @return the `.java` files in it
	 * @throws IOException when the folder cannot be listed
	 */
	private static List<Path> listRecipes(Path recipes) throws IOException {
		try (Stream<Path> files = Files.list(recipes)) {
			return files
				.filter(it -> it.getFileName().toString().endsWith(".java"))
				.sorted(Comparator.comparing(it -> it.getFileName().toString()))
				.toList();
		}
	}

	/**
	 * Returns the class path of the given release: the supplied one, or the one a nested Maven resolves.
	 *
	 * @param options  the parsed options
	 * @param work     the work directory
	 * @param version  the release version
	 * @param supplied the file holding a supplied class path, or null
	 * @return the class path
	 * @throws IOException          when a file cannot be read or written
	 * @throws InterruptedException when waiting for the nested Maven is interrupted
	 * @throws GenerationFailure    when Maven cannot resolve the release or cannot be passed a path
	 */
	private static String resolveClasspath(Options options, Path work, String version, String supplied)
		throws IOException, InterruptedException {
		if (supplied != null) {
			return Files.readString(Path.of(supplied)).strip();
		}
		final Path pom = Files.createDirectories(work.resolve("pom-" + version)).resolve("pom.xml");
		Files.writeString(
			pom,
			"""
				<project xmlns="http://maven.apache.org/POM/4.0.0">
					<modelVersion>4.0.0</modelVersion>
					<groupId>local.fixture</groupId>
					<artifactId>release-classpath</artifactId>
					<version>1</version>
					<packaging>pom</packaging>
					<dependencies>
						<dependency>
							<groupId>io.evitadb</groupId>
							<artifactId>evita_db</artifactId>
							<version>%1$s</version>
							<type>pom</type>
						</dependency>
						<dependency>
							<groupId>io.evitadb</groupId>
							<artifactId>evita_export_fs</artifactId>
							<version>%1$s</version>
						</dependency>
					</dependencies>
				</project>
				""".formatted(version),
			StandardCharsets.UTF_8
		);
		final Path classpath = work.resolve("classpath-" + version + ".txt");
		final List<String> command = new ArrayList<>(mavenCommand(options));
		// the folder, not the file: mvn.cmd finds a file's folder through `call`, which doubles a `^` in the path
		command.addAll(List.of("-q", "-B", "-f", pom.getParent().toString()));
		if (options.mavenRepoLocal() != null) {
			command.add("-Dmaven.repo.local=" + options.mavenRepoLocal());
		}
		// a calling build names its settings files even when they do not exist, and only existing ones are passed on
		if (options.mavenSettings() != null && Files.isRegularFile(options.mavenSettings())) {
			command.addAll(List.of("-s", options.mavenSettings().toString()));
		}
		if (options.mavenGlobalSettings() != null && Files.isRegularFile(options.mavenGlobalSettings())) {
			command.addAll(List.of("-gs", options.mavenGlobalSettings().toString()));
		}
		command.addAll(List.of(BUILD_CLASSPATH_GOAL, "-Dmdep.outputFile=" + classpath));

		final List<String> offline = new ArrayList<>(command);
		offline.add(1, "-o");
		final boolean resolved;
		if (options.mavenOffline()) {
			resolved = runMaven(offline, Redirect.INHERIT);
		} else {
			// offline first: release artifacts never change once downloaded, so only the first run needs the network
			resolved = runMaven(offline, Redirect.DISCARD) || runMaven(command, Redirect.INHERIT);
		}
		if (!resolved) {
			throw new GenerationFailure(
				"Could not resolve io.evitadb:evita_db:" + version + " and io.evitadb:evita_export_fs:" + version +
					" for the release upgrade fixtures" + (options.mavenOffline() ? " in an offline build" : "") +
					". They come from Maven Central and are needed until the local repository holds them: build " +
					"online once, or let your repository mirror proxy Maven Central. To build without writing the " +
					"fixtures, pass -DreleaseFixtures.skip=true; the release upgrade tests then fail, each naming " +
					"the fixture it misses."
			);
		}
		return Files.readString(classpath).strip();
	}

	/**
	 * Returns the command that starts the calling build's Maven, or the one on the `PATH` when none is named.
	 *
	 * @param options the parsed options
	 * @return the executable, as the first and only element
	 */
	private static List<String> mavenCommand(Options options) {
		final String launcher = WINDOWS ? "mvn.cmd" : "mvn";
		return List.of(
			options.mavenHome() == null ? launcher : options.mavenHome().resolve("bin").resolve(launcher).toString()
		);
	}

	/**
	 * Runs the nested Maven on the JDK that runs this program.
	 *
	 * @param command the command line
	 * @param output  where its output goes
	 * @return true when it succeeded
	 * @throws IOException          when it cannot be started
	 * @throws InterruptedException when waiting for it is interrupted
	 */
	private static boolean runMaven(List<String> command, Redirect output) throws IOException, InterruptedException {
		final ProcessBuilder builder = new ProcessBuilder(WINDOWS ? throughCommandInterpreter(command) : command)
			.redirectInput(Redirect.INHERIT)
			.redirectOutput(output)
			.redirectError(output);
		builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
		return builder.start().waitFor() == 0;
	}

	/**
	 * Wraps a command that starts with `mvn.cmd` in an explicit `cmd.exe` call. A batch file started directly gets its
	 * arguments as the JDK quotes them for a native program, which leaves `&`, `^`, `|` or `<` in an unquoted path to
	 * the command interpreter; here every argument is quoted, and `/s` makes the interpreter strip only the outer
	 * quotes of the whole line. `mvn.cmd` hands the quoted arguments on to the JVM as they are. A `"` or `%` cannot be
	 * passed through the interpreter at all, so an argument holding one is refused with a message.
	 *
	 * @param command the command line, `mvn.cmd` first
	 * @return the command line that runs it through the command interpreter
	 * @throws GenerationFailure when an argument holds a `"` or `%`
	 */
	private static List<String> throughCommandInterpreter(List<String> command) {
		final StringBuilder line = new StringBuilder("\"");
		for (String argument : command) {
			if (argument.indexOf('"') >= 0 || argument.indexOf('%') >= 0) {
				throw new GenerationFailure(
					"Cannot pass `" + argument + "` to Maven through the Windows command interpreter, which has no " +
						"escape for `\"` or `%`: move the project, the local repository or the settings file to a " +
						"path without them."
				);
			}
			line.append('"').append(argument);
			// the JVM reads backslashes before a closing quote as escapes, so a trailing one is doubled
			if (argument.endsWith("\\")) {
				line.append('\\');
			}
			line.append("\" ");
		}
		line.setCharAt(line.length() - 1, '"');
		final String interpreter = System.getenv("ComSpec");
		return List.of(
			interpreter == null || interpreter.isEmpty() ? "cmd.exe" : interpreter,
			"/d", "/v:off", "/s", "/c", line.toString()
		);
	}

	/**
	 * Compiles the sources against the class path into the target directory.
	 *
	 * @param classpath the class path
	 * @param sources   the sources
	 * @param target    the output directory
	 * @return the output directory
	 * @throws IOException       when the output directory cannot be created
	 * @throws GenerationFailure when the JDK has no compiler or the sources do not compile
	 */
	private static Path compile(String classpath, List<Path> sources, Path target) throws IOException {
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new GenerationFailure("Writing the release upgrade fixtures needs a JDK, not a JRE.");
		}
		Files.createDirectories(target);
		final List<String> arguments = new ArrayList<>(
			List.of("-nowarn", "-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", target.toString())
		);
		sources.forEach(it -> arguments.add(it.toString()));
		if (compiler.run(null, null, null, arguments.toArray(String[]::new)) != 0) {
			throw new GenerationFailure(
				"Could not compile " + sources.size() + " sources against " + classpath.split(File.pathSeparator)[0] +
					" and its class path: a recipe uses API the release does not have."
			);
		}
		return target;
	}

	/**
	 * Runs a main class in a new JVM of the JDK that runs this program. The class path goes through an argument file,
	 * because a release's class path can exceed the length Windows allows a command line.
	 *
	 * @param work      the work directory, where the argument file is written
	 * @param jvmFlags  the JVM flags
	 * @param classpath the class path
	 * @param mainClass the main class
	 * @param arguments the program arguments
	 * @throws IOException          when it cannot be started
	 * @throws InterruptedException when waiting for it is interrupted
	 * @throws GenerationFailure    when the class path cannot be written for the launcher or the JVM exits with
	 *                              anything but 0
	 */
	private static void runJava(
		Path work, List<String> jvmFlags, String classpath, String mainClass, List<String> arguments
	) throws IOException, InterruptedException {
		final Path argumentFile = Files.createTempFile(work, "classpath-", ".args");
		// inside quotes an argument file reads a backslash as an escape, which Windows paths are full of
		Files.write(
			argumentFile,
			encodeForLauncher(
				"-classpath \"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
			)
		);
		final List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString());
		command.addAll(jvmFlags);
		command.add("@" + argumentFile);
		command.add(mainClass);
		command.addAll(arguments);
		final int exitCode = new ProcessBuilder(command).inheritIO().start().waitFor();
		if (exitCode != 0) {
			throw new GenerationFailure(mainClass + " failed with exit code " + exitCode + ".");
		}
	}

	/**
	 * Encodes an argument file the way the `java` launcher reads it: in the encoding the JVM decodes the launcher's
	 * strings in (`sun.jnu.encoding`), which is the ANSI code page on Windows and UTF-8 on macOS whatever the locale.
	 * A path that encoding cannot represent is refused with a message instead of reaching the launcher garbled.
	 *
	 * @param content the argument file text
	 * @return its bytes
	 * @throws GenerationFailure when the launcher's encoding cannot represent the text
	 */
	private static byte[] encodeForLauncher(String content) {
		final String encodingName = System.getProperty("sun.jnu.encoding");
		// Maven may run on JDK 17, which lacks the overload of Charset.forName that takes a fallback
		final Charset launcherEncoding = encodingName != null && Charset.isSupported(encodingName)
			? Charset.forName(encodingName)
			: StandardCharsets.UTF_8;
		try {
			final ByteBuffer encoded = launcherEncoding.newEncoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.encode(CharBuffer.wrap(content));
			final byte[] bytes = new byte[encoded.remaining()];
			encoded.get(bytes);
			return bytes;
		} catch (CharacterCodingException ex) {
			throw new GenerationFailure(
				"A class path of the release upgrade fixtures holds characters the platform encoding " +
					launcherEncoding + " cannot represent, so the java launcher could not read it: move the project " +
					"or the local repository to a path without them."
			);
		}
	}

	/**
	 * Deletes the file or directory tree, if it exists.
	 *
	 * @param root the file or directory
	 * @throws IOException when it cannot be listed or one of its files cannot be deleted
	 */
	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}

	/**
	 * The parsed command line.
	 *
	 * @param projectRoot         the project root, where the recipes and generators live
	 * @param workDir             the scratch directory for class files, throw-away poms, class path files and the
	 *                            engines' work folders; every run that writes wipes it before and after, so it is
	 *                            refused when it is or contains the project root, or overlaps `testDataDir`
	 * @param mavenHome           the calling build's Maven installation, or null for the `mvn` on the `PATH`
	 * @param mavenRepoLocal      the calling build's local repository, or null for the nested Maven's default
	 * @param mavenOffline        whether the calling build is offline
	 * @param mavenSettings       the calling build's user settings file, or null
	 * @param mavenGlobalSettings the calling build's global settings file, or null
	 * @param testDataDir         the output directory
	 */
	private record Options(
		Path projectRoot,
		Path workDir,
		Path mavenHome,
		String mavenRepoLocal,
		boolean mavenOffline,
		Path mavenSettings,
		Path mavenGlobalSettings,
		Path testDataDir
	) {
		/** The usage line printed on a malformed command line. */
		private static final String USAGE =
			"usage: java tools/fixtures/GenerateReleaseFixtures.java --project-root=<dir> --work-dir=<dir> " +
				"[--maven-home=<dir>] [--maven-repo-local=<dir>] [--maven-offline=true|false] " +
				"[--maven-settings=<file>] [--maven-global-settings=<file>] <testDataDir>";

		/**
		 * Parses the command line.
		 *
		 * @param args the command line
		 * @return the options
		 * @throws IllegalArgumentException when an option is unknown, a required one is missing or empty, or the
		 *                                  options are not followed by exactly one `<testDataDir>`
		 */
		static Options parse(String[] args) {
			Path projectRoot = null;
			Path workDir = null;
			Path mavenHome = null;
			String mavenRepoLocal = null;
			boolean mavenOffline = false;
			Path mavenSettings = null;
			Path mavenGlobalSettings = null;
			int index = 0;
			while (index < args.length && args[index].startsWith("--")) {
				final String option = args[index];
				final int separator = option.indexOf('=');
				if (separator < 0) {
					throw new IllegalArgumentException("unknown option: " + option + "\n" + USAGE);
				}
				final String value = option.substring(separator + 1);
				switch (option.substring(0, separator)) {
					case "--project-root" ->
						projectRoot = value.isEmpty() ? null : Path.of(value).toAbsolutePath().normalize();
					case "--work-dir" -> workDir = optionalPath(value);
					case "--maven-home" -> mavenHome = optionalPath(value);
					case "--maven-repo-local" -> mavenRepoLocal = value.isEmpty() ? null : value;
					case "--maven-offline" -> mavenOffline = Boolean.parseBoolean(value);
					case "--maven-settings" -> mavenSettings = optionalPath(value);
					case "--maven-global-settings" -> mavenGlobalSettings = optionalPath(value);
					default -> throw new IllegalArgumentException("unknown option: " + option + "\n" + USAGE);
				}
				index++;
			}
			if (projectRoot == null || workDir == null || args.length - index != 1) {
				throw new IllegalArgumentException(USAGE + "\ngot: " + Arrays.toString(args));
			}
			return new Options(
				projectRoot, workDir, mavenHome, mavenRepoLocal, mavenOffline, mavenSettings, mavenGlobalSettings,
				Path.of(args[index])
			);
		}

		/**
		 * Returns the path an option names, or null when its value is empty.
		 *
		 * @param value the option value
		 * @return the path, or null
		 */
		private static Path optionalPath(String value) {
			return value.isEmpty() ? null : Path.of(value);
		}
	}

	/**
	 * A step of the generation failed; its message says which and what to do.
	 */
	private static final class GenerationFailure extends RuntimeException {
		/** Serialization version of the exception. */
		private static final long serialVersionUID = 1L;

		/**
		 * Creates the failure.
		 *
		 * @param message what failed and what to do
		 */
		GenerationFailure(String message) {
			super(message);
		}
	}
}
