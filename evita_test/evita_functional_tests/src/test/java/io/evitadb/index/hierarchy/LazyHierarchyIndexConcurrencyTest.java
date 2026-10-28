/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.hierarchy;

import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races transactional first writes into a fresh hierarchy store. Every writer must find its own root through the
 * published store inside its transaction and in its merged copy. The copies remain independent, as in the existing
 * index transaction tests; one writer never installs its copy over another's baseline.
 *
 * Repeating the barrier race on fresh indexes exercises speculative construction without reflection or a production
 * seam. Calibrate by making the node-store CAS loser return its own candidate: the root then disappears from the
 * normal read path. This complements the allocation and rollback coverage in {@link LazyHierarchyNodeStoreTest}.
 *
 * Calibrated on 2026-10-28: returning the losing store candidate fails with "First root write disappeared" at
 * 128 rounds and 16 writers. Re-run that counterfactual after changing store construction or the access path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(HIERARCHY)
@Tag(TRANSACTION)
@DisplayName("Concurrent first writes into a lazy hierarchy store")
class LazyHierarchyIndexConcurrencyTest {
	/**
	 * Independent allocations, including the narrower warmed-up construction window.
	 */
	private static final int ROUNDS = 128;
	/**
	 * Writers released together for every fresh store.
	 */
	private static final int WRITERS = 16;

	/**
	 * The published store must own each transaction's writes even when that thread loses the publication race.
	 */
	@Test
	@DisplayName("Every first writer reads its root through the published store")
	void shouldKeepEveryFirstRootInPublishedStore() throws Exception {
		final ExecutorService writers = Executors.newFixedThreadPool(
			WRITERS, Thread.ofPlatform().daemon().name("hierarchy-first-writer-", 0).factory()
		);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				final HierarchyIndex index = new HierarchyIndex();
				final CyclicBarrier start = new CyclicBarrier(WRITERS);
				final List<Future<?>> probes = new ArrayList<>(WRITERS);
				for (int writer = 1; writer <= WRITERS; writer++) {
					final int recordId = writer;
					probes.add(writers.submit(() -> assertStateAfterCommit(
						index,
						original -> {
							awaitStart(start);
							original.addNode(recordId, null);
							assertVisible(original, recordId);
						},
						(original, committed) -> assertVisible(committed, recordId)
					)));
				}
				for (final Future<?> probe : probes) {
					assertDoesNotThrow(
						() -> probe.get(30, TimeUnit.SECONDS),
						"First-write diff disappeared from the published index or could not be merged"
					);
				}
			}
		} finally {
			writers.shutdownNow();
			assertTrue(writers.awaitTermination(30, TimeUnit.SECONDS), "First writers did not stop");
		}
	}

	/**
	 * Releases the writers only after every transaction has reached its first write.
	 */
	private static void awaitStart(CyclicBarrier start) {
		try {
			start.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError("First-write barrier interrupted", ex);
		} catch (Exception ex) {
			throw new AssertionError("First-write barrier failed", ex);
		}
	}

	/**
	 * Reads through the published store instead of retaining any structure the insertion used.
	 */
	private static void assertVisible(HierarchyIndex index, int recordId) {
		assertTrue(
			index.getRootHierarchyNodes(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE).contains(recordId),
			"First root write disappeared"
		);
		assertEquals(1, index.getHierarchySizeIncludingOrphans());
	}
}
