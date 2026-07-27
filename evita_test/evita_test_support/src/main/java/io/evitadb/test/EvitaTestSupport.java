/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.test;

import com.linecorp.armeria.common.TlsKeyPair;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.params.provider.Arguments;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.evitadb.utils.CertificateUtils.CERTIFICATE_EXTENSION;
import static io.evitadb.utils.CertificateUtils.CERTIFICATE_KEY_EXTENSION;

/**
 * This interface allows unit tests to easily prepare test directory, test file and also clean it up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EvitaTestSupport extends TestConstants {
	/**
	 * Default data folder for evita data in tests.
	 */
	Path BASE_PATH = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "evita" + File.separator);
	/**
	 * Set of all test directories allocated by *this JVM* via [#createTestPaths(String)] (or the equivalent inline
	 * triplet in `EvitaParameterResolver.createEvita`). Used by `CleaningTestExecutionListener` to wipe leftovers
	 * scoped to this JVM (i.e. PID) when the test plan finishes — a graceful exit path, complementary to per-test
	 * [#cleanupTestPaths(TestPaths)] in `@AfterEach`.
	 *
	 * The set is JVM-local (a static field), so concurrent test JVMs cannot trample each other: each one only ever
	 * deletes paths it allocated itself. Entries are removed from the set by [#cleanupTestPaths(TestPaths)] in the
	 * happy path, so the set normally drains to empty over the lifetime of a run.
	 */
	Set<Path> ALLOCATED_TEST_PATHS = ConcurrentHashMap.newKeySet();
	/**
	 * Default name of the root certificate authority's certificate file.
	 */
	String OTHER_CERT_NAME = "other";
	/**
	 * Shared instance of port manager.
	 */
	PortManager PORT_MANAGER = new PortManager();

	/**
	 * Returns the name and the extension of the additional generated certificate file.
	 */
	default String getGeneratedOtherCertificateFileName() {
		return OTHER_CERT_NAME + CERTIFICATE_EXTENSION;
	}

	/**
	 * Returns the name and the extension of the additional generated private key file.
	 */
	default String getGeneratedOtherCertificateKeyFileName() {
		return OTHER_CERT_NAME + CERTIFICATE_KEY_EXTENSION;
	}

	/**
	 * Method copies `evita-configuration.yaml` from the classpath to the temporary directory on the filesystem so that
	 * evita server that is going to be started in tests will be able to find it.
	 *
	 * @param folderName        name of the folder where the configuration file will be stored
	 * @param classPathLocation classpath location of the source configuration file
	 * @param targetFileName    name of the target configuration file
	 * @return path of the exported configuration file
	 */
	@Nonnull
	static Path bootstrapEvitaServerConfigurationFileFrom(@Nonnull String folderName, @Nonnull String classPathLocation, @Nonnull String targetFileName) {
		final Path dir = Path.of(System.getProperty("java.io.tmpdir"))
			.resolve("evita")
			.resolve(folderName);
		if (!dir.toFile().exists()) {
			Assert.isTrue(dir.toFile().mkdirs(), "Cannot set up folder: " + dir);
		}
		final Path configFilePath = dir.resolve(targetFileName);
		try (final InputStream sourceIs = TestConstants.class.getResourceAsStream(classPathLocation)) {
			Files.copy(
				Objects.requireNonNull(sourceIs),
				configFilePath,
				StandardCopyOption.REPLACE_EXISTING
			);
		} catch (IOException e) {
			throw new RuntimeException(
				"Failed to copy evita `" + targetFileName + "` to `" + configFilePath + "` due to: " + e.getMessage(),
				e
			);
		}

		return dir;
	}

	/**
	 * Returns a stream of 50 random seeds.
	 */
	@Nonnull
	static Stream<Arguments> returnRandomSeed() {
		final Random random = new Random();
		return LongStream.generate(random::nextLong).limit(50).mapToObj(Arguments::of);
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestDirectory() throws IOException {
		// clear evitaDB directory
		FileUtils.deleteDirectory(BASE_PATH.toFile());
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestSubDirectory(@Nonnull String directory) throws IOException {
		// clear evitaDB directory
		FileUtils.deleteDirectory(BASE_PATH.resolve(directory).toFile());
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestDirectoryWithRethrow() {
		try {
			cleanTestDirectory();
		} catch (IOException e) {
			throw new GenericEvitaInternalError("Cannot empty target directory!", e);
		}
	}

	/**
	 * Removes test directory with its contents.
	 */
	default void cleanTestSubDirectoryWithRethrow(String directory) {
		IOException ex = null;
		// wait a while until files become unlocked (on Windows it sometimes takes a while)
		for (int i = 0; i < 10; i++) {
			try {
				cleanTestSubDirectory(directory);
				return;
			} catch (IOException e) {
				ex = e;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		throw new GenericEvitaInternalError("Cannot empty target directory!", ex);
	}

	/**
	 * Returns pointer to the root project directory. This method supports proper folder resolution from different
	 * working directories in evitaDB git repository.
	 */
	@Nonnull
	default Path getRootDirectory() {
		final Path workingDirPath = Path.of(System.getProperty("user.dir"));
		if (workingDirPath.toString().contains(File.separator + "evita_")) {
			return workingDirPath.resolve("../..");
		} else {
			return workingDirPath.resolve("");
		}
	}

	/**
	 * Returns pointer to the data directory. This method supports proper DATA folder resolution from different working
	 * directories in evitaDB git repository.
	 */
	@Nonnull
	default Path getDataDirectory() {
		final String externallyDefinedPath = System.getProperty(DATA_FOLDER_ENV_VARIABLE);
		final Path dataPath;
		if (externallyDefinedPath == null) {
			dataPath = getRootDirectory().resolve("data");
		} else {
			dataPath = Path.of(externallyDefinedPath);
		}
		if (!dataPath.toFile().exists()) {
			throw new GenericEvitaInternalError("Data directory `" + dataPath + "` does not exist!");
		}
		return dataPath;
	}

	/**
	 * Returns path to the test directory.
	 */
	default Path getTestDirectory() {
		return BASE_PATH;
	}

	/**
	 * Allocates a fresh, collision-free triplet of storage / work / export directories under {@link #BASE_PATH}.
	 *
	 * Every directory is prefixed with `label` (for debuggability — greppable in `/tmp/evita/`) and suffixed with an
	 * 8-character slice of a random {@link UUID} to guarantee uniqueness across concurrently executing tests on
	 * the same machine. The caller owns the returned {@link TestPaths} value and is expected to pass it to
	 * {@link #newTestEvitaConfigurationBuilder(TestPaths)} and, when done, to {@link #cleanupTestPaths(TestPaths)}.
	 *
	 * Holding the same {@link TestPaths} across multiple `new Evita(cfg)` invocations is the intended way to simulate
	 * engine restart — the paths stay stable for the duration the caller wants them to.
	 *
	 * @param label human-readable prefix, typically the test class or method name; used only for debuggability
	 * @return freshly allocated, unique, not-yet-created directory paths
	 */
	@Nonnull
	default TestPaths createTestPaths(@Nonnull String label) {
		final String unique = label + "_" + UUID.randomUUID().toString().substring(0, 8);
		final TestPaths paths = new TestPaths(
			BASE_PATH.resolve(unique),
			BASE_PATH.resolve(unique + "_work"),
			BASE_PATH.resolve(unique + "_export")
		);
		// register so `CleaningTestExecutionListener` can sweep this JVM's leftovers on plan finish even when a
		// test bypasses `@AfterEach`-driven `cleanupTestPaths(...)` (e.g. crashes mid-method)
		ALLOCATED_TEST_PATHS.add(paths.storage());
		ALLOCATED_TEST_PATHS.add(paths.work());
		ALLOCATED_TEST_PATHS.add(paths.export());
		return paths;
	}

	/**
	 * Variant of {@link #createTestPaths(String)} that anchors the directory triplet under a caller-supplied base
	 * path (e.g. a JUnit `@TempDir`) instead of the shared {@link #BASE_PATH}. Use this when the lifecycle of the
	 * base path is already managed by JUnit and you do not want to pollute `/tmp/evita/`.
	 *
	 * @param base  root under which the three directories will be created; must not be null
	 * @param label human-readable prefix, typically the test class or method name
	 * @return freshly allocated, unique, not-yet-created directory paths under `base`
	 */
	@Nonnull
	default TestPaths createTestPaths(@Nonnull Path base, @Nonnull String label) {
		final String unique = label + "_" + UUID.randomUUID().toString().substring(0, 8);
		return new TestPaths(
			base.resolve(unique),
			base.resolve(unique + "_work"),
			base.resolve(unique + "_export")
		);
	}

	/**
	 * Returns an {@link EvitaConfiguration.Builder} pre-wired to the supplied {@link TestPaths} — storage directory,
	 * work directory, and filesystem-backed export directory are all configured to non-colliding per-test locations.
	 *
	 * The caller is responsible for applying any further test-specific overrides (cache, server options, transaction
	 * options, …) and for invoking `.build()`. The returned builder intentionally carries **only** the path wiring so
	 * that migrating existing call sites does not change any other aspect of their configuration.
	 *
	 * @param paths non-null path triplet, typically produced by {@link #createTestPaths(String)}
	 * @return builder with `.storage(...)` and `.export(...)` already applied; never null
	 */
	@Nonnull
	default EvitaConfiguration.Builder newTestEvitaConfigurationBuilder(@Nonnull TestPaths paths) {
		return EvitaConfiguration.builder()
			.storage(
				StorageOptions.builder()
					.storageDirectory(paths.storage())
					.workDirectory(paths.work())
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(paths.export())
					.build()
			);
	}

	/**
	 * Removes all three directories described by the supplied {@link TestPaths}. Inherits the Windows-friendly
	 * retry-on-IOException semantics already used by {@link #cleanTestSubDirectoryWithRethrow(String)} — the export
	 * directory is cleaned first because it owns the `FolderLock` file whose release is the usual stall point.
	 *
	 * Missing directories are treated as already-cleaned and never raise.
	 *
	 * @param paths triplet to remove
	 */
	default void cleanupTestPaths(@Nonnull TestPaths paths) {
		deleteDirectoryWithRetry(paths.export());
		deleteDirectoryWithRetry(paths.work());
		deleteDirectoryWithRetry(paths.storage());
		// the happy path: drain entries from the JVM-local registry so the set stays bounded over a long run
		ALLOCATED_TEST_PATHS.remove(paths.storage());
		ALLOCATED_TEST_PATHS.remove(paths.work());
		ALLOCATED_TEST_PATHS.remove(paths.export());
	}

	/**
	 * Deletes `dir` if it exists, retrying up to 10 times with 100ms backoff on {@link IOException}. Mirrors the
	 * behavior of {@link #cleanTestSubDirectoryWithRethrow(String)} so that test cleanup behaves uniformly on
	 * Windows where file handles are held briefly after the owning process releases them.
	 */
	private static void deleteDirectoryWithRetry(@Nonnull Path dir) {
		final File file = dir.toFile();
		if (!file.exists()) {
			return;
		}
		IOException lastFailure = null;
		for (int attempt = 0; attempt < 10; attempt++) {
			try {
				FileUtils.deleteDirectory(file);
				return;
			} catch (IOException ex) {
				lastFailure = ex;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new GenericEvitaInternalError("Cannot delete test directory: " + dir, lastFailure);
	}

	/**
	 * Returns path to the file with specified name in the test directory.
	 */
	default Path getPathInTargetDirectory(@Nonnull String fileName) {
		return BASE_PATH.resolve(fileName);
	}

	/**
	 * Returns file reference to the file with specified name in the test directory.
	 */
	default File createFileInTargetDirectory(@Nonnull String fileName) {
		return getPathInTargetDirectory(fileName).toFile();
	}

	/**
	 * Returns singleton instance of port manager that keeps track of allocated ports during test runs.
	 */
	@Nonnull
	default PortManager getPortManager() {
		return PORT_MANAGER;
	}

	/**
	 * Generates a self-signed test certificate and writes it to the specified folder.
	 *
	 * @param certificateFolderPath the path of the folder where the generated certificate
	 * and its private key should be stored
	 * @throws GenericEvitaInternalError if there is any error during the generation or
	 * writing process
	 */
	default void generateTestCertificate(@Nonnull String certificateFolderPath) {
		try {
			final String certificateName = OTHER_CERT_NAME;
			final TlsKeyPair tlsKeyPair = TlsKeyPair.ofSelfSigned();
			final Path certificatePath = Path.of(certificateFolderPath);
			try (final JcaPEMWriter pemWriterIssued = new JcaPEMWriter(new FileWriter(certificatePath.resolve(certificateName + CertificateUtils.getCertificateExtension()).toFile()))) {
				pemWriterIssued.writeObject(tlsKeyPair.certificateChain().get(0));
			}

			try (final PemWriter privateKeyWriter = new PemWriter(new FileWriter(certificatePath.resolve(certificateName + CertificateUtils.getCertificateKeyExtension()).toFile()))) {
				privateKeyWriter.writeObject(new PemObject("PRIVATE KEY", tlsKeyPair.privateKey().getEncoded()));
			}
		} catch (Exception e) {
			throw new GenericEvitaInternalError("Failed to generate test certificate.", e);
		}
	}

	/**
	 * Immutable triplet of directories used by a single Evita test instance — storage root, work directory, and
	 * filesystem export directory. All three are expected to be siblings (or at least non-overlapping) so that
	 * cleanup can delete them independently.
	 *
	 * @param storage path to the Evita storage root (`StorageOptions.storageDirectory`)
	 * @param work    path to the Evita work directory (`StorageOptions.workDirectory`)
	 * @param export  path to the filesystem export directory (`FileSystemExportOptions.directory`)
	 */
	record TestPaths(@Nonnull Path storage, @Nonnull Path work, @Nonnull Path export) {
	}
}
