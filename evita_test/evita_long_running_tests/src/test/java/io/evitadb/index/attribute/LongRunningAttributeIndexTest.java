/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Generational randomized atomic-rollback proof for the {@link AttributeIndex} container (Ref: #569). Unlike the
 * per-sub-index long-running tests (unique / filter / sort / chain), this drives the **container** directly so it
 * exercises the four nested transactional maps together — the standalone `unique` owner map, the shared filter value
 * map (read through filter views), the `sort` owner map and the `chain` map — and proves that a rolled-back transaction
 * discards every in-transaction mutation across all of them, leaving the base container byte-for-byte intact.
 *
 * Each generation rebuilds a fresh container from a (random-walking) reference model, captures a value oracle of that
 * base, applies a random batch of add/remove mutations inside a transaction that is then rolled back, and asserts the
 * base container is unchanged and no committed value was published. The `snapshot` oracle here is also the reader used
 * by the sibling savepoint proof {@code LongRunningSavepointAttributeIndexTest} (Ref: #1252).
 *
 * A record mutation is atomic over three sub-indexes (a per-record globally-unique code, a repeatable filterable name
 * and a repeatable sortable priority); a chain mutation is a tail append / tail removal over a single consistent
 * predecessor chain, so the chain map both mutates and — once it empties — drops and later re-creates its entry.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("AttributeIndex (generational atomic-rollback proof)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningAttributeIndexTest implements TimeBoundedTestSupport {
	static final String ENTITY_TYPE = "product";
	static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);
	private static final String ATTRIBUTE_GLOBAL_CODE = "globalCode";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	/** Cap on the number of live records so the add/remove random walk stays bounded. */
	private static final int RECORD_CAP = 40;
	/** Cap on the chain length so the tail-append random walk stays bounded. */
	private static final int CHAIN_CAP = 30;
	/** Record-id base for chain elements, kept clear of the record map's small ids for readable reproduction logs. */
	private static final int CHAIN_BASE = 100_000;
	/** Number of distinct filterable-name values — small so records cluster and the inverted buckets hold many ids. */
	private static final int FILTER_VALUE_SPACE = 8;
	/** Modulus of the sortable priority — small so records tie and the sort index's cardinality map is exercised. */
	private static final int SORT_VALUE_SPACE = 50;
	/** Upper bound (inclusive after +1) on the number of mutations applied per transaction. */
	private static final int MAX_OPS = 5;

	/**
	 * Catalog + product schema scaffolding assembled through the real {@link InternalEntitySchemaBuilder} so every
	 * fixture is a schema the engine could actually receive.
	 */
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);
	private static final EntitySchema PRODUCT_SCHEMA = EntitySchema._internalBuild(ENTITY_TYPE);
	/**
	 * A single product schema carrying one attribute per container sub-index:
	 *
	 * - `globalCode` — localized + unique ACROSS locales: the non-foldable standalone unique case (unique map)
	 * - `name` — non-localized, filterable (shared filter value map, read through a filter view)
	 * - `priority` — {@link Integer} sortable (sort owner map)
	 * - `order` — {@link Predecessor} sortable (chain map)
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, PRODUCT_SCHEMA
	)
		.withAttribute(ATTRIBUTE_GLOBAL_CODE, String.class, thatIs -> thatIs.localized().unique())
		.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
		.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, AttributeSchemaEditor::sortable)
		.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
		.toInstance();
	static final EntityAttributeSchemaContract GLOBAL_UNIQUE_LOCALIZED_CODE = entityAttr(ATTRIBUTE_GLOBAL_CODE);
	static final EntityAttributeSchemaContract FILTERABLE_NAME = entityAttr(ATTRIBUTE_NAME);
	static final EntityAttributeSchemaContract SORTABLE_PRIORITY = entityAttr(ATTRIBUTE_PRIORITY);
	static final EntityAttributeSchemaContract CHAIN_ORDER = entityAttr(ATTRIBUTE_ORDER);

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * container byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh
	 * container from the (random-walking) reference model, captures a value oracle of that base, applies a random batch
	 * of add/remove mutations inside a transaction that is then rolled back, and asserts the base container is unchanged
	 * and no committed value was published.
	 */
	@ParameterizedTest(name = "AttributeIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(512), new TreeSet<>(), new ArrayList<>()),
			(random, testState) -> {
				final TreeSet<Integer> records = testState.records();
				final List<Integer> chain = testState.chain();
				final StringBuilder code = testState.code();

				code.append("final AttributeIndex index = new EntityAttributeIndex(\"").append(ENTITY_TYPE).append("\");\n");
				final AttributeIndex index = buildIndex(records, chain, code);
				// value oracle of the base state that the rollback must return to
				final AttributeSnapshot beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, records, chain, code),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + code);
						assertEquals(beforeRollback, snapshot(original),
							"AttributeIndex changed after rollback — atomic rollback leaked!\n" + code);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base containers
				return new TestState(new StringBuilder(512), records, chain);
			}
		);
	}

	/**
	 * Applies a random batch of 1..{@link #MAX_OPS} add/remove mutations to `index`, mirroring each into the `records`
	 * (unique + filter + sort) and `chain` reference models so they stay in lockstep. Roughly a quarter of the ops touch
	 * the chain (tail append / tail removal over a single consistent predecessor chain); the rest are record ops, each
	 * atomic over the three record sub-indexes.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull AttributeIndex index,
		@Nonnull TreeSet<Integer> records,
		@Nonnull List<Integer> chain,
		@Nonnull StringBuilder code
	) {
		final int operations = 1 + random.nextInt(MAX_OPS);
		for (int i = 0; i < operations; i++) {
			if (random.nextInt(4) == 0) {
				// CHAIN op: tail append or tail removal keeps the single chain consistent
				if (chain.isEmpty() || (chain.size() < CHAIN_CAP && random.nextBoolean())) {
					final int predecessorId = chain.isEmpty() ? 0 : chain.get(chain.size() - 1);
					final int recordId = chain.isEmpty() ? CHAIN_BASE : chain.get(chain.size() - 1) + 1;
					code.append("chainInsert(index, ").append(predecessorId).append(", ").append(recordId).append(");\n");
					chainInsert(index, predecessorId, recordId);
					chain.add(recordId);
				} else {
					final int recordId = chain.remove(chain.size() - 1);
					code.append("chainRemove(index, ").append(recordId).append(");\n");
					chainRemove(index, recordId);
				}
			} else {
				// RECORD op: a fresh id (max + 1, always absent) on add; an existing id on remove
				if (records.isEmpty() || (records.size() < RECORD_CAP && random.nextBoolean())) {
					final int recordId = records.isEmpty() ? 1 : records.last() + 1;
					code.append("addRecord(index, ").append(recordId).append(");\n");
					addRecord(index, recordId);
					records.add(recordId);
				} else {
					final List<Integer> present = new ArrayList<>(records);
					final int recordId = present.get(random.nextInt(present.size()));
					code.append("removeRecord(index, ").append(recordId).append(");\n");
					removeRecord(index, recordId);
					records.remove(recordId);
				}
			}
		}
	}

	/**
	 * Rebuilds a fresh container from the reference model: replays every live record (three sub-index inserts each) and
	 * the chain in order (HEAD then successive predecessors). Appends the equivalent calls to `code` for reproduction.
	 */
	@Nonnull
	private static AttributeIndex buildIndex(
		@Nonnull TreeSet<Integer> records,
		@Nonnull List<Integer> chain,
		@Nonnull StringBuilder code
	) {
		final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
		for (final int recordId : records) {
			code.append("addRecord(index, ").append(recordId).append(");\n");
			addRecord(index, recordId);
		}
		int predecessorId = 0;
		for (final int recordId : chain) {
			code.append("chainInsert(index, ").append(predecessorId).append(", ").append(recordId).append(");\n");
			chainInsert(index, predecessorId, recordId);
			predecessorId = recordId;
		}
		return index;
	}

	/**
	 * Inserts a single record into the three record sub-indexes: a globally-unique code (standalone unique owner), a
	 * repeatable filterable name and a repeatable sortable priority — all derived deterministically from `recordId`.
	 */
	static void addRecord(@Nonnull AttributeIndex index, int recordId) {
		index.insertUniqueAttribute(
			null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, uniqueValue(recordId), recordId
		);
		index.insertFilterAttribute(
			null, FILTERABLE_NAME, ALLOWED_LOCALES, null, filterValue(recordId), recordId, false
		);
		index.insertSortAttribute(
			null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, sortValue(recordId), recordId
		);
	}

	/**
	 * Removes a single record from the three record sub-indexes, recomputing the same deterministic values used on add.
	 */
	static void removeRecord(@Nonnull AttributeIndex index, int recordId) {
		index.removeUniqueAttribute(
			null, GLOBAL_UNIQUE_LOCALIZED_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, uniqueValue(recordId), recordId
		);
		index.removeFilterAttribute(
			null, FILTERABLE_NAME, ALLOWED_LOCALES, null, filterValue(recordId), recordId
		);
		index.removeSortAttribute(
			null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, sortValue(recordId), recordId
		);
	}

	/**
	 * Appends `recordId` to the predecessor chain right after `predecessorId` (`0` = HEAD) through the container's sort
	 * mutation path, which routes a {@link Predecessor} value to the chain map.
	 */
	static void chainInsert(@Nonnull AttributeIndex index, int predecessorId, int recordId) {
		index.insertSortAttribute(
			null, CHAIN_ORDER, ALLOWED_LOCALES, null,
			predecessorId == 0 ? Predecessor.HEAD : new Predecessor(predecessorId), recordId
		);
	}

	/**
	 * Removes `recordId` from the predecessor chain. The value argument only shapes the (value-independent) chain key, so
	 * a placeholder {@link Predecessor#HEAD} suffices — the removal itself targets `recordId`.
	 */
	static void chainRemove(@Nonnull AttributeIndex index, int recordId) {
		index.removeSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, recordId);
	}

	/**
	 * Reads the full logical content of the container into a value-comparable snapshot — the ascending record ids of the
	 * unique and filter sub-indexes, the priority-ordered record ids of the sort sub-index and the predecessor order of
	 * the chain sub-index — so two snapshots taken before and after a rollback can be compared with `.equals` to prove
	 * exact restoration. An absent (dropped, empty) sub-index reads as an empty list.
	 *
	 * Every sub-index is read through a SIDE-EFFECT-FREE accessor, which matters for the sibling savepoint proof
	 * ({@code LongRunningSavepointAttributeIndexTest}) that commits the transaction and asserts the commit-time layer
	 * sweep left nothing dangling: the sort content is read via {@link SortIndex#getSortedRecords()} (a plain read of the
	 * positional façade) rather than {@code getAscendingOrderRecordsSupplier().getSortedRecordIds()}, because the supplier
	 * lazily creates the sort index's `SortIndexChanges` diff layer as a read side-effect — a layer the producer map's
	 * `O(Δ)` commit only sweeps when the key was marked mutated, so an oracle read of an un-mutated sort index would leave
	 * a phantom layer and fail the sweep. Both accessors yield the identical `sortedRecords.getArray()` content.
	 */
	@Nonnull
	static AttributeSnapshot snapshot(@Nonnull AttributeIndex index) {
		final UniqueIndex uniqueIndex = index.getUniqueIndex(null, GLOBAL_UNIQUE_LOCALIZED_CODE, Scope.LIVE, Locale.ENGLISH);
		final FilterIndex filterIndex = index.getFilterIndex(null, FILTERABLE_NAME, null);
		final SortIndex sortIndex = index.getSortIndex(null, SORTABLE_PRIORITY, null);
		final ChainIndex chainIndex = index.getChainIndex(null, CHAIN_ORDER, null);
		return new AttributeSnapshot(
			uniqueIndex == null ? List.of() : toList(uniqueIndex.getRecordIds().getArray()),
			filterIndex == null ? List.of() : toList(filterIndex.getAllRecords().getArray()),
			sortIndex == null ? List.of() : toList(sortIndex.getSortedRecords()),
			chainIndex == null ? List.of() : toList(chainIndex.getUnorderedLookup().getArray())
		);
	}

	/**
	 * Converts a record-id array into a value type with deep `.equals`, preserving the array's order (ascending for the
	 * bitmap-backed sub-indexes, priority/chain order for the sort and chain sub-indexes).
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull int[] array) {
		final List<Integer> list = new ArrayList<>(array.length);
		for (final int value : array) {
			list.add(value);
		}
		return list;
	}

	/**
	 * Globally-unique code value for `recordId` — a distinct string per record, so the standalone unique index never
	 * sees a duplicate key.
	 */
	@Nonnull
	private static String uniqueValue(int recordId) {
		return "U" + recordId;
	}

	/**
	 * Filterable name value for `recordId` — drawn from a small space so multiple records share a value and the inverted
	 * buckets carry more than one id.
	 */
	@Nonnull
	private static String filterValue(int recordId) {
		return "F" + (recordId % FILTER_VALUE_SPACE);
	}

	/**
	 * Sortable priority for `recordId` — drawn from a small space so records tie and the sort index's cardinality map is
	 * exercised.
	 */
	private static int sortValue(int recordId) {
		return (recordId * 7) % SORT_VALUE_SPACE;
	}

	/**
	 * Pulls the entity-level attribute schema with the given name out of the shared {@link #SCHEMA}.
	 *
	 * @param name the attribute name declared on {@link #SCHEMA}
	 * @return the assembled {@link EntityAttributeSchemaContract}
	 */
	@Nonnull
	private static EntityAttributeSchemaContract entityAttr(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	/**
	 * Reference model carried across generations: the (random-walking) set of live record ids, the ordered predecessor
	 * chain and a reproduction buffer of the calls that built and mutated the current base container.
	 *
	 * @param code    reproduction buffer of the calls issued this generation
	 * @param records live record ids present in the unique + filter + sort sub-indexes
	 * @param chain   ordered record ids forming the single consistent predecessor chain
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull TreeSet<Integer> records,
		@Nonnull List<Integer> chain
	) {}

	/**
	 * Value-comparable snapshot of an {@link AttributeIndex}: the record ids held by each of the four container
	 * sub-indexes. Record equality gives deep structural comparison, so a rollback that leaks any change fails the proof.
	 *
	 * @param unique ascending record ids of the standalone unique index
	 * @param filter ascending record ids of the shared filter value index (read through its view)
	 * @param sort   priority-ordered record ids of the sort index
	 * @param chain  predecessor-ordered record ids of the chain index
	 */
	record AttributeSnapshot(
		@Nonnull List<Integer> unique,
		@Nonnull List<Integer> filter,
		@Nonnull List<Integer> sort,
		@Nonnull List<Integer> chain
	) {}

}
