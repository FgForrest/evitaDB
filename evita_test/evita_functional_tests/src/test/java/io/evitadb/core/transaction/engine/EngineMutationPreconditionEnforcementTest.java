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

package io.evitadb.core.transaction.engine;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.UnexpectedCatalogIncarnationException;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the engine actually *enforces* an {@link EngineMutationPrecondition}, rather than merely accepting
 * one.
 *
 * The distinction is the whole point of this class. Tests elsewhere assert that a restore hands the right
 * expectations to the swap, and a unit test asserts that a precondition compares folders correctly - but neither
 * would notice if `EngineTransactionManager#applyMutation` stopped consulting them, which is precisely the failure
 * that would turn the guard into decoration. These tests go through the real engine so that deleting the check
 * breaks them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine mutation precondition enforcement")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class EngineMutationPreconditionEnforcementTest implements EvitaTestSupport {
	private TestPaths testPaths;
	private Path storageDirectory;

	@BeforeEach
	void setUp() throws IOException {
		this.testPaths = createTestPaths(EngineMutationPreconditionEnforcementTest.class.getSimpleName());
		this.storageDirectory = this.testPaths.storage();
		Files.createDirectories(this.storageDirectory);
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.testPaths);
	}

	@Test
	@DisplayName("should replace the target when the expectation still holds")
	void shouldReplaceWhenTheExpectationHolds() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			evita.defineCatalog("source");
			evita.defineCatalog("target");
			final CatalogFolderId targetFolder = evita.getEngineState().boundFolderIdFor("target");
			assertNotNull(targetFolder);

			evita.replaceCatalogWithProgress(
					"source", "target",
					EngineMutationPrecondition.expectingBoundTo("target", targetFolder)
				)
				.onCompletion()
				.toCompletableFuture()
				.join();

			assertTrue(evita.getCatalogNames().contains("target"));
			assertFalse(
				evita.getCatalogNames().contains("source"),
				"The replacement consumed the source name, so an expectation that holds must not block the swap."
			);
		}
	}

	@Test
	@DisplayName("should refuse the swap when the target is no longer the catalog that was expected")
	void shouldRefuseWhenTheTargetWasSubstituted() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			evita.defineCatalog("source");
			evita.defineCatalog("target");
			// stands for the catalog that held the name when the operation was issued, which a drop and recreate
			// has since replaced - the generation is what tells the two apart, and it never repeats
			final CatalogFolderId supersededTarget = new CatalogFolderId("target_99");

			assertThrows(
				UnexpectedCatalogIncarnationException.class,
				() -> evita.replaceCatalogWithProgress(
					"source", "target",
					EngineMutationPrecondition.expectingBoundTo("target", supersededTarget)
				),
				"A swap whose target has been substituted must be refused, or it destroys a catalog nobody aimed at."
			);
			assertTrue(
				evita.getCatalogNames().contains("source") && evita.getCatalogNames().contains("target"),
				"A refused swap must leave both catalogs exactly as they were."
			);
		}
	}

	@Test
	@DisplayName("should reject constraining a name the mutation does not key")
	void shouldRejectAnUnkeyedConstraint() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			evita.defineCatalog("source");
			evita.defineCatalog("target");

			// the expectation would be tested once and then left unprotected: nothing stops another mutation from
			// taking `unrelated` the moment this one proceeds, so proving anything about it is meaningless
			assertThrows(
				GenericEvitaInternalError.class,
				() -> evita.replaceCatalogWithProgress(
					"source", "target",
					EngineMutationPrecondition.expectingUnbound("unrelated")
				),
				"Constraining a name the mutation emits no conflict key for has to be refused as a programming error."
			);
		}
	}

	@Nonnull
	private Evita bootEvita() {
		return new Evita(
			newTestEvitaConfigurationBuilder(this.testPaths)
				.storage(
					StorageOptions.builder()
						.storageDirectory(this.storageDirectory)
						.workDirectory(this.testPaths.work())
						.build()
				)
				.transaction(
					TransactionOptions.builder()
						.transactionMemoryBufferLimitSizeBytes(1024 << 10)
						.transactionMemoryRegionCount(4)
						.build()
				)
				.build()
		);
	}

}
