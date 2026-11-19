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
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.engine.EngineMutationProgressStatus.Status;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.evitadb.utils.CollectionUtils.createHashSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies through a real engine that every engine mutation started by a `...WithProgress` method is tracked
 * automatically: its progress carries an id, and the outcome can be observed by that id after the operation has
 * finished.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine mutation progress tracking")
@Tag(ENGINE)
@Tag(TASK)
class EngineMutationProgressTrackingTest implements EvitaTestSupport {
	private TestPaths testPaths;

	@BeforeEach
	void setUp() throws IOException {
		this.testPaths = createTestPaths(EngineMutationProgressTrackingTest.class.getSimpleName());
		Files.createDirectories(this.testPaths.storage());
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.testPaths);
	}

	@Test
	@DisplayName("should track every engine operation under a distinct id and retain its outcome")
	void shouldTrackEveryOperationAndRetainOutcome() {
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			evita.defineCatalog("a");
			final Set<UUID> ids = createHashSet(16);

			final EngineMutationProgressStatus alive = runAndObserve(
				evita, ids, () -> evita.makeCatalogAliveWithProgress("a")
			);
			assertNotNull(alive.catalogVersion(), "Making alive reports the catalog version.");
			assertNotNull(alive.catalogSchemaVersion());
			runAndObserve(evita, ids, () -> evita.duplicateCatalogWithProgress("a", "b"));
			runAndObserve(evita, ids, () -> evita.activateCatalogWithProgress("b"));
			runAndObserve(evita, ids, () -> evita.makeCatalogImmutableWithProgress("b"));
			runAndObserve(evita, ids, () -> evita.makeCatalogMutableWithProgress("b"));
			runAndObserve(evita, ids, () -> evita.renameCatalogWithProgress("b", "c"));
			runAndObserve(evita, ids, () -> evita.replaceCatalogWithProgress("c", "a"));
			runAndObserve(evita, ids, () -> evita.deactivateCatalogWithProgress("a"));
			runAndObserve(evita, ids, () -> evita.deleteCatalogIfExistsWithProgress("a").orElseThrow());

			assertEquals(9, ids.size(), "Every operation must have its own id.");
			// the name-keyed lookup keeps its semantics: an entry exists only while the operation runs
			assertTrue(evita.getEngineMutationProgress("a").isEmpty());
		}
	}

	/**
	 * Runs the operation, waits for it to finish, then subscribes to its progress by id and returns the terminal
	 * item the late subscriber receives.
	 */
	@Nonnull
	private static EngineMutationProgressStatus runAndObserve(
		@Nonnull Evita evita,
		@Nonnull Set<UUID> ids,
		@Nonnull Supplier<Progress<?>> operation
	) {
		final Progress<?> progress = operation.get();
		final UUID id = progress.progressId().orElseThrow();
		assertTrue(ids.add(id));
		progress.onCompletion().toCompletableFuture().join();

		final List<EngineMutationProgressStatus> items = new CopyOnWriteArrayList<>();
		final CompletableFuture<Void> done = new CompletableFuture<>();
		evita.subscribeToEngineMutationProgress(id).subscribe(
			new Flow.Subscriber<>() {
				@Override
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(EngineMutationProgressStatus item) {
					items.add(item);
				}

				@Override
				public void onError(Throwable throwable) {
					done.completeExceptionally(throwable);
				}

				@Override
				public void onComplete() {
					done.complete(null);
				}
			}
		);
		done.orTimeout(30, TimeUnit.SECONDS).join();
		assertEquals(1, items.size());
		final EngineMutationProgressStatus terminal = items.get(0);
		assertEquals(id, terminal.progressId());
		assertEquals(Status.SUCCEEDED, terminal.status());
		assertEquals(100, terminal.percentCompleted());
		return terminal;
	}

	@Nonnull
	private Evita bootEvita() {
		return new Evita(
			newTestEvitaConfigurationBuilder(this.testPaths)
				.storage(
					StorageOptions.builder()
						.storageDirectory(this.testPaths.storage())
						.workDirectory(this.testPaths.work())
						.build()
				)
				.build()
		);
	}

}
