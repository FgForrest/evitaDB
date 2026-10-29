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

package io.evitadb.index.attribute;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.analysis.AttributeNormalizationResolver;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races first writes into each of the seven lazy attribute families through the public mutation paths.
 * Each writer has its own transaction and must read its own change through the published family, both before and
 * after merging its transaction. No transaction's merged copy is installed over another writer's snapshot.
 *
 * Every round uses a fresh index and releases all writers from a barrier inside their transactions. The repeated
 * races exercise speculative allocation without a production seam. Calibrate by making each CAS loser return its
 * own candidate: a missing sub-index, cached filter view, record or an unconsumed transactional layer must fail.
 * The shared-map paths first mark a key mutated, so an orphan marker can survive even if a later accessor finds the
 * winner before inserting the data; the existing merge support also asserts that every diff layer was consumed.
 * The range filter path covers the value tree, range companion and filter cache together; the folded unique path
 * additionally covers the unique view cache. The family parameter names the allocation site to mutate.
 *
 * Calibrated on 2026-10-28: mutating each of the seven CAS loser returns separately made the suite fail at every
 * site with 128 rounds and 16 writers. The marker-only first accesses fail the merge's complete-layer-sweep check;
 * the other sites fail on missing sub-indexes or cached filter views. Re-run these counterfactuals after changing
 * construction or the access paths, since a shorter allocation window can reduce the overlap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("Concurrent first writes into lazy attribute families")
class LazyAttributeIndexConcurrencyTest {
	/**
	 * Fresh indexes per family; repeating allocation also exercises the much narrower warmed-up construction window.
	 */
	private static final int ROUNDS = 128;
	/**
	 * Writers released together for every fresh family.
	 */
	private static final int WRITERS = 16;
	/**
	 * Real schema fixtures covering standalone unique, sort, chain, shared value/range/filter and folded unique maps.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
		),
		EntitySchema._internalBuild("product")
	)
		.withAttribute("uniqueIndex", String.class, it -> it.localized().unique())
		.withAttribute("sortIndex", Integer.class, AttributeSchemaEditor::sortable)
		.withAttribute("chainIndex", Predecessor.class, AttributeSchemaEditor::sortable)
		.withAttribute("sharedValueIndex", IntegerNumberRange.class, AttributeSchemaEditor::filterable)
		.withAttribute("sharedRangeIndex", IntegerNumberRange.class, AttributeSchemaEditor::filterable)
		.withAttribute("filterIndex", IntegerNumberRange.class, AttributeSchemaEditor::filterable)
		.withAttribute("uniqueViewIndex", String.class, AttributeSchemaEditor::unique)
		.toInstance();

	/**
	 * A discarded candidate must never receive a writer's transactional diff instead of the published winner.
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"uniqueIndex", "sortIndex", "chainIndex", "sharedValueIndex",
		"sharedRangeIndex", "filterIndex", "uniqueViewIndex"
	})
	@DisplayName("Every first writer reads its change through the published family")
	void shouldKeepEveryFirstWriteInPublishedFamily(String family) throws Exception {
		final EntityAttributeSchemaContract schema = SCHEMA.getAttribute(family).orElseThrow();
		final ExecutorService writers = Executors.newFixedThreadPool(
			WRITERS, Thread.ofPlatform().daemon().name("attribute-first-writer-", 0).factory()
		);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				final AttributeIndex index = new EntityAttributeIndex(
					"product", AttributeNormalizationResolver.LEGACY_NFD
				);
				final CyclicBarrier start = new CyclicBarrier(WRITERS);
				final List<Future<?>> probes = new ArrayList<>(WRITERS);
				for (int writer = 1; writer <= WRITERS; writer++) {
					final int recordId = writer;
					probes.add(writers.submit(() -> assertStateAfterCommit(
						index,
						original -> {
							awaitStart(start);
							insert(original, family, schema, recordId);
							assertVisible(original, family, schema, recordId);
						},
						(original, committed) -> assertVisible(committed, family, schema, recordId)
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
	 * Uses the normal write path that first allocates the requested family.
	 */
	private static void insert(
		AttributeIndex index, String family, EntityAttributeSchemaContract schema, int recordId
	) {
		switch (family) {
			case "uniqueIndex" -> index.insertUniqueAttribute(
				null, schema, Set.of(Locale.ENGLISH), Scope.LIVE, Locale.ENGLISH, "value" + recordId, recordId
			);
			case "sortIndex" -> index.insertSortAttribute(null, schema, Set.of(), null, recordId, recordId);
			case "chainIndex" -> index.insertSortAttribute(null, schema, Set.of(), null, Predecessor.HEAD, recordId);
			case "uniqueViewIndex" -> index.insertFilterAttribute(
				null, schema, Set.of(), null, "value" + recordId, recordId, true
			);
			case "sharedValueIndex", "sharedRangeIndex", "filterIndex" -> index.insertFilterAttribute(
				null, schema, Set.of(), null, IntegerNumberRange.between(recordId, recordId + 100), recordId, false
			);
			default -> throw new AssertionError("Unknown family " + family);
		}
	}

	/**
	 * Reads only through the index's published field, so a write into an orphan map cannot satisfy the assertion.
	 */
	private static void assertVisible(
		AttributeIndex index, String family, EntityAttributeSchemaContract schema, int recordId
	) {
		switch (family) {
			case "uniqueIndex", "uniqueViewIndex" -> {
				final UniqueIndex unique = index.getUniqueIndex(
					null, schema, Scope.LIVE, family.equals("uniqueIndex") ? Locale.ENGLISH : null
				);
				assertNotNull(unique, family + " lost its first writer's sub-index");
				assertEquals(recordId, unique.getRecordIdByUniqueValue("value" + recordId));
			}
			case "sortIndex" -> {
				final SortIndex sort = index.getSortIndex(null, schema, null);
				assertNotNull(sort, family + " lost its first writer's sub-index");
				assertTrue(sort.getRecordsEqualTo(recordId).contains(recordId), "Sort write disappeared");
			}
			case "chainIndex" -> {
				final ChainIndex chain = index.getChainIndex(null, schema, null);
				assertNotNull(chain, family + " lost its first writer's sub-index");
				assertTrue(chain.getPredecessorPrimaryKey(recordId).isPresent(), "Chain write disappeared");
			}
			case "sharedValueIndex", "sharedRangeIndex", "filterIndex" -> {
				final FilterIndex filter = index.getFilterIndex(null, schema, null);
				assertNotNull(filter, family + " lost its first writer's sub-index");
				assertTrue(
					filter.getRecordsEqualTo(IntegerNumberRange.between(recordId, recordId + 100)).contains(recordId),
					"Shared value write disappeared"
				);
				assertTrue(filter.getRecordsValidIn(recordId).contains(recordId), "Shared range write disappeared");
			}
			default -> throw new AssertionError("Unknown family " + family);
		}
	}
}
