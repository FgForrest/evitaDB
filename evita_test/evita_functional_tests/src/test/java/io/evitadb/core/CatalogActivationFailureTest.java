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

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers an activation whose catalog loads fine but whose transition the engine fails to record.
 *
 * Activating a catalog loads it, and the load's success callback publishes the loaded instance - replayed to the
 * head of its write-ahead log - into the engine state on its own. Only afterwards does the activation record the
 * transition in the engine write-ahead log and bootstrap record. When that record fails, the append is rolled back
 * and the persisted engine state still lists the catalog as inactive, and the activation terminates the instance
 * it loaded. What must not survive the failure is the instance the load already published: left in the engine
 * state it reads as an `ALIVE` catalog whose storage is closed, contradicting what is persisted until the server
 * restarts.
 *
 * **How the failure is injected.** No production seam is used. Revoking write permission on the storage root
 * leaves the catalog folder below it writable and readable, so the load and its replay succeed, while the engine
 * cannot create the temporary file its bootstrap record is rewritten through - the step that makes the
 * activation durable. POSIX permissions are why the test is confined to Linux and macOS, and why it skips itself
 * when the revocation does not bite, which is what happens when the suite runs as root.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("An activation the engine fails to record")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(MANAGEMENT)
@EnabledOnOs({OS.LINUX, OS.MAC})
class CatalogActivationFailureTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogActivationFailureTest.class.getSimpleName());
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
	@DisplayName("Leaves the catalog inactive, as persisted, and activatable again")
	void shouldLeaveTheCatalogInactiveWhenTheActivationCannotBeRecorded() throws IOException {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.goLiveAndClose();
			}
		);
		this.evita.deactivateCatalog(TEST_CATALOG);
		assertEquals(CatalogState.INACTIVE, catalogState());

		final Path storageRoot = this.paths.storage();
		final Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(storageRoot);
		try {
			Files.setPosixFilePermissions(storageRoot, PosixFilePermissions.fromString("r-xr-xr-x"));
			assumeTrue(
				!Files.isWritable(storageRoot),
				"Revoking write permission did not bite - the suite is running as a user that ignores it."
			);

			final RuntimeException failure = assertThrows(
				RuntimeException.class,
				() -> this.evita.activateCatalog(TEST_CATALOG),
				"An activation the engine could not record must be reported as failed!"
			);
			// precondition - the failure must be the record of the activation, the rewrite of the engine bootstrap
			// record; a load that failed instead would leave the catalog behind a placeholder of its own and the
			// assertions below would test something else
			assertTrue(
				namesEngineBootstrapRecord(failure),
				() -> "The activation must have failed rewriting the engine bootstrap record, but failed with: " +
					failure
			);
		} finally {
			Files.setPosixFilePermissions(storageRoot, originalPermissions);
		}

		// the instance the load published before the record failed must be withdrawn - the persisted engine state
		// lists the catalog as inactive, and the in-memory one must not claim more
		assertEquals(
			CatalogState.INACTIVE, catalogState(),
			"A catalog whose activation was not recorded must not be listed as active over its closed storage!"
		);

		// and because nothing on disk was touched, the catalog activates as if the failed attempt never happened
		this.evita.activateCatalog(TEST_CATALOG);
		assertEquals(CatalogState.ALIVE, catalogState());
		final int brandCount = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntityCollectionSize(Entities.BRAND);
			}
		);
		assertEquals(
			1, brandCount,
			"The catalog activated after the failed attempt must serve the data it held before it!"
		);
	}

	/**
	 * Tells whether the cause chain of the passed failure names the engine bootstrap record, which the engine
	 * rewrites through a temporary file next to it when it records an engine mutation.
	 *
	 * @param failure the failure the activation was reported with
	 * @return true when a message in the cause chain names the engine bootstrap record
	 */
	private static boolean namesEngineBootstrapRecord(@Nonnull Throwable failure) {
		final String bootstrapFileName = EnginePersistenceService.getBootstrapFileName();
		int depth = 0;
		for (Throwable cause = failure; cause != null && depth < 32; cause = cause.getCause(), depth++) {
			final String message = cause.getMessage();
			if (message != null && message.contains(bootstrapFileName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the state the engine state lists the test catalog in.
	 *
	 * @return the catalog state, never null
	 */
	@Nonnull
	private CatalogState catalogState() {
		return this.evita.getCatalogState(TEST_CATALOG).orElseThrow();
	}

	/**
	 * Stock storage options, with the work directory kept outside the storage root whose permissions are revoked.
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
