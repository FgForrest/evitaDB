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

package io.evitadb.index;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.index.IndexHeapSizeAssertions.AUTOBOX_CACHE_CEILING;
import static io.evitadb.index.IndexHeapSizeAssertions.assertDivergenceDoesNotGrowWithTheData;
import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.excluded;
import static io.evitadb.index.IndexHeapSizeAssertions.measuredHeapOf;
import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures every container index's `getHeapSizeInBytes` against what JOL actually finds on the heap.
 *
 * A container owns sub-indexes rather than data, so what these assertions really pin is **who charges what**: an
 * {@link AttributeIndex} charges the shared value tree that its filter, folded-unique and sort views all point at,
 * and each of those views charges only its own object; a {@link io.evitadb.index.facet.FacetGroupIndex} charges the
 * facets beneath it but not the boxed group id the map above filed it under. Get either wrong and the figure moves
 * with the number of *views* of the data rather than with the data.
 *
 * See `documentation/developer/heap-size-testing.md` for the ownership rules and the traps behind these assertions —
 * in particular trap 4, which is why the exclusion lists below reach into every sub-index rather than naming fields
 * of the container alone.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@DisplayName("Container index heap size")
class ContainerIndexHeapSizeTest {

	private static final String ENTITY_TYPE = "product";
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);
	/**
	 * Reference names the facet fixtures index under; they stand in for the schema's, which is what a real facet
	 * index is handed and never owns.
	 */
	private static final String[] REFERENCE_NAMES = {"brand", "category", "parameter"};
	/**
	 * Every attribute the fixture indexes, in the order the schema declares them.
	 */
	private static final String[] ATTRIBUTE_NAMES = {
		"code", "ean", "name", "validity", "priority", "weight", "order"
	};

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * One schema carrying an attribute of every shape an {@link AttributeIndex} files in a different sub-index map.
	 * A map left empty by the fixture is a branch the measurement never reaches, so all six are covered:
	 *
	 * - `code` — filterable, so it creates the shared value tree and its filter view
	 * - `ean` — non-localized unique, therefore FOLDABLE: it lives in the shared tree behind a folded unique view
	 * - `name` — localized and unique ACROSS locales, the one shape that cannot fold and keeps a standalone owner
	 * - `validity` — a range-typed filterable, which adds the sibling range structure
	 * - `priority` — sortable only, so its sort index owns its own tree
	 * - `weight` — filterable AND sortable, so its sort index is a view over the shared tree
	 * - `order` — a {@link Predecessor}, which goes to the chain index instead
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
		.withAttribute("ean", String.class, AttributeSchemaEditor::unique)
		.withAttribute("name", String.class, whatever -> whatever.localized().unique())
		.withAttribute("validity", IntegerNumberRange.class, AttributeSchemaEditor::filterable)
		.withAttribute("priority", Long.class, AttributeSchemaEditor::sortable)
		.withAttribute("weight", Integer.class, whatever -> whatever.filterable().sortable())
		.withAttribute("order", Predecessor.class, AttributeSchemaEditor::sortable)
		.toInstance();

	@Nonnull
	private static AttributeSchemaContract attribute(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	@Nested
	@DisplayName("attribute index")
	class AttributeIndexes {

		/**
		 * Everything an inverted index reaches but does not charge, and which an attribute index therefore reaches
		 * through it — trap 4.
		 */
		private static final String[] INVERTED_EXCLUSIONS = {
			"normalizer", "comparator", "pageStreamRegistry",
			"buckets.valueColumnFactory", "buckets.recordColumnFactory"
		};
		private static final String[] RANGE_EXCLUSIONS = {
			"pageStreamRegistry", "ranges.transactionalLayerWrapper"
		};
		private static final String[] FILTER_VIEW_EXCLUSIONS = {
			"normalizer", "comparator"
		};
		/**
		 * A sort index comes in two shapes and only the owner has a tree of its own to name the scaffolding of — a
		 * view reads the shared tree, whose scaffolding is already named through the filter view above it.
		 */
		private static final String[] SORT_EXCLUSIONS = {
			"comparator", "normalizer",
			"comparatorBase.0.orderDirection", "comparatorBase.0.orderBehaviour"
		};
		private static final String[] OWNER_SORT_EXCLUSIONS = {
			"ownedTree.normalizer", "ownedTree.comparator", "ownedTree.pageStreamRegistry",
			"ownedTree.buckets.valueColumnFactory", "ownedTree.buckets.recordColumnFactory"
		};
		private static final String[] CHAIN_EXCLUSIONS = {
			"pageStreamRegistry", "successorsByPredecessor.transactionalLayerWrapper"
		};
		/**
		 * A unique index likewise comes in two shapes: only the standalone owner has a value tree and a comparator of
		 * its own, while a folded view holds nothing but a pointer at the filter view above it.
		 */
		private static final String[] UNIQUE_EXCLUSIONS = {
			"entityType"
		};
		private static final String[] OWNER_UNIQUE_EXCLUSIONS = {
			"comparator", "pageStreamRegistry",
			"tree.valueColumnFactory", "tree.recordColumnFactory"
		};
		/**
		 * The container's own scaffolding: the collection name every index in it shares, and the transactional
		 * wrapper each sub-index map carries (a lambda the map does not own, and one JOL cannot walk into).
		 */
		/**
		 * The five per-family on-disk leaf-page snapshots, in the order the index declares them.
		 */
		private static final String[] SNAPSHOT_FIELDS = {
			"persistedChainLeafPages", "persistedFilterInvertedLeafPages", "persistedFilterRangeLeafPages",
			"persistedUniqueLeafPages", "persistedSortLeafPages"
		};
		private static final String[] OWN_EXCLUSIONS = {
			"entityType",
			"uniqueIndex.transactionalLayerWrapper", "sortIndex.transactionalLayerWrapper",
			"chainIndex.transactionalLayerWrapper", "sharedValueIndex.transactionalLayerWrapper",
			"sharedRangeIndex.transactionalLayerWrapper", "filterIndex.transactionalLayerWrapper",
			"uniqueViewIndex.transactionalLayerWrapper"
		};

		/**
		 * Seeds an attribute index with `records` entities, every value clearing {@link #AUTOBOX_CACHE_CEILING} so no
		 * box in the figure is the JVM's rather than the index's.
		 *
		 * @param records how many entities to index
		 * @return the seeded index
		 */
		@Nonnull
		private static EntityAttributeIndex seededIndex(int records) {
			final EntityAttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			for (int i = 0; i < records; i++) {
				final int pk = AUTOBOX_CACHE_CEILING + i + 1;
				insertFilter(index, "code", "code-" + pk, pk);
				insertFolded(index, "ean", "ean-" + pk, pk);
				insertOwnerUnique(index, "name", "name-" + pk, pk);
				insertFilter(index, "validity", IntegerNumberRange.between(pk, pk + 10), pk);
				index.insertSortAttribute(null, attribute("priority"), ALLOWED_LOCALES, null, (long) pk, pk);
				insertFilter(index, "weight", pk, pk);
				index.insertSortAttribute(null, attribute("weight"), ALLOWED_LOCALES, null, pk, pk);
				index.insertSortAttribute(
					null, attribute("order"), ALLOWED_LOCALES, null,
					i == 0 ? Predecessor.HEAD : new Predecessor(pk - 1), pk
				);
			}
			return index;
		}

		private static void insertFilter(
			@Nonnull AttributeIndex index, @Nonnull String attributeName,
			@Nonnull Serializable value, int recordId
		) {
			index.insertFilterAttribute(null, attribute(attributeName), ALLOWED_LOCALES, null, value, recordId, false);
		}

		/**
		 * Mirrors the mutator's foldable-unique insert: the unique write reports that enforcement belongs to the
		 * filter write, which then registers the folded view.
		 */
		private static void insertFolded(
			@Nonnull AttributeIndex index, @Nonnull String attributeName,
			@Nonnull Serializable value, int recordId
		) {
			final boolean folded = index.insertUniqueAttribute(
				null, attribute(attributeName), ALLOWED_LOCALES, Scope.LIVE, null, value, recordId
			) == AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE;
			assertTrue(folded, "`" + attributeName + "` must be the foldable unique shape");
			index.insertFilterAttribute(
				null, attribute(attributeName), ALLOWED_LOCALES, null, value, recordId, true
			);
		}

		/**
		 * Inserts the one unique shape that cannot fold — localized, unique across locales — which is the only way
		 * the standalone owner unique map is ever populated.
		 */
		private static void insertOwnerUnique(
			@Nonnull AttributeIndex index, @Nonnull String attributeName,
			@Nonnull Serializable value, int recordId
		) {
			final AttributeIndex.UniquenessEnforcement enforcement = index.insertUniqueAttribute(
				null, attribute(attributeName), ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, value, recordId
			);
			assertEquals(
				AttributeIndex.UniquenessEnforcement.BY_OWNER_INDEX, enforcement,
				"`" + attributeName + "` must keep a standalone owner unique index"
			);
		}

		/**
		 * Collects everything the index reaches but no part of the figure charges: the schema-owned components of
		 * every attribute key, and the scaffolding of every sub-index it owns.
		 *
		 * The sub-indexes live inside maps rather than behind field paths, so their exclusions are resolved against
		 * each instance and concatenated — the same lists their own tests use, which is exactly what trap 4 says has
		 * to happen when one index owns another.
		 *
		 * Each is resolved by its **schema**, never through `getFilterIndexes()` and friends: those return a
		 * `keySet()` view, which a `HashMap` allocates lazily and caches on first call. Asking for one would grow
		 * the very map about to be measured by sixteen bytes — the same reason {@code MapHeapSize} walks a map with
		 * `forEach` rather than iterating its entry set.
		 *
		 * @param index the index about to be walked
		 * @return the objects to hand the walker as borrowed roots
		 */
		@Nonnull
		private static Object[] borrowedRoots(@Nonnull AttributeIndex index) {
			final List<Object> roots = new ArrayList<>(64);
			// the attribute key is a record this index charges, but its components belong to the schema and the JVM
			roots.add(Locale.ENGLISH);
			for (final String attributeName : ATTRIBUTE_NAMES) {
				final AttributeSchemaContract attributeSchema = attribute(attributeName);
				roots.add(attributeSchema.getName());
				final Locale locale = attributeSchema.isLocalized() ? Locale.ENGLISH : null;
				final FilterIndex filterView = index.getFilterIndex(null, attributeSchema, locale);
				if (filterView != null) {
					Collections.addAll(roots, excluded(filterView, FILTER_VIEW_EXCLUSIONS));
					// once a range histogram has been asked for, the memoized subset carries the aggregation lambda
					// of the class that built it - a slot here, and a hidden class JOL cannot walk into
					Collections.addAll(
						roots, excluded(filterView, "memoizedRangeHistogramSubSet.aggregationLambda")
					);
					Collections.addAll(roots, excluded(filterView.getInvertedIndex(), INVERTED_EXCLUSIONS));
					final RangeIndex rangeIndex = filterView.getRangeIndex();
					if (rangeIndex != null) {
						Collections.addAll(roots, excluded(rangeIndex, RANGE_EXCLUSIONS));
					}
				}
				final SortIndex sortIndex = index.getSortIndex(null, attributeSchema, locale);
				if (sortIndex != null) {
					Collections.addAll(roots, excluded(sortIndex, SORT_EXCLUSIONS));
					if (sortIndex instanceof OwnerSortIndex) {
						Collections.addAll(roots, excluded(sortIndex, OWNER_SORT_EXCLUSIONS));
					}
				}
				final ChainIndex chainIndex = index.getChainIndex(null, attributeSchema, locale);
				if (chainIndex != null) {
					Collections.addAll(roots, excluded(chainIndex, CHAIN_EXCLUSIONS));
				}
				final UniqueIndex uniqueIndex = index.getUniqueIndex(null, attributeSchema, Scope.LIVE, locale);
				if (uniqueIndex != null) {
					Collections.addAll(roots, excluded(uniqueIndex, UNIQUE_EXCLUSIONS));
					if (uniqueIndex instanceof OwnerUniqueIndex) {
						Collections.addAll(roots, excluded(uniqueIndex, OWNER_UNIQUE_EXCLUSIONS));
					}
				}
			}
			return roots.toArray();
		}

		private static void assertExactlyMeasured(@Nonnull AttributeIndex index) {
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index, borrowedRoots(index), OWN_EXCLUSIONS
			);
		}

		/**
		 * Rebuilds `live` through the from-committed-maps constructor — the path both a cold load and a commit
		 * merge-copy take, and the ONLY one that populates the five on-disk leaf-page snapshots.
		 *
		 * @param live an index whose sub-indexes have already been flushed
		 * @return the rebuilt index
		 */
		@Nonnull
		private static EntityAttributeIndex rebuiltFromCommittedMaps(@Nonnull EntityAttributeIndex live) {
			return new EntityAttributeIndex(
				ENTITY_TYPE,
				familyOf(live, "uniqueIndex"),
				familyOf(live, "filterIndex"),
				familyOf(live, "uniqueViewIndex"),
				familyOf(live, "sortIndex"),
				familyOf(live, "chainIndex"),
				familyOf(live, "sharedValueIndex"),
				familyOf(live, "sharedRangeIndex")
			);
		}

		/**
		 * Copies one sub-index family out of a live index into the plain map the from-committed-maps constructor
		 * expects. A family nothing ever wrote to is not allocated at all, and reads back here as an empty map —
		 * which is exactly what the constructor is handed on a cold load of an index that has no such attribute.
		 *
		 * @param live  the index to read the family off
		 * @param field the family's field name
		 * @param <V>   the sub-index type held by the family
		 * @return a detached copy of the family's entries, empty when the family is absent
		 */
		@Nonnull
		@SuppressWarnings("unchecked")
		private static <V> Map<AttributeIndexKey, V> familyOf(
			@Nonnull EntityAttributeIndex live, @Nonnull String field
		) {
			final Map<AttributeIndexKey, V> family = (Map<AttributeIndexKey, V>) readField(live, field);
			return family == null ? new HashMap<>() : new HashMap<>(family);
		}

		@SuppressWarnings("unchecked")
		private static int snapshotEntries(@Nonnull EntityAttributeIndex index, @Nonnull String field) {
			return ((Map<AttributeIndexKey, int[]>) readField(index, field)).size();
		}

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final EntityAttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			assertExactlyMeasured(index);
		}

		@Test
		void shouldStayWithinTheKnownShortfallOfASeededIndex() {
			// A seeded container cannot be exact, and both reasons are documented elsewhere rather than new here:
			//
			// - its two derived VIEW maps are built by `CollectionUtils.createHashMap(32)` and hold a handful of
			//   entries, so each sits on a table four doublings larger than the entry count can reveal - the
			//   pre-sized case `MapHeapSize` states it reads low on, pinned by `TransactionalMapHeapSizeTest`
			// - every sub-index brings its own documented divergence, the chain index's pre-sized tables above all
			//
			// What matters is that the container adds nothing of its own on top, and the assertion that shows it is
			// the SCALE: a per-value term going uncharged would be tens of kilobytes at this fixture size, since the
			// index holds seven attributes over two hundred records
			final EntityAttributeIndex index = seededIndex(200);
			final long shortfall = measuredHeapOf(index, borrowedRoots(index), OWN_EXCLUSIONS)
				- index.getHeapSizeInBytes();
			assertTrue(
				shortfall > 0 && shortfall < 2_048,
				"the shortfall must stay within the known fixed causes - was " + shortfall
			);
		}

		@Test
		void shouldNotLetTheShortfallGrowWithTheRecordCount() {
			// eight times the data: every known cause of the shortfall above is per-map or per-leaf, so the figure
			// must barely move. A per-record term would grow it by tens of kilobytes
			final EntityAttributeIndex small = seededIndex(50);
			final EntityAttributeIndex large = seededIndex(400);
			final long smallShortfall = measuredHeapOf(small, borrowedRoots(small), OWN_EXCLUSIONS)
				- small.getHeapSizeInBytes();
			final long largeShortfall = measuredHeapOf(large, borrowedRoots(large), OWN_EXCLUSIONS)
				- large.getHeapSizeInBytes();
			assertTrue(
				Math.abs(largeShortfall - smallShortfall) < 512,
				"the shortfall must track maps and leaves, not records - " + smallShortfall + " to " + largeShortfall
			);
		}

		@Test
		void shouldChargeTheOnDiskLeafPageSnapshotsOfAFlushedIndex() {
			// the five snapshots are populated ONLY by the from-committed-maps constructor, and only for sub-indexes
			// that actually paged - a hand-built index leaves all five empty, so the ruling that they are charged
			// would be verified nowhere without this fixture
			final EntityAttributeIndex live = seededIndex(400);
			live.getModifiedStorageParts(1, new TrappedChanges());
			final EntityAttributeIndex loaded = rebuiltFromCommittedMaps(live);

			int pagedFamilies = 0;
			for (final String field : SNAPSHOT_FIELDS) {
				pagedFamilies += snapshotEntries(loaded, field) > 0 ? 1 : 0;
			}
			assertTrue(
				pagedFamilies > 0,
				"the fixture must page at least one family, or the leaf-page snapshots are never exercised"
			);

			// isolate the snapshots from every divergence the seeded container inherits: clear the five fields and
			// read the figure again. What the arithmetic drops must be exactly what the walk stops finding
			final long withSnapshots = loaded.getHeapSizeInBytes();
			final long measuredWithSnapshots = measuredHeapOf(loaded, borrowedRoots(loaded), OWN_EXCLUSIONS);
			for (final String field : SNAPSHOT_FIELDS) {
				writeField(loaded, field, Map.of());
			}
			final long withoutSnapshots = loaded.getHeapSizeInBytes();
			final long measuredWithoutSnapshots = measuredHeapOf(loaded, borrowedRoots(loaded), OWN_EXCLUSIONS);

			assertTrue(withSnapshots > withoutSnapshots, "the leaf-page snapshots must show up as occupancy");
			final long reportedDrop = withSnapshots - withoutSnapshots;
			final long measuredDrop = measuredWithSnapshots - measuredWithoutSnapshots;
			// a snapshot map is pre-sized from the SOURCE sub-index map's size and then filled only with the keys
			// that actually paged, so its bucket table is one the entry count cannot reconstruct - the model reports
			// the larger of the two shapes it could be, which is the standing rule and leaves the figure high rather
			// than low. What must hold is that the charge is of the right order and never below what is really there
			assertTrue(
				reportedDrop >= measuredDrop && reportedDrop - measuredDrop < 256,
				"the snapshots must be charged what they weigh, rounded up - reported " + reportedDrop
					+ " against measured " + measuredDrop
			);
		}

		@Test
		void shouldChargeWhatARangeAttributeMemoizesWhileAnsweringQueries() {
			// the two query memos of a filter view are the newest arithmetic in this block and nothing else here
			// reaches them: the all-records union, and the range histogram - which materializes a fresh bucket per
			// range point, each carrying a clone of the running active set, and is by far the larger of the two
			final EntityAttributeIndex index = seededIndex(200);
			final long cold = index.getHeapSizeInBytes();
			final FilterIndex validity = index.getFilterIndex(null, attribute("validity"), null);
			assertNotNull(validity, "the range attribute must have a filter view");

			validity.getAllRecordsFormula();
			final long warmUnion = index.getHeapSizeInBytes();
			assertTrue(warmUnion > cold, "the memoized union must show up as occupancy - " + cold + " to " + warmUnion);

			validity.getRangeHistogramOfAllRecords(Integer.class, 0);
			final long warmHistogram = index.getHeapSizeInBytes();
			assertTrue(
				warmHistogram > warmUnion,
				"the memoized range histogram must show up as occupancy - " + warmUnion + " to " + warmHistogram
			);

			// and both are charged what they weigh: the shortfall must stay where it was before the index answered
			// anything, rather than growing by the size of what the memos hold
			final long shortfall = measuredHeapOf(index, borrowedRoots(index), OWN_EXCLUSIONS) - warmHistogram;
			assertTrue(
				shortfall > 0 && shortfall < 2_048,
				"a memo charged short would show up here as the shortfall - was " + shortfall
			);
		}

		/**
		 * Replaces one of the leaf-page snapshots with the empty singleton, so the figure can be read with and
		 * without them and the difference attributed to nothing else.
		 *
		 * @param index the index to modify
		 * @param field the snapshot field to clear
		 * @param value the value to write
		 */
		private static void writeField(@Nonnull Object index, @Nonnull String field, @Nonnull Object value) {
			try {
				final Field declaredField = AttributeIndex.class.getDeclaredField(field);
				declaredField.setAccessible(true);
				declaredField.set(index, value);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				throw new IllegalStateException("Cannot clear the leaf-page snapshot `" + field + "`.", e);
			}
		}

		@Test
		void shouldChargeTheSharedTreeOnceHoweverManyViewsPointAtIt() {
			// `ean` is filterable-by-fold and `weight` is filterable AND sortable, so the shared trees are reached by
			// a filter view, a folded unique view and a sort view. The figure must not move when the same tree is
			// reached one more time, which is what a walk subtracting nothing proves
			final EntityAttributeIndex index = seededIndex(200);
			// `ean` is unique by fold and `weight` is filterable AND sortable, so two of the shared trees are reached
			// by a filter view, a folded unique view and a sort view at once. If any of them charged the tree the
			// figure would be a multiple of the data rather than the data - which at this fixture size would be tens
			// of kilobytes, not the fixed shortfall the assertion below leaves room for
			final long shortfall = measuredHeapOf(index, borrowedRoots(index), OWN_EXCLUSIONS)
				- index.getHeapSizeInBytes();
			assertTrue(
				shortfall > 0 && shortfall < 2_048,
				"a tree charged twice would show up as a figure far above the measurement - shortfall " + shortfall
			);
			// and the fixture really does carry both sort modes, so the view arm above is not vacuous
			assertInstanceOf(
				OwnerSortIndex.class, index.getSortIndex(null, attribute("priority"), null),
				"a sort-only attribute must own its tree"
			);
			assertInstanceOf(
				SortIndexView.class, index.getSortIndex(null, attribute("weight"), null),
				"a both-flagged attribute must read the shared tree"
			);
		}

	}

	@Nested
	@DisplayName("owner filter index")
	class OwnerFilterIndexes {

		/**
		 * The owner's own scaffolding plus that of the two structures it allocates — it is the histogram indexes that
		 * hold one of these, and they charge nothing of it, so everything below is named here rather than by an
		 * enclosing container.
		 */
		private static final String[] EXCLUSIONS = {
			"attributeIndexKey", "normalizer", "comparator",
			"invertedIndex.normalizer", "invertedIndex.comparator", "invertedIndex.pageStreamRegistry",
			"invertedIndex.buckets.valueColumnFactory", "invertedIndex.buckets.recordColumnFactory",
			"rangeIndex.pageStreamRegistry", "rangeIndex.ranges.transactionalLayerWrapper"
		};

		/**
		 * Builds an owner filter index over `values` distinct values, which — unlike every filter index an attribute
		 * index holds — owns its value tree and, for a range type, its range companion outright.
		 *
		 * @param values        how many distinct values to index
		 * @param attributeType the declared attribute type, a range type to exercise the range companion
		 * @return the seeded index
		 */
		@Nonnull
		private static OwnerFilterIndex ownerFilterIndex(int values, @Nonnull Class<?> attributeType) {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "validity", null), attributeType
			);
			for (int i = 0; i < values; i++) {
				final int pk = AUTOBOX_CACHE_CEILING + i + 1;
				index.addRecord(
					pk,
					attributeType == IntegerNumberRange.class ? IntegerNumberRange.between(pk, pk + 10) : pk
				);
			}
			return index;
		}

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "code", null), String.class
			);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, EXCLUSIONS);
		}

		@Test
		void shouldMeasureASeededRangeIndexExactly() {
			final OwnerFilterIndex index = ownerFilterIndex(200, IntegerNumberRange.class);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, EXCLUSIONS);
		}

		@Test
		void shouldChargeTheRangeCompanionOnTopOfTheValueTree() {
			// a range attribute allocates a second structure the value tree knows nothing about, and it is the
			// larger of the two - a figure that missed it would look plausible and be wrong by half
			final OwnerFilterIndex ranged = ownerFilterIndex(200, IntegerNumberRange.class);
			final OwnerFilterIndex plain = ownerFilterIndex(200, Integer.class);
			assertTrue(
				ranged.getHeapSizeInBytes() > plain.getHeapSizeInBytes(),
				"the range companion must cost something - " + ranged.getHeapSizeInBytes()
					+ " against " + plain.getHeapSizeInBytes()
			);
		}

		@Test
		void shouldNotLetTheDivergenceGrowWithTheValueCount() {
			final OwnerFilterIndex small = ownerFilterIndex(50, IntegerNumberRange.class);
			final OwnerFilterIndex large = ownerFilterIndex(400, IntegerNumberRange.class);
			assertDivergenceDoesNotGrowWithTheData(
				small.getHeapSizeInBytes(), small,
				large.getHeapSizeInBytes(), large,
				EXCLUSIONS
			);
		}

	}

	@Nested
	@DisplayName("facet index")
	class FacetIndexes {

		/**
		 * The dirty-reference set is excluded on the same standing as flush bookkeeping — it is empty for the whole
		 * lifetime of a read-only catalog — and the two maps carry a transactional wrapper they do not own.
		 */
		private static final String[] EXCLUSIONS = {
			"dirtyIndexes", "facetingEntities.transactionalLayerWrapper"
		};

		/**
		 * Seeds a facet index with `references` reference types, each carrying `groups` groups of `facets` facets,
		 * plus one non-grouped facet per reference type so the {@code notGroupedFacets} holder is exercised too.
		 *
		 * @param references how many reference types to index
		 * @param groups     how many groups per reference type
		 * @param facets     how many facets per group
		 * @return the seeded index
		 */
		@Nonnull
		private static FacetIndex seededIndex(int references, int groups, int facets) {
			final FacetIndex index = new FacetIndex();
			for (int r = 0; r < references; r++) {
				final String referenceName = REFERENCE_NAMES[r];
				for (int g = 0; g < groups; g++) {
					final int groupId = AUTOBOX_CACHE_CEILING + g + 1;
					for (int f = 0; f < facets; f++) {
						final int facetId = AUTOBOX_CACHE_CEILING + g * facets + f + 1;
						index.addFacet(
							null, new ReferenceKey(referenceName, facetId),
							groupId, AUTOBOX_CACHE_CEILING + f + 1
						);
					}
				}
				// one facet with no group at all, which lands in the notGroupedFacets holder
				index.addFacet(
					null, new ReferenceKey(referenceName, AUTOBOX_CACHE_CEILING + 9_999),
					null, AUTOBOX_CACHE_CEILING + 1
				);
			}
			return index;
		}

		/**
		 * The reference names are the schema's, so the walk has to be handed them as borrowed roots — a facet index
		 * charges the map's spine and its values, never the name it files them under.
		 */
		@Nonnull
		private static Object[] borrowedRoots() {
			return REFERENCE_NAMES.clone();
		}

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final FacetIndex index = new FacetIndex();
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, borrowedRoots(), EXCLUSIONS);
		}

		@Test
		void shouldMeasureASeededIndexExactly() {
			final FacetIndex index = seededIndex(3, 5, 20);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, borrowedRoots(), EXCLUSIONS);
		}

		@Test
		void shouldNotLetTheDivergenceGrowWithTheFacetCount() {
			final FacetIndex small = seededIndex(2, 2, 5);
			final FacetIndex large = seededIndex(3, 8, 40);
			assertDivergenceDoesNotGrowWithTheData(
				small.getHeapSizeInBytes(), small, borrowedRoots(),
				large.getHeapSizeInBytes(), large, borrowedRoots(),
				EXCLUSIONS
			);
		}

	}

	@Nested
	@DisplayName("hierarchy index")
	class HierarchyIndexes {

		/**
		 * The children index sits inside the lazily allocated node store, so the path crosses it. An index that never
		 * received a node has no store at all, and {@link IndexHeapSizeAssertions#excluded} then resolves the path to
		 * nothing — which is exactly right, because the walk finds nothing there either.
		 */
		private static final String[] EXCLUSIONS = {
			"nodeStore.levelIndex.transactionalLayerWrapper"
		};

		/**
		 * Seeds a two-level hierarchy of `roots` root nodes each carrying `children` children, plus one orphan whose
		 * parent is not indexed at all — the shape that keeps a node out of the level index and out of the memoized
		 * all-nodes formula.
		 *
		 * @param roots    how many root nodes to create
		 * @param children how many children each root carries
		 * @return the seeded index
		 */
		@Nonnull
		private static HierarchyIndex seededIndex(int roots, int children) {
			final HierarchyIndex index = new HierarchyIndex();
			for (int r = 0; r < roots; r++) {
				final int rootId = AUTOBOX_CACHE_CEILING + r + 1;
				index.addNode(rootId, null);
				for (int c = 0; c < children; c++) {
					index.addNode(rootId * 100 + c, rootId);
				}
			}
			index.addNode(AUTOBOX_CACHE_CEILING + 999_999, AUTOBOX_CACHE_CEILING + 888_888);
			return index;
		}

		@Test
		void shouldMeasureAnEmptyIndexExactly() {
			final HierarchyIndex index = new HierarchyIndex();
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, EXCLUSIONS);
		}

		@Test
		void shouldMeasureASeededIndexExactly() {
			final HierarchyIndex index = seededIndex(20, 10);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, EXCLUSIONS);
		}

		@Test
		void shouldStepUpOnceTheAllNodesBitmapIsMemoized() {
			// unlike a filter index's all-records memo, this one materializes a bitmap nothing else in the catalog
			// holds - so it must show up as occupancy AND be charged. Only the bitmap is retained: the formula
			// wrapping it is built fresh per call and dies with the query, so nothing prices formula scaffolding
			final HierarchyIndex index = seededIndex(20, 10);
			final long cold = index.getHeapSizeInBytes();
			assertMatchesMeasuredHeap(cold, index, EXCLUSIONS);

			index.getAllHierarchyNodesFormula();

			final long warm = index.getHeapSizeInBytes();
			assertTrue(warm > cold, "the memoized node bitmap must show up as additional occupancy");
			// What remains is a small SIGNED divergence rather than the old over-charge. Dropping the formula memo
			// removed the upper-bound scaffolding charge that used to sit on top, and doing so exposed a fixed
			// under-report of `BaseBitmap#getHeapSizeInBytes` against a reflective walk - about two words, present
			// before this change and merely masked by the over-charge. What matters for a memory report is that it
			// is a CONSTANT and not a term that grows, which the divergence assertion below pins
			final long divergence = warm - measuredHeapOf(index, EXCLUSIONS);
			assertTrue(
				Math.abs(divergence) < 128,
				"the bitmap charge must stay within a couple of words of the walk - was " + divergence
			);

			final HierarchyIndex larger = seededIndex(40, 20);
			larger.getAllHierarchyNodesFormula();
			assertDivergenceDoesNotGrowWithTheData(
				warm, index, larger.getHeapSizeInBytes(), larger, EXCLUSIONS
			);
		}

		@Test
		void shouldNotLetTheDivergenceGrowWithTheNodeCount() {
			final HierarchyIndex small = seededIndex(5, 5);
			final HierarchyIndex large = seededIndex(40, 20);
			assertDivergenceDoesNotGrowWithTheData(
				small.getHeapSizeInBytes(), small,
				large.getHeapSizeInBytes(), large,
				EXCLUSIONS
			);
		}

	}

}
