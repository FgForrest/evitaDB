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

package io.evitadb.core.management;

import io.evitadb.api.exception.ConcurrentCatalogMaterializationException;
import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.engine.TestCatalogFolderContexts;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the handover that keeps a catalog restore's folder claim from being released twice — or by the wrong
 * party.
 *
 * A restore is the only materialising path whose claim outlives the call that took it: the folder is allocated
 * while the archive is unpacked, but the mutation that binds a catalog to it runs in a *later* task step and
 * resolves the folder by catalog **name**. Meanwhile `SequentialTask#cancel()` completes the task's future
 * without stopping a step already running, so the completion hook can fire while that registering step is
 * mid-flight. Releasing the name there lets a second restore take it and the first bind its catalog to the
 * second one's half-written folder.
 *
 * `RestoreFolderClaim` is the seam that makes the two mutually exclusive, and it is what these tests drive.
 * The interleaving *inside* `EvitaManagement#createRestorationTask` is not reachable from a test — the restoring
 * step needs a real archive, and there is no seam to block inside the registering step — so the whole
 * exactly-once policy deliberately lives in this one small type instead of being spread across that method,
 * where it could only have been tested by duplicating it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Restore folder claim handover")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class RestoreFolderClaimTest {

	private static final String CATALOG_NAME = "restoredCatalog";

	@Nested
	@DisplayName("Allocating the folder")
	class Allocating {

		@Test
		@DisplayName("Answers with the folder it already took rather than allocating a second one")
		void shouldAnswerWithTheFolderItAlreadyTook(@TempDir Path storageDirectory) throws Exception {
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestoreFolderClaim claim = new RestoreFolderClaim();

			final CatalogFolderId first = claim.allocate(folderContext, CATALOG_NAME);
			final CatalogFolderId second = claim.allocate(folderContext, CATALOG_NAME);

			// Allocating twice would both strand the first directory and refuse against this restore's own
			// exclusive claim, so the second call has to answer rather than act.
			assertEquals(first, second);
			try (final Stream<Path> entries = Files.list(storageDirectory)) {
				assertEquals(1, entries.count(), "A second allocation would leave a stranded directory behind.");
			}
		}

		@Test
		@DisplayName("Keeps answering with its folder after the claim has changed hands")
		void shouldKeepAnsweringAfterTheClaimChangedHands(@TempDir Path storageDirectory) {
			// The folder token is held in a different field from the takeable claim, on purpose. Overloading one
			// field with both "which folder" and "who owns the release" would turn this second read into a
			// failure the moment the claim moved - and the registering step takes the claim before it runs.
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestoreFolderClaim claim = new RestoreFolderClaim();

			final CatalogFolderId allocated = claim.allocate(folderContext, CATALOG_NAME);
			assertNotNull(claim.takeClaim());

			assertEquals(allocated, claim.allocate(folderContext, CATALOG_NAME));
		}

	}

	@Nested
	@DisplayName("Handing the claim over")
	class HandingOver {

		@Test
		@DisplayName("Yields the claim once and nothing thereafter")
		void shouldYieldTheClaimExactlyOnce(@TempDir Path storageDirectory) {
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestoreFolderClaim claim = new RestoreFolderClaim();
			claim.allocate(folderContext, CATALOG_NAME);

			final CatalogFolderReservation taken = claim.takeClaim();
			assertNotNull(taken, "The party that gets there first must receive the claim.");
			// The loser has to be told, not handed a second reference - one release site, or the name could be
			// freed while the registering step is still resolving it.
			assertNull(claim.takeClaim(), "The claim must not be handed out twice.");

			// Until the winner releases it, the name stays exclusively held.
			assertThrows(
				ConcurrentCatalogMaterializationException.class,
				() -> folderContext.allocateFolderFor(CATALOG_NAME)
			);
			taken.close();
			try (final CatalogFolderReservation reclaimed = folderContext.allocateFolderFor(CATALOG_NAME)) {
				assertNotNull(reclaimed.folderId());
			}
		}

		@Test
		@DisplayName("Yields the claim to exactly one of many simultaneous takers")
		void shouldYieldTheClaimToExactlyOneOfManyTakers(@TempDir Path storageDirectory) throws Exception {
			// The real race has two takers - the registering step and the completion hook - but the guarantee is
			// the atomic one, so this drives it with enough contenders that a non-atomic implementation loses.
			final int takers = 16;
			final CatalogFolderContext folderContext = TestCatalogFolderContexts.onDirectory(storageDirectory);
			final RestoreFolderClaim claim = new RestoreFolderClaim();
			claim.allocate(folderContext, CATALOG_NAME);

			final ThreadFactory daemonThreads = runnable -> {
				final Thread thread = new Thread(runnable, "restore-claim-taker");
				thread.setDaemon(true);
				return thread;
			};
			final ExecutorService executor = Executors.newFixedThreadPool(takers, daemonThreads);
			try {
				final CountDownLatch startGate = new CountDownLatch(1);
				final CountDownLatch finished = new CountDownLatch(takers);
				final AtomicInteger winners = new AtomicInteger();
				for (int i = 0; i < takers; i++) {
					executor.execute(
						() -> {
							try {
								startGate.await();
								if (claim.takeClaim() != null) {
									winners.incrementAndGet();
								}
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							} finally {
								finished.countDown();
							}
						}
					);
				}
				startGate.countDown();
				assertTrue(finished.await(30, TimeUnit.SECONDS), "Takers did not finish within the budget.");
				assertEquals(1, winners.get(), "Exactly one taker may own the release.");
			} finally {
				executor.shutdownNow();
			}
		}

	}

}
