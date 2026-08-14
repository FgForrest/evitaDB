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

package io.evitadb.core;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers what a **failed** rename leaves behind, which issue #1414 reported as its second symptom and which no
 * test reaches: every other rename test asserts about the success path.
 *
 * The issue reported three consequences of a rename that threw after the point of no return - sessions on the
 * former name suspended for ever (surfacing as `SessionBusyException`), a catalog reachable under neither name,
 * and an engine that could not release its folder lock, so the next boot in the same JVM failed with
 * `FolderAlreadyUsedException` *after* `close()` had returned. The folder decoupling is expected to have removed
 * all three - the lock now sits once on the storage root rather than per catalog, and a rename commits nothing
 * until the storage work has succeeded - but "expected to" is what this test exists to replace.
 *
 * **The first two tests found live defects, and both are calibrated against the fixes that closed them.** Drop
 * `prevailingCatalogSessionRegistry.ifPresent(SessionRegistry::resumeOperations)` from `undoOperations` in
 * `ModifyCatalogSchemaNameMutationOperator#doReplaceCatalogInternal` and the rename test fails reading the
 * catalog back, with `SessionBusyException`. Drop the `quiescedTargetRegistry` resume beside it and the replace
 * test fails the same way with `InstanceTerminatedException`, thrown out of
 * `SessionRegistry#awaitResumeOrRefuse` - the catalog that was *not* replaced refusing every session it is
 * offered. Both suspensions used to be lifted on the success path only.
 *
 * The third covers the *other* undo branch, which the second deliberately avoids: a target nobody has opened a
 * session on since boot has no registry, so the operation installs one purely to quiesce it, and the undo has to
 * resume that registry **in place** rather than restore or unpublish it. Calibrated by dropping the
 * `resumeOperations` call from that branch - the target then answers `InstanceTerminatedException` for the rest
 * of the process.
 *
 * **How the failure is injected, and why here.** No production seam is used and no timing is raced. `replaceWith`
 * writes the header and the bootstrap record through handles opened at boot, which file permissions cannot fail;
 * it then closes the former persistence service and constructs a new one, and *that* constructor opens the
 * folder's data file afresh. Revoking read permission on that file before the rename therefore fails the
 * operation at exactly the point the issue's second shape failed at - after the old service is gone - and does so
 * deterministically. POSIX permissions are also why the test is confined to Linux and macOS, and why it skips
 * itself when the revocation does not bite, which is what happens when the suite runs as root.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("A rename that fails after the persistence service has been handed over")
@Tag(STORAGE)
@Tag(MANAGEMENT)
@Tag(SESSION)
@EnabledOnOs({OS.LINUX, OS.MAC})
class CatalogRenameFailurePathTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	private static final String RENAMED_CATALOG = "renamedCatalog";
	private static final String REPLACED_CATALOG = "replacedCatalog";
	private static final String COMMITTED_VALUE = "committed before the failed rename";
	private static final String TARGET_VALUE = "committed into the catalog being replaced away";
	private static final String CATALOG_FILE_SUFFIX = ".catalog";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogRenameFailurePathTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Leaves the catalog usable under its former name and the engine able to restart")
	void shouldLeaveCatalogUsableWhenRenameFailsAfterTheServiceHandover() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);

		final Path dataFile = soleCatalogDataFile(catalogFolder(TEST_CATALOG));
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dataFile);

		try {
			Files.setPosixFilePermissions(dataFile, Collections.emptySet());
			assumeTrue(
				isUnreadable(dataFile),
				"Revoking read permission did not bite - the suite is running as a user that ignores it."
			);

			// the rename has to fail, and it has to fail *inside* the handover rather than in validation: the
			// header and the bootstrap record are already written at this point, and the former service closed
			assertThrows(
				RuntimeException.class,
				() -> this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG),
				"The rename must report the failure rather than swallow it!"
			);
		} finally {
			// restored before anything else touches the catalog - a wedged fixture would otherwise be
			// indistinguishable from the defect under test, and would take the cleanup down with it
			Files.setPosixFilePermissions(dataFile, originalPermissions);
		}

		// the catalog must answer under the name it still has. The issue reported the opposite: sessions on the
		// former name stayed suspended because the operator resumed them only on the success path, so the name
		// answered `SessionBusyException` where it owed either data or `CatalogNotFoundException`.
		assertTrue(
			this.evita.getCatalogNames().contains(TEST_CATALOG),
			"A failed rename must leave the catalog under the name it started with!"
		);
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"A failed rename must leave the data readable through a fresh session!"
		);

		// the reported symptom: `close()` returned, yet the lock was still held and the next boot in the same
		// JVM refused to start
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();

		assertTrue(
			this.evita.getCatalogNames().contains(TEST_CATALOG),
			"The catalog must come back from the restart that follows a failed rename!"
		);
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"The transaction committed before the failed rename must survive the restart!"
		);
	}

	@Test
	@DisplayName("Leaves both catalogs of a failed replace usable")
	void shouldLeaveBothCatalogsUsableWhenReplaceFailsAfterTheServiceHandover() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);
		defineCatalogAndGoLive(REPLACED_CATALOG);
		commitProduct(REPLACED_CATALOG, 2, TARGET_VALUE);

		// the target's registry must already exist when the replace starts, or the operation installs one purely
		// to quiesce it and the undo path takes a different branch than the one under test here
		assertEquals(TARGET_VALUE, readPayload(REPLACED_CATALOG, 2));

		// the *source* is the catalog that survives a replace, so failing its handover fails the operation at
		// the same point the rename case does
		final Path dataFile = soleCatalogDataFile(catalogFolder(TEST_CATALOG));
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dataFile);

		try {
			Files.setPosixFilePermissions(dataFile, Collections.emptySet());
			assumeTrue(
				isUnreadable(dataFile),
				"Revoking read permission did not bite - the suite is running as a user that ignores it."
			);

			assertThrows(
				RuntimeException.class,
				() -> this.evita.replaceCatalog(TEST_CATALOG, REPLACED_CATALOG),
				"The replace must report the failure rather than swallow it!"
			);
		} finally {
			Files.setPosixFilePermissions(dataFile, originalPermissions);
		}

		// a failed replace changes nothing, so both catalogs have to answer exactly as they did before it - the
		// source under its own name, and the target, whose sessions the operation quiesced with REJECT
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"A failed replace must leave the source catalog readable!"
		);
		assertEquals(
			TARGET_VALUE, readPayload(REPLACED_CATALOG, 2),
			"A failed replace must leave the target catalog readable rather than rejecting sessions!"
		);

		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();

		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"The source catalog must survive the restart that follows a failed replace!"
		);
		assertEquals(
			TARGET_VALUE, readPayload(REPLACED_CATALOG, 2),
			"The target catalog must survive the restart that follows a failed replace!"
		);
	}

	@Test
	@DisplayName("Leaves the target serving when it installed a registry purely to quiesce it")
	void shouldLeaveTheTargetServingWhenTheFailedReplaceInstalledItsRegistry() throws Exception {
		defineCatalogAndGoLive(TEST_CATALOG);
		commitProduct(TEST_CATALOG, 1, COMMITTED_VALUE);
		defineCatalogAndGoLive(REPLACED_CATALOG);
		commitProduct(REPLACED_CATALOG, 2, TARGET_VALUE);

		// session registries live in memory only, so a restart is what produces a catalog that has none - and
		// that is the branch where the operation has to install one before it can quiesce the target at all
		restartEngine();
		assertTrue(
			this.evita.getCatalogSessionRegistry(REPLACED_CATALOG).isEmpty(),
			"The target must start with no session registry, or this test exercises the other undo branch!"
		);

		final Path dataFile = soleCatalogDataFile(catalogFolder(TEST_CATALOG));
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(dataFile);

		try {
			Files.setPosixFilePermissions(dataFile, Collections.emptySet());
			assumeTrue(
				isUnreadable(dataFile),
				"Revoking read permission did not bite - the suite is running as a user that ignores it."
			);

			assertThrows(
				RuntimeException.class,
				() -> this.evita.replaceCatalog(TEST_CATALOG, REPLACED_CATALOG),
				"The replace must report the failure rather than swallow it!"
			);
		} finally {
			Files.setPosixFilePermissions(dataFile, originalPermissions);
		}

		// Asserted before anything opens a session, so the registry can only be the one the operation installed
		// to quiesce the target: it stays published and is resumed in place, which is exactly the state the
		// first session would have left it in. The alternative - unpublishing it - orphans whatever sessions
		// outlived a drain that gave up, since every later quiesce walks the registry map.
		assertTrue(
			this.evita.getCatalogSessionRegistry(REPLACED_CATALOG).isPresent(),
			"The failed replace must keep the registry it installed, rather than unpublishing it!"
		);
		assertEquals(
			TARGET_VALUE, readPayload(REPLACED_CATALOG, 2),
			"A failed replace must leave an untouched target readable rather than rejecting sessions!"
		);
		assertEquals(
			COMMITTED_VALUE, readPayload(TEST_CATALOG, 1),
			"A failed replace must leave the source catalog readable!"
		);
	}

	/**
	 * Closes the engine and boots a new one over the same storage folder.
	 */
	private void restartEngine() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Returns the single catalog data file of the passed folder - the file the reopen constructor opens afresh,
	 * and therefore the one whose permissions decide whether the handover succeeds.
	 *
	 * @param catalogDirectory folder to inspect
	 * @return path of the data file, never null
	 */
	@Nonnull
	private static Path soleCatalogDataFile(@Nonnull Path catalogDirectory) {
		final File[] dataFiles = catalogDirectory.toFile().listFiles(
			(dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX)
		);
		assertNotNull(dataFiles, "The catalog folder must be listable!");
		assertEquals(
			1, dataFiles.length,
			() -> "Exactly one catalog data file is expected in a freshly written catalog!"
		);
		return dataFiles[0].toPath();
	}

	/**
	 * Tells whether the passed file genuinely cannot be opened for reading, which permission bits alone do not
	 * settle - a superuser ignores them entirely.
	 *
	 * @param file file to probe
	 * @return true when opening the file for reading fails
	 */
	private static boolean isUnreadable(@Nonnull Path file) {
		try (final InputStream stream = Files.newInputStream(file)) {
			assertNotNull(stream);
			return false;
		} catch (IOException ex) {
			return true;
		}
	}

	/**
	 * Resolves the folder the passed catalog is bound to - an opaque token, not the catalog's name.
	 *
	 * @param catalogName the catalog whose folder to resolve
	 * @return the folder path, never null
	 */
	@Nonnull
	private Path catalogFolder(@Nonnull String catalogName) {
		return this.paths.storage()
			.resolve(this.evita.getCatalogFolderContext().folderIdFor(catalogName).id());
	}

	/**
	 * Commits a single product in its own transaction and waits until the change is visible.
	 *
	 * @param catalogName    catalog to write into
	 * @param primaryKey     primary key of the product
	 * @param attributeValue value stored in the payload attribute
	 */
	private void commitProduct(@Nonnull String catalogName, int primaryKey, @Nonnull String attributeValue) {
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(catalogName, SessionFlags.READ_WRITE));
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, primaryKey)
			       .setAttribute(ATTRIBUTE_PAYLOAD, attributeValue)
		);
		assertNotNull(session.closeNowWithProgress().onChangesVisible().toCompletableFuture().join());
	}

	/**
	 * Reads a single product's payload attribute back through a fresh session.
	 *
	 * @param catalogName catalog to read from
	 * @param primaryKey  primary key of the product
	 * @return the stored payload
	 */
	@Nonnull
	private String readPayload(@Nonnull String catalogName, int primaryKey) {
		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return session.getEntity(Entities.PRODUCT, primaryKey, attributeContentAll())
					.orElseThrow(() -> new AssertionError("The product must be present!"))
					.getAttribute(ATTRIBUTE_PAYLOAD, String.class);
			}
		);
	}

	/**
	 * Defines the passed catalog with a single product schema and takes it live.
	 *
	 * @param catalogName name of the catalog to define
	 */
	private void defineCatalogAndGoLive(@Nonnull String catalogName) {
		this.evita.defineCatalog(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withoutGeneratedPrimaryKey()
				       .withAttribute(ATTRIBUTE_PAYLOAD, String.class)
				       .updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Stock storage options - nothing here depends on the log or on time travel.
	 *
	 * @return the configuration, never null
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.build()
			)
			.build();
	}

}
