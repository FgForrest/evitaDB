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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.index.HistogramIndex.PersistedHistogramLeafPages;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.component.HistogramIndexMapComponent;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.index.IndexHeapSizeAssertions.AUTOBOX_CACHE_CEILING;
import static io.evitadb.index.IndexHeapSizeAssertions.assertExceedsMeasuredHeapBy;
import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.excluded;
import static io.evitadb.index.IndexHeapSizeAssertions.measuredHeapOf;
import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the heap arithmetic of the entity indexes, the histogram indexes beneath them, and the attribute
 * cardinality index they both build on, against what JOL actually finds on the heap.
 *
 * An entity index is the top of the tree - it owns an attribute, facet, hierarchy and price index, and the reduced
 * variants add cardinality and histogram maps on top. Two of its rulings are the ones worth pinning, because getting
 * either wrong doubles a figure rather than shifting it by a constant:
 *
 * - the `components` list holds **the very sub-indexes charged above it**, so only its spine is charged;
 * - the `original*` baselines hold storage keys pointing at an index key, attribute keys and schema strings that all
 *   belong to somebody else, so only the sets and the key records are charged.
 *
 * Depth makes an exact reading unattainable for a seeded index - see trap 4 in
 * `documentation/developer/heap-size-testing.md` - so those assertions pin **scale and slope** instead. An empty index
 * is asserted exactly, which is what actually pins the field arithmetic.
 *
 * That an empty index can be asserted **exactly at all** is itself a claim worth knowing. A `HashMap` keeps the
 * `keySet`/`values`/`entrySet` view it hands out, so an accessor asked for on a construction or flush path is sixteen
 * retained bytes on every index in the catalog - and one the arithmetic cannot see, since `MapHeapSize` would have to
 * call the accessor that creates it. An entity index used to carry fourteen such views before any caller touched it,
 * and seventeen once flushed, which is why these cases previously read low by a documented constant. Every walk on
 * those paths now goes through `forEach`, which reaches the same entries and leaves nothing behind; a walk added later
 * that asks for an accessor instead reappears here as a shortfall, and in
 * {@link EntityIndexes#shouldNotAccumulateCachedViewsOnFlush} if it is on the flush path only.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(MANAGEMENT)
@DisplayName("Entity index heap size")
class EntityIndexHeapSizeTest {

	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "brand";
	private static final String SUBSTRING_CODE = "substringCode";
	private static final String SUBSTRING_NAME = "substringName";
	private static final int INDEX_PK = 1;
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * Carries one attribute of each shape whose *normalized* key form is a class outside evitaDB's own data types -
	 * the three that took a real catalog's statistics request down before {@link IndexHeapSize#OWNED_KEY_SIZER} knew
	 * about them - plus a plain filterable one as the control.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
		.withAttribute("validFrom", OffsetDateTime.class, AttributeSchemaEditor::filterable)
		.withAttribute("currency", Currency.class, AttributeSchemaEditor::filterable)
		.withAttribute("language", Locale.class, AttributeSchemaEditor::filterable)
		// two SUBSTRING attributes, so a global index can be measured with a populated trigram map and again with one
		// entry taken out of it - the only fixture in this file that reaches the map's per-entry charge at all
		.withAttribute(SUBSTRING_CODE, String.class, thatIs -> thatIs.filterable(FilterIndexCapability.SUBSTRING))
		.withAttribute(SUBSTRING_NAME, String.class, thatIs -> thatIs.filterable(FilterIndexCapability.SUBSTRING))
		.toInstance();

	@Nonnull
	private static AttributeSchemaContract attribute(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	/**
	 * The scaffolding an owner filter index reaches but does not own, named through its nested paths (trap 4).
	 */
	private static final String[] HISTOGRAM_BASE_EXCLUSIONS = {
		"histogramName", "referenceName", "valueNormalizer"
	};

	private static final String[] OWNER_FILTER_EXCLUSIONS = {
		"normalizer", "comparator",
		"invertedIndex.normalizer", "invertedIndex.comparator", "invertedIndex.pageStreamRegistry",
		"invertedIndex.buckets.valueColumnFactory", "invertedIndex.buckets.recordColumnFactory"
	};

	@Nested
	@DisplayName("attribute cardinality index")
	class AttributeCardinalityIndexes {

		@Test
		@DisplayName("an empty one is measured exactly")
		void shouldMatchAnEmptyCardinalityIndex() {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index);
		}

		@Test
		@DisplayName("a seeded one sits above the measurement by its shared boxes and its string margin")
		void shouldMatchASeededCardinalityIndex() {
			final int entries = 64;
			final AttributeCardinalityIndex index = seed(entries);
			final VMLayout layout = VMLayout.current();
			// every tracked value has cardinality one, so all `entries` counters resolve to the JVM's own cached
			// `Integer.valueOf(1)`: the walk charges that box once and rule 1 charges it to each holder (trap 1)
			final long sharedBoxes = (entries - 1) * layout.sizeOfObject(Integer.BYTES);
			// `EvitaDataTypes.estimateSize` reads a String eight bytes above what it weighs, which is the
			// conservative direction rule 3 asks for
			final long stringMargin = entries * 8L;
			assertExceedsMeasuredHeapBy(
				index.getHeapSizeInBytes(), sharedBoxes + stringMargin, index
			);
		}

		@Test
		@DisplayName("the reported size grows with the values it tracks")
		void shouldGrowWithTheTrackedValues() {
			final long small = seed(64).getHeapSizeInBytes();
			final long large = seed(512).getHeapSizeInBytes();
			assertTrue(
				large > small * 4,
				"A cardinality index tracking eight times the values must report far more than the smaller one, " +
					"reported " + small + " vs " + large
			);
		}

		@Test
		@DisplayName("a map whose bins have treeified is under-reported, and by how much")
		void shouldUnderReportATreeifiedMap() {
			final int entries = 512;
			// the key record hashes as `31 * recordId + value.hashCode()`, so seeding the value equal to the record id
			// makes every hash a multiple of 32 and drops all 512 entries into 32 buckets - past eight per bucket
			// `HashMap` converts the bin to a red-black tree of 56-byte `TreeNode`s
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(Integer.class);
			for (int i = 0; i < entries; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.addRecord(recordId, recordId);
			}
			final VMLayout layout = VMLayout.current();
			final long sharedBoxes = (entries - 1) * layout.sizeOfObject(Integer.BYTES);
			final long shortfall = measuredHeapOf(index, "valueType") - index.getHeapSizeInBytes() + sharedBoxes;
			// a `TreeNode` carries six references and a flag beyond a `Node`, and neither the entry count nor anything
			// else `MapHeapSize` can read reveals that a bin converted - see its class javadoc
			final long treeNodeExcess = entries * (
				layout.sizeOfObject(Integer.BYTES + 1L + 9L * layout.referenceSize())
					- layout.sizeOfObject(Integer.BYTES + 3L * layout.referenceSize())
			);
			assertEquals(
				treeNodeExcess, shortfall,
				"The only thing a fully treeified map may read low by is the difference between a TreeNode and " +
					"a Node, once its shared counter boxes are added back - anything else is a defect"
			);
		}

		/**
		 * Seeds `count` distinct values, each above the boxed-`Integer` cache so no reading depends on
		 * `-XX:AutoBoxCacheMax`.
		 */
		@Nonnull
		private AttributeCardinalityIndex seed(int count) {
			final AttributeCardinalityIndex index = new AttributeCardinalityIndex(String.class);
			for (int i = 0; i < count; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.addRecord("value-" + recordId, recordId);
			}
			return index;
		}
	}

	@Nested
	@DisplayName("histogram index")
	class HistogramIndexes {

		@Test
		@DisplayName("an empty simple one is measured exactly")
		void shouldMatchAnEmptySimpleHistogram() {
			final SimpleHistogramIndex index = new SimpleHistogramIndex(
				"price", REFERENCE_NAME, Integer.class, 0
			);
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index,
				concatPaths(HISTOGRAM_BASE_EXCLUSIONS, prefixed("filterIndex.", OWNER_FILTER_EXCLUSIONS))
			);
		}

		@Test
		@DisplayName("a seeded simple one diverges only by its shared boxes, however many values it holds")
		void shouldKeepASeededSimpleHistogramBounded() {
			final long small = residualOfSimple(64);
			final long large = residualOfSimple(512);
			assertEquals(
				small, large,
				"Once the shared counter boxes are accounted for, what is left must not move with the value count - " +
					"a per-value term would be tens of kilobytes at eight times the data"
			);
			assertTrue(
				Math.abs(small) < 1_024,
				"A seeded histogram inherits the divergences of the filter and cardinality indexes beneath it; " +
					small + " bytes is outside the range those explain"
			);
		}

		/**
		 * Builds a simple histogram over `values` distinct values and returns what its arithmetic diverges by
		 * **beyond** the shared counter boxes rule 1 charges per holder — the term that must stay flat.
		 */
		private long residualOfSimple(int values) {
			final SimpleHistogramIndex index = new SimpleHistogramIndex(
				"price", REFERENCE_NAME, Integer.class, 0
			);
			for (int i = 0; i < values; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.insertValue(null, recordId, histogramValue(recordId));
			}
			// each value is seen once, so every counter in the cardinality index beneath is the JVM's own cached
			// `Integer.valueOf(1)`: the walk charges that box once and rule 1 charges it to each holder (trap 1)
			final long sharedBoxes = (values - 1) * VMLayout.current().sizeOfObject(Integer.BYTES);
			return index.getHeapSizeInBytes() - sharedBoxes - measuredHeapOf(
				index, new Object[0],
				concatPaths(HISTOGRAM_BASE_EXCLUSIONS, prefixed("filterIndex.", OWNER_FILTER_EXCLUSIONS))
			);
		}

		@Test
		@DisplayName("an empty localized one is measured exactly")
		void shouldMatchAnEmptyLocalizedHistogram() {
			final LocalizedHistogramIndex index = new LocalizedHistogramIndex(
				"price", REFERENCE_NAME, Integer.class, 0
			);
			assertMatchesMeasuredHeap(
				index.getHeapSizeInBytes(), index,
				concatPaths(HISTOGRAM_BASE_EXCLUSIONS, LOCALIZED_OWN_EXCLUSIONS)
			);
		}

		@Test
		@DisplayName("a seeded localized one charges every locale variant it holds")
		void shouldChargeEveryLocaleOfALocalizedHistogram() {
			final LocalizedHistogramIndex index = new LocalizedHistogramIndex(
				"price", REFERENCE_NAME, Integer.class, 0
			);
			for (int i = 0; i < 64; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.insertValue(Locale.ENGLISH, recordId, histogramValue(recordId));
				index.insertValue(Locale.GERMAN, recordId, histogramValue(recordId));
			}
			final List<Object> roots = new ArrayList<>(16);
			java.util.Collections.addAll(
				roots, excluded(index, concatPaths(HISTOGRAM_BASE_EXCLUSIONS, LOCALIZED_OWN_EXCLUSIONS))
			);
			for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
				final OwnerFilterIndex filterIndex = (OwnerFilterIndex) index.getFilterIndex(locale);
				java.util.Collections.addAll(roots, excluded(filterIndex, OWNER_FILTER_EXCLUSIONS));
				// the interned locale both maps key on, reached from inside two charged maps
				roots.add(locale);
			}
			// both variants track every value once, so all 128 counters are the same cached `Integer.valueOf(1)`
			final long sharedBoxes = (2 * 64 - 1) * VMLayout.current().sizeOfObject(Integer.BYTES);
			final long residual = index.getHeapSizeInBytes() - sharedBoxes
				- measuredHeapOf(index, roots.toArray(), new String[0]);
			assertTrue(
				Math.abs(residual) < 4_096,
				"Both locale variants must be charged - a locale left out would show as a shortfall in the tens of " +
					"kilobytes rather than the " + residual + " bytes the sub-indexes' own divergences explain"
			);
		}

		private static final String[] LOCALIZED_OWN_EXCLUSIONS = {
			"filterIndexes.transactionalLayerWrapper", "cardinalities.transactionalLayerWrapper"
		};

		/**
		 * Derives the indexed value of a record from its id, **without** letting the two coincide.
		 *
		 * A histogram feeds an {@link AttributeCardinalityIndex} keyed by the
		 * {@link AttributeCardinalityIndex.AttributeCardinalityKey} record, whose generated hash is
		 * `31 * recordId + value.hashCode()`. Seeding `value == recordId` collapses that to `32 * recordId` — five
		 * always-zero low bits, so every entry lands in one of thirty-two buckets no matter how wide the table grows,
		 * and every bin treeifies. A `TreeNode` weighs 56 bytes against a `Node`'s 32, which `MapHeapSize` cannot see
		 * and does not charge, and the resulting 24-bytes-per-entry shortfall reads exactly like a per-value defect
		 * in the arithmetic above it. The multiplier keeps the two apart so the slope assertion measures what it
		 * claims to. The blind spot itself is pinned by {@code shouldUnderReportATreeifiedMap}, not left to be
		 * rediscovered.
		 */
		private static int histogramValue(int recordId) {
			return 7 * recordId + 3;
		}

		/**
		 * Joins two exclusion path lists.
		 */
		@Nonnull
		private String[] concatPaths(@Nonnull String[] first, @Nonnull String... second) {
			final String[] result = new String[first.length + second.length];
			System.arraycopy(first, 0, result, 0, first.length);
			System.arraycopy(second, 0, result, first.length, second.length);
			return result;
		}

		/**
		 * Rewrites a sub-structure's own exclusion paths as paths from its owner (trap 4).
		 */
		@Nonnull
		private String[] prefixed(@Nonnull String prefix, @Nonnull String... paths) {
			final String[] result = new String[paths.length];
			for (int i = 0; i < paths.length; i++) {
				result[i] = prefix + paths[i];
			}
			return result;
		}
	}

	@Nested
	@DisplayName("normalized index keys")
	class NormalizedKeys {

		@Test
		@DisplayName("an OffsetDateTime attribute is priced through its Instant key form")
		void shouldPriceAnInstantKey() {
			assertPricesItsKeys(
				"validFrom",
				i -> OffsetDateTime.of(2026, 1, 1, 0, 0, i % 60, 0, ZoneOffset.UTC)
			);
		}

		@Test
		@DisplayName("a Currency attribute is priced through its ComparableCurrency key form")
		void shouldPriceAComparableCurrencyKey() {
			final Currency[] currencies = {
				Currency.getInstance("CZK"), Currency.getInstance("EUR"), Currency.getInstance("USD")
			};
			assertPricesItsKeys("currency", i -> currencies[i % currencies.length]);
		}

		@Test
		@DisplayName("a Locale attribute is priced through its ComparableLocale key form")
		void shouldPriceAComparableLocaleKey() {
			final Locale[] locales = {Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH};
			assertPricesItsKeys("language", i -> locales[i % locales.length]);
		}

		/**
		 * Indexes twenty values of the named attribute and asserts the index prices what it holds - a positive figure
		 * strictly above the same index's empty reading, rather than the exception these key forms used to raise.
		 *
		 * The comparison is against the empty index rather than against JOL, because what is under test is that the
		 * key form is *known* to the sizer; the byte-exact arithmetic of an owner filter index is pinned by
		 * `ContainerIndexHeapSizeTest`.
		 */
		private void assertPricesItsKeys(
			@Nonnull String attributeName,
			@Nonnull java.util.function.IntFunction<Serializable> valueFactory
		) {
			final GlobalEntityIndex empty = newGlobalIndex();
			final long emptySize = empty.getHeapSizeInBytes();

			final GlobalEntityIndex index = newGlobalIndex();
			for (int i = 0; i < 20; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.insertPrimaryKeyIfMissing(recordId);
				index.insertFilterAttribute(
					null, attribute(attributeName), ALLOWED_LOCALES, null, valueFactory.apply(i), recordId, false
				);
			}
			final long seededSize = index.getHeapSizeInBytes();
			assertTrue(
				seededSize > emptySize,
				"Indexing `" + attributeName + "` must be charged, empty was " + emptySize +
					" and seeded " + seededSize
			);
		}
	}

	@Nested
	@DisplayName("entity index")
	class EntityIndexes {

		/**
		 * The shell of every entity index: the entity type its sub-indexes share, and the transactional wrapper each
		 * map carries - a lambda the map does not own and JOL cannot walk into.
		 */
		/**
		 * How many distinct values each seeded histogram carries - large enough that its bucket axis splits and the
		 * on-disk baseline the flush captures actually holds page sequences.
		 */
		private static final int HISTOGRAM_VALUES = 512;

		private static final String[] SHARED_EXCLUSIONS = {
			// the collection's index map is keyed by this very instance, so the map owns it
			"indexKey",
			"entityIdsByLanguage.transactionalLayerWrapper",
			"attributeIndex.entityType",
			"attributeIndex.uniqueIndex.transactionalLayerWrapper",
			"attributeIndex.sortIndex.transactionalLayerWrapper",
			"attributeIndex.chainIndex.transactionalLayerWrapper",
			"attributeIndex.sharedValueIndex.transactionalLayerWrapper",
			"attributeIndex.sharedRangeIndex.transactionalLayerWrapper",
			"attributeIndex.filterIndex.transactionalLayerWrapper",
			"attributeIndex.uniqueViewIndex.transactionalLayerWrapper",
			"facetIndex.dirtyIndexes",
			"facetIndex.facetingEntities.transactionalLayerWrapper",
			"hierarchyIndex.itemIndex.transactionalLayerWrapper",
			"hierarchyIndex.levelIndex.transactionalLayerWrapper"
		};

		@Test
		@DisplayName("an empty global index is measured exactly")
		void shouldMatchAnEmptyGlobalIndex() {
			final GlobalEntityIndex index = newGlobalIndex();
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, globalExclusions());
		}

		@Test
		@DisplayName("a global index charges its trigram map for the index it holds, but not for a borrowed key")
		void shouldMatchAGlobalIndexCarryingTrigramIndexes() {
			// the trigram map's key sizer is the one term of this index's arithmetic that nothing else in the
			// repository executes: every other global index here declares no SUBSTRING attribute, so the map is
			// always empty and the per-entry charge is asserted nowhere. Two entries go in so that one can be taken
			// out again, which is what isolates the cost of a single entry from everything else the index carries.
			//
			// What is pinned is the ARITHMETIC of one entry, plus a JOL reading of the key it charges for - not a
			// byte-exact reading of the whole index, which is unattainable here for two independent reasons: a seeded
			// global index inherits its sub-indexes' documented divergences (see the bounded shortfall below), and two
			// trigram indexes in one map reach objects that each of them charges to itself, so a walk gives back less
			// than the arithmetic drops when one of them leaves
			final GlobalEntityIndex index = newGlobalIndex();
			for (int i = 0; i < 2; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.insertPrimaryKeyIfMissing(recordId);
				index.insertFilterAttribute(
					null, attribute(SUBSTRING_CODE), ALLOWED_LOCALES, null, "code-" + recordId, recordId, false);
				index.insertFilterAttribute(
					null, attribute(SUBSTRING_NAME), ALLOWED_LOCALES, null, "name-" + recordId, recordId, false);
			}
			final AttributeIndexKey droppedKey = new AttributeIndexKey(null, SUBSTRING_CODE, null);
			final TrigramIndex dropped = index.getTrigramIndex(droppedKey);
			assertNotNull(dropped, "the fixture must have built an accelerator to price");
			assertTrue(dropped.getTrigramCount() > 0, "and it must hold postings");

			// the key the trigram map is filed under is the very instance the attribute index files its shared value
			// tree under, and that map already charges it in full. This is what makes the zero charge below correct
			// rather than an omission: a key charged in both places would be reported twice in one figure, on every
			// attribute that declares the capability
			assertSame(
				readField(dropped, "attributeIndexKey"),
				readField(index.getFilterIndex(null, attribute(SUBSTRING_CODE), null), "attributeIndexKey"),
				"the attribute index and the trigram map hold one and the same key instance"
			);

			final VMLayout layout = VMLayout.current();
			final long reportedWithBoth = index.getHeapSizeInBytes();
			trigramMapOf(index).remove(droppedKey);
			final long reportedWithOne = index.getHeapSizeInBytes();

			assertEquals(
				layout.sizeOfObject(Integer.BYTES + 3L * layout.referenceSize())
					+ dropped.getHeapSizeInBytes(),
				reportedWithBoth - reportedWithOne,
				"one entry must cost one map node and the index it points at, and nothing for the borrowed key"
			);
		}

		/**
		 * @param index the global index whose trigram map is wanted
		 * @return the map itself, so a test can take an entry out of it without going through a schema mutation
		 */
		@Nonnull
		@SuppressWarnings("unchecked")
		private Map<AttributeIndexKey, TrigramIndex> trigramMapOf(@Nonnull GlobalEntityIndex index) {
			return (Map<AttributeIndexKey, TrigramIndex>) readField(index, "trigramIndex");
		}

		@Test
		@DisplayName("an empty reduced index is measured exactly")
		void shouldMatchAnEmptyReducedIndex() {
			final ReducedEntityIndex index = new ReducedEntityIndex(
				INDEX_PK, ENTITY_TYPE,
				// a per-referenced-entity index is discriminated by the reference key, never by a bare name
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, 1))
				)
			);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, reducedExclusions());
		}

		@Test
		@DisplayName("an empty reference-type index is measured exactly")
		void shouldMatchAnEmptyReferencedTypeIndex() {
			final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(
				INDEX_PK, ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, referencedTypeExclusions());
		}

		@Test
		@DisplayName("a flushed index carries no more cached map views than a fresh one")
		void shouldNotAccumulateCachedViewsOnFlush() {
			// the count below is a *fresh* index's, and the claim is that flushing adds none. A `HashMap` hands out its
			// `keySet`/`values`/`entrySet` view once and keeps it, so a flush path reaching a map through an accessor
			// the construction path did not use would quietly park one more permanent object on every index in the
			// catalog - and nothing else in this suite would see it, because every other case measures a fresh index
			final GlobalEntityIndex index = newGlobalIndex();
			index.getModifiedStorageParts(new TrappedChanges());
			index.resetDirty();
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, globalExclusions());
		}

		@Test
		@DisplayName("an empty reduced-group index is measured exactly")
		void shouldMatchAnEmptyReducedGroupIndex() {
			final ReducedGroupEntityIndex index = new ReducedGroupEntityIndex(
				INDEX_PK, ENTITY_TYPE,
				new EntityIndexKey(
					EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
					new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, 1))
				)
			);
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, reducedGroupExclusions());
		}

		@Test
		@DisplayName("the histogram component's on-disk leaf-page baseline is charged what it weighs")
		void shouldChargeTheHistogramLeafPageBaseline() {
			// this baseline is a field of the histogram-map COMPONENT rather than of the index, so no field of the
			// index points at it and the ruling that a wrapper costs its shell alone would report it as free. It is
			// populated only by a flush, and only an axis that actually paged contributes page sequences - so the
			// fixture has to split at least one leaf, or the one term of the arithmetic that scales goes untested
			final ReferencedTypeEntityIndex index = new ReferencedTypeEntityIndex(
				INDEX_PK, ENTITY_TYPE,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME)
			);
			for (int i = 0; i < HISTOGRAM_VALUES; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.insertPrimaryKeyIfMissing(recordId, recordId);
				index.insertHistogramValue("price", null, recordId, recordId, Integer.class, 0);
				index.insertHistogramValue("weight", Locale.ENGLISH, recordId, recordId, Integer.class, 0);
			}
			index.getModifiedStorageParts(new TrappedChanges());

			final Object component = readField(index, "histogramComponent");
			final Map<?, ?> baseline = (Map<?, ?>) readField(component, "persistedLeafPages");
			// `forEach`, never `values()`: asking a `HashMap` for a view allocates and CACHES it in the map, which
			// would grow the very baseline this test is about to measure and leave the walk sixteen bytes above the
			// arithmetic for a reason that has nothing to do with the arithmetic
			final int[] pagedAxes = new int[1];
			baseline.forEach(
				(key, pages) ->
					pagedAxes[0] += ((PersistedHistogramLeafPages) pages).bucketPageSequences().length > 0 ? 1 : 0
			);
			assertEquals(2, baseline.size(), "both seeded histograms must reach the baseline");
			assertTrue(
				pagedAxes[0] > 0,
				"the fixture must page at least one bucket axis, or the page sequences the baseline carries are " +
					"never exercised"
			);

			// isolate the baseline from every divergence a seeded reference-type index inherits: read the figure with
			// it and again with the field parked on the empty singleton. What the arithmetic drops must be what the
			// walk stops finding
			final long withBaseline = index.getHeapSizeInBytes();
			final long measuredWithBaseline = measuredHeapOf(index, referencedTypeExclusions());
			writeField(component, "persistedLeafPages", Map.of());
			final long withoutBaseline = index.getHeapSizeInBytes();
			final long measuredWithoutBaseline = measuredHeapOf(index, referencedTypeExclusions());

			assertTrue(withBaseline > withoutBaseline, "the leaf-page baseline must show up as occupancy");
			final long reportedDrop = withBaseline - withoutBaseline;
			final long measuredDrop = measuredWithBaseline - measuredWithoutBaseline;
			// the ONE thing the model cannot read is the bucket table: the baseline is pre-sized from the histogram
			// map's size, so `createHashMap(2)` asks for room for three and lands on a four-slot table, while the
			// reconstruction reports the larger of the shapes two entries could sit on and floors that at sixteen.
			// Everything else - the map object, its nodes, the minted keys, the page records and their sequences -
			// must agree to the byte, which is what makes a mis-sized record fail here instead of hiding in slack
			final VMLayout layout = VMLayout.current();
			final long tableOverReport = layout.sizeOfArray(16, layout.referenceSize())
				- layout.sizeOfArray(4, layout.referenceSize());
			assertEquals(
				tableOverReport, reportedDrop - measuredDrop,
				"the baseline must be charged what it weighs, over-reported by its bucket table alone - reported " +
					reportedDrop + " against measured " + measuredDrop
			);
		}

		@Test
		@DisplayName("a seeded index reports a shortfall that is bounded and does not grow with the data")
		void shouldKeepTheSeededDivergenceFlat() {
			final Shortfall small = shortfallOf(50);
			final Shortfall large = shortfallOf(400);
			assertTrue(
				small.shortfall() >= 0 && small.shortfall() < 4_096,
				"A seeded global index inherits its sub-indexes' documented divergences; " +
					small.shortfall() + " bytes is outside the range those explain"
			);
			assertTrue(
				Math.abs(large.shortfall() - small.shortfall()) < 1_024,
				"The shortfall must not move with the record count - a per-value term would be tens of kilobytes " +
					"at eight times the data, measured " + small.shortfall() + " then " + large.shortfall()
			);
			assertTrue(
				large.reported() > small.reported(),
				"Eight times the records must report more heap, " +
					small.reported() + " vs " + large.reported()
			);
		}

		/**
		 * Builds a global index carrying `recordCount` records with one filterable attribute each, and returns how
		 * far its own arithmetic sits below the JOL walk.
		 */
		@Nonnull
		private Shortfall shortfallOf(int recordCount) {
			final GlobalEntityIndex index = newGlobalIndex();
			for (int i = 0; i < recordCount; i++) {
				final int recordId = AUTOBOX_CACHE_CEILING + i;
				index.insertPrimaryKeyIfMissing(recordId);
				index.upsertLanguage(Locale.ENGLISH, recordId, SCHEMA);
				index.insertFilterAttribute(
					null, attribute("code"), ALLOWED_LOCALES, null, "code-" + recordId, recordId, false
				);
			}
			final List<Object> roots = new ArrayList<>(32);
			java.util.Collections.addAll(roots, excluded(index, globalExclusions()));
			final FilterIndex filterIndex = index.getFilterIndex(null, attribute("code"), null);
			java.util.Collections.addAll(roots, excluded(filterIndex, "normalizer", "comparator"));
			java.util.Collections.addAll(
				roots, excluded(index, "attributeIndex.sharedValueIndex.transactionalLayerWrapper")
			);
			// the interned locale the language map keys on, reached from inside a charged map
			roots.add(Locale.ENGLISH);
			final long measured = measuredHeapOf(index, roots.toArray(), new String[0]);
			final long reported = index.getHeapSizeInBytes();
			return new Shortfall(reported, measured - reported);
		}

		@Nonnull
		private String[] globalExclusions() {
			return concat(
				SHARED_EXCLUSIONS,
				"priceIndex.priceIndexes.transactionalLayerWrapper"
			);
		}

		@Nonnull
		private String[] reducedExclusions() {
			return concat(
				SHARED_EXCLUSIONS,
				"priceIndex.priceIndexes.transactionalLayerWrapper"
			);
		}

		@Nonnull
		private String[] reducedGroupExclusions() {
			return concat(
				SHARED_EXCLUSIONS,
				"priceIndex.priceIndexes.transactionalLayerWrapper",
				"cardinalityIndexes.transactionalLayerWrapper",
				"histogramIndexes.transactionalLayerWrapper",
				"referencedPrimaryKeysIndex.transactionalLayerWrapper"
			);
		}

		@Nonnull
		private String[] referencedTypeExclusions() {
			return concat(
				SHARED_EXCLUSIONS,
				"cardinalityIndexes.transactionalLayerWrapper",
				"histogramIndexes.transactionalLayerWrapper",
				"indexPrimaryKeyCardinality.pageStreamRegistry",
				"indexPrimaryKeyCardinality.cardinalities.valueColumnFactory",
				"indexPrimaryKeyCardinality.cardinalities.recordColumnFactory",
				"indexPrimaryKeyCardinality.referencedPrimaryKeysIndex.transactionalLayerWrapper"
			);
		}

		/**
		 * Parks the histogram component's leaf-page baseline on the empty singleton, so the figure can be read with
		 * and without it and the difference attributed to nothing else.
		 *
		 * @param component the histogram-map component to modify
		 * @param field     the baseline field to clear
		 * @param value     the value to write
		 */
		private static void writeField(@Nonnull Object component, @Nonnull String field, @Nonnull Object value) {
			try {
				final Field declaredField = HistogramIndexMapComponent.class.getDeclaredField(field);
				declaredField.setAccessible(true);
				declaredField.set(component, value);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				throw new IllegalStateException("Cannot clear the leaf-page baseline `" + field + "`.", e);
			}
		}

		/**
		 * Appends further exclusion paths to a shared list.
		 */
		@Nonnull
		private String[] concat(@Nonnull String[] base, @Nonnull String... extra) {
			final String[] result = new String[base.length + extra.length];
			System.arraycopy(base, 0, result, 0, base.length);
			System.arraycopy(extra, 0, result, base.length, extra.length);
			return result;
		}

		/**
		 * A seeded index's reported figure and how far the measurement exceeds it.
		 *
		 * @param reported  what the index says it occupies
		 * @param shortfall how much more JOL found
		 */
		private record Shortfall(long reported, long shortfall) {
		}
	}

	/**
	 * Builds an empty global index of the fixture's entity type.
	 */
	@Nonnull
	private static GlobalEntityIndex newGlobalIndex() {
		return new GlobalEntityIndex(
			INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
	}

}
