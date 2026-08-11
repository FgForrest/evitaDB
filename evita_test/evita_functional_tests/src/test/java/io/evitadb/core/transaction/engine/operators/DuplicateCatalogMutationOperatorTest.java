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

package io.evitadb.core.transaction.engine.operators;


import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.core.transaction.engine.EngineStateUpdater;
import io.evitadb.exception.UnexpectedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests `DuplicateCatalogMutationOperator` — the operator that copies a live catalog into a freshly allocated
 * folder and registers the copy under a new name.
 *
 * The duplicate is one of the paths that materialise a catalog folder, so it takes an exclusive
 * `CatalogFolderReservation` on the target name. That claim is the subject of these tests: a name whose claim is
 * not given back becomes permanently un-materialisable, and the wedge refuses **restore** of that name too — which
 * is the disaster-recovery path, where picking another name is not an option.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("DuplicateCatalogMutationOperator tests")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Tag(SCHEMA)
@Tag(MANAGEMENT)
class DuplicateCatalogMutationOperatorTest {

	private static final String SOURCE_CATALOG_NAME = "duplicateSource";
	private static final String TARGET_CATALOG_NAME = "duplicateTarget";

	/**
	 * Asserts the target name carries no reservation any more, by taking one. The claim is exclusive, so a leaked
	 * reservation makes this throw `ConcurrentCatalogMaterializationException` — the exact wedge a leak produces
	 * for every later create, restore or duplicate of the name.
	 *
	 * @param folderContext context whose reservation map is under test
	 */
	private static void assertTargetNameIsMaterialisableAgain(@Nonnull CatalogFolderContext folderContext) {
		assertDoesNotThrow(
			() -> {
				try (final CatalogFolderReservation retry = folderContext.allocateFolderFor(TARGET_CATALOG_NAME)) {
					assertNotNull(retry.folderId());
				}
			},
			"A failed duplication must give its folder claim back - otherwise the target name stays " +
				"un-materialisable until the process restarts."
		);
	}

	@Nested
	@DisplayName("Folder claim is released however the copy ends")
	class ReservationRelease {

		@Test
		@DisplayName("should release the folder claim when the copy throws before returning a future")
		void shouldReleaseFolderClaimWhenCopySetupThrows(@TempDir Path storageDirectory) {
			// `duplicateCatalog` verifies the target directory, takes a directory read hold and walks the whole
			// source before it returns a future at all. Everything it throws therefore escapes on the calling
			// thread, while there is still no future to hang a release on.
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final Catalog sourceCatalog = mock(Catalog.class);
			when(sourceCatalog.duplicateTo(eq(TARGET_CATALOG_NAME), any()))
				.thenThrow(new UnexpectedIOException("source walk failed", "source walk failed"));

			final Evita evita = mock(Evita.class);
			when(evita.getCatalogInstanceOrThrowException(SOURCE_CATALOG_NAME)).thenReturn(sourceCatalog);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final DuplicateCatalogMutationOperator operator = new DuplicateCatalogMutationOperator(folderContext);

			assertThrows(
				UnexpectedIOException.class,
				() -> operator.applyMutation(
					UUID.randomUUID(),
					new DuplicateCatalogMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME),
					evita,
					transitionUpdater,
					completionUpdater
				)
			);

			assertTargetNameIsMaterialisableAgain(folderContext);
			// nothing was copied, so nothing may be registered
			verifyNoInteractions(transitionUpdater);
			verifyNoInteractions(completionUpdater);
		}

		@Test
		@DisplayName("should release the folder claim when the copy future completes exceptionally")
		void shouldReleaseFolderClaimWhenCopyFutureFails(@TempDir Path storageDirectory) {
			// The result mapper that finishes the duplication is a `thenApply` continuation of the copy, so it is
			// skipped precisely when the copy fails - which is the case the release exists for. The release has to
			// ride on a hook that fires on failure as well as on success.
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final ProgressingFuture<Void> failingCopy = new ProgressingFuture<>(
				1,
				progressingFuture -> {
					throw new UnexpectedIOException("copy failed midway", "copy failed midway");
				}
			);

			final Catalog sourceCatalog = mock(Catalog.class);
			when(sourceCatalog.duplicateTo(eq(TARGET_CATALOG_NAME), any())).thenReturn(failingCopy);

			final Evita evita = mock(Evita.class);
			when(evita.getCatalogInstanceOrThrowException(SOURCE_CATALOG_NAME)).thenReturn(sourceCatalog);

			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> transitionUpdater =
				mock(Consumer.class);
			@SuppressWarnings("unchecked") final Consumer<EngineStateUpdater> completionUpdater =
				mock(Consumer.class);

			final DuplicateCatalogMutationOperator operator = new DuplicateCatalogMutationOperator(folderContext);
			final ProgressingFuture<Void> duplication = operator.applyMutation(
				UUID.randomUUID(),
				new DuplicateCatalogMutation(SOURCE_CATALOG_NAME, TARGET_CATALOG_NAME),
				evita,
				transitionUpdater,
				completionUpdater
			);

			// a same-thread executor, so the whole chain - including the release hook registered before this
			// call - has run by the time `execute` returns. A pooled executor would leave the assertion below
			// racing the completion callback.
			duplication.execute(Runnable::run);
			assertThrows(ExecutionException.class, duplication::get);

			assertTargetNameIsMaterialisableAgain(folderContext);
			// the copy never finished, so the target catalog may not be registered
			verifyNoInteractions(transitionUpdater);
			verifyNoInteractions(completionUpdater);
		}

	}

}
