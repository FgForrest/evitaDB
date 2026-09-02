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

package io.evitadb.index.component.loader;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.attribute.UniqueIndexView;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.component.loader.LoadedComponentBundle.AttributeIndexes;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.*;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real {@link AttributeIndexLoader#load(LoadContext)} two-pass reload algorithm against
 * an in-memory {@link StoragePartPersistenceService} fake, pinning the owner/view-split contract
 * that the loader reconstructs from persisted {@link StoragePart}s.
 *
 * Unlike `EntityIndexRoundTripTest` — which exercises the same behavior through a hand-copied
 * re-implementation of the two-pass algorithm operating on a captured list of parts — this test
 * invokes the production loader directly through the same `LoadContext` /
 * `StoragePartPersistenceService` / `computeUniquePartId` plumbing the engine uses on catalog boot.
 * That covers the loader's own code paths (the manifest-driven counting, the part-id computation
 * and lookup, the FIRST-pass-builds-views / SECOND-pass-consumes-views ordering) which the
 * round-trip mirror bypasses.
 *
 * The fake never touches disk, Kryo or a real catalog: storage parts are pre-seeded into a
 * `Map<Long, StoragePart>` keyed by the same `computeUniquePartId` the loader recomputes, and the
 * SAME {@link ReadWriteKeyCompressor} instance is shared between seeding and lookup so the
 * compressed key ids agree on both sides.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AttributeIndexLoader owner/view-split reload")
@Tag(INDEXING)
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class AttributeIndexLoaderTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final int INDEX_PK = 7;
	private static final long CATALOG_VERSION = 1L;
	/** Passed to the seeding helper when every persisted leaf page is to keep its value id column. */
	private static final int NO_STRIPPED_PAGE = -1;
	private static final EntityIndexKey ENTITY_INDEX_KEY =
		new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);

	@Test
	@DisplayName("should fold unique into a view when a shared FILTER tree exists for the same key")
	void shouldFoldUniqueIntoViewWhenSharedFilterTreeExistsForSameKey() {
		final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
		final SeededStorage storage = new SeededStorage();
		// FILTER part carries the CORRECT folded value "ABC" -> record 10 in the shared tree
		storage.seedFilter(key, String.class, "ABC", 10);
		// a LEGACY full UNIQUE part is intentionally seeded with a DELIBERATELY WRONG value map and
		// record bitmap; the loader must discard them (self-healing to slim) and build the view from
		// the shared filter tree instead
		storage.seedLegacyFullUnique(key, String.class, "WRONG", 999);

		final AttributeIndexes bundle = load(storage, key);

		// the entry must land in the VIEW map and NOT in the standalone owner map
		assertTrue(bundle.uniqueIndexes().isEmpty(), "No standalone owner unique must be created");
		final UniqueIndex view = bundle.uniqueViewIndexes().get(key);
		assertNotNull(view, "Foldable unique must be reconstructed as a view");
		assertInstanceOf(UniqueIndexView.class, view, "Folded unique must be a UniqueIndexView");
		// self-healing: the view answers from the shared filter tree, NOT from the legacy part's map
		assertEquals(
			Integer.valueOf(10), view.getRecordIdByUniqueValue("ABC"),
			"View must resolve the value from the shared filter tree"
		);
		assertNull(
			view.getRecordIdByUniqueValue("WRONG"),
			"The legacy full part's value-to-record map must be discarded on reload"
		);
		assertTrue(view.getRecordIds().contains(10), "View record ids come from the shared tree");
		assertFalse(view.getRecordIds().contains(999), "Legacy record-id bitmap must be discarded");
	}

	@Test
	@DisplayName("should build a standalone owner unique when no FILTER tree exists for the key")
	void shouldBuildOwnerUniqueWhenNoSharedFilterTreeForKey() {
		final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);
		final SeededStorage storage = new SeededStorage();
		// only a full UNIQUE part — no FILTER part for the key, so the unique is standalone (owner)
		storage.seedLegacyFullUnique(key, String.class, "ABC", 10);

		final AttributeIndexes bundle = load(storage, key);

		assertTrue(bundle.uniqueViewIndexes().isEmpty(), "No folded view without a shared tree");
		final UniqueIndex owner = bundle.uniqueIndexes().get(key);
		assertNotNull(owner, "Standalone unique must be reconstructed as an owner");
		assertInstanceOf(OwnerUniqueIndex.class, owner, "Standalone unique must be an OwnerUniqueIndex");
		// the owner restores its own value map / record bitmap from the full part
		assertEquals(
			Integer.valueOf(10), owner.getRecordIdByUniqueValue("ABC"),
			"Owner must resolve the value from its own persisted map"
		);
		assertTrue(owner.getRecordIds().contains(10), "Owner record ids come from its own bitmap");
	}

	@Test
	@DisplayName("should reload sort in view mode when a FILTER tree exists for the same key")
	void shouldRunSortInViewModeWhenFilterPartExistsForSameKey() {
		final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
		final SeededStorage storage = new SeededStorage();
		// FIRST pass builds the shared tree from this FILTER part; the SECOND-pass SORT must detect it
		storage.seedFilter(key, Integer.class, 42, 10);
		storage.seedSort(key, Integer.class, new int[]{10}, new Serializable[]{42});

		final AttributeIndexes bundle = load(storage, key);

		final SortIndex sort = bundle.sortIndexes().get(key);
		assertNotNull(sort, "Sort index must be reconstructed");
		assertInstanceOf(
			SortIndexView.class, sort,
			"Both-flagged sort must run in view mode bound to the shared tree"
		);
		// the view sources its value ordering from the shared tree built in the first pass
		assertTrue(sort.getRecordsEqualTo(42).contains(10), "View-mode sort resolves via the shared tree");
	}

	@Test
	@DisplayName("should reload sort as owner when no FILTER tree exists for the key")
	void shouldRunSortInOwnerModeWhenNoFilterPartForKey() {
		final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
		final SeededStorage storage = new SeededStorage();
		// no FILTER part for the key — the SORT must fall back to owner mode and rebuild from its arrays
		storage.seedSort(key, Integer.class, new int[]{10}, new Serializable[]{42});

		final AttributeIndexes bundle = load(storage, key);

		final SortIndex sort = bundle.sortIndexes().get(key);
		assertNotNull(sort, "Sort index must be reconstructed");
		assertInstanceOf(
			OwnerSortIndex.class, sort,
			"Sort without a shared tree must be a standalone OwnerSortIndex"
		);
		assertTrue(sort.getRecordsEqualTo(42).contains(10), "Owner-mode sort resolves from its own arrays");
		assertTrue(bundle.sharedValueIndexes().isEmpty(), "No shared tree must be built without a FILTER part");
	}

	@Test
	@DisplayName("should silently skip CARDINALITY keys and populate only the four attribute maps")
	void shouldIgnoreCardinalityKeysAndPopulateOnlyTheFourAttributeMaps() {
		final AttributeIndexKey filterKey = new AttributeIndexKey(null, "name", null);
		final AttributeIndexKey cardinalityKey = new AttributeIndexKey(null, "color", null);
		final SeededStorage storage = new SeededStorage();
		storage.seedFilter(filterKey, String.class, "Phone", 10);
		// a CARDINALITY key advertised in the manifest must be skipped here — it is owned by
		// AttributeCardinalityIndexMapLoader, so no part is seeded for it and the loader must not look
		// it up
		storage.manifestOnly(cardinalityKey, AttributeIndexType.CARDINALITY);

		final AttributeIndexes bundle = load(storage, filterKey);

		// the FILTER key populated the filter + shared maps; the CARDINALITY key contributed nothing
		assertEquals(1, bundle.filterIndexes().size(), "Only the FILTER key yields a filter index");
		assertNotNull(bundle.filterIndexes().get(filterKey), "Filter index for the FILTER key present");
		assertNull(bundle.filterIndexes().get(cardinalityKey), "CARDINALITY key must not create a filter index");
		assertTrue(bundle.uniqueIndexes().isEmpty(), "No unique entries expected");
		assertTrue(bundle.uniqueViewIndexes().isEmpty(), "No unique-view entries expected");
		assertTrue(bundle.sortIndexes().isEmpty(), "No sort entries expected");
		assertTrue(bundle.chainIndexes().isEmpty(), "No chain entries expected");
	}

	@Test
	@DisplayName("loads a granular PAGED filter part by reading its leaf pages and reconstructs the whole tree")
	void shouldLoadPagedFilterAndReconstructTree() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "name", null);
		final InvertedIndex source = new InvertedIndex(
			String.class, FilterIndex.getNormalizer(String.class, 0), FilterIndex.getComparator(key, String.class), 0
		);
		// more than one leaf block (256) of distinct values so the tree spans multiple leaves and is persisted PAGED
		for (int i = 0; i < 1_000; i++) {
			source.addRecord(String.format("value-%05d", i), i);
		}
		assertTrue(source.isPaged(), "the seeded index must be multi-leaf (PAGED)");
		final ValueToRecordBitmap[] expected = source.getValueToRecordBitmap();

		final SeededStorage storage = new SeededStorage();
		storage.seedPagedFilter(key, String.class, source);

		final AttributeIndexes bundle = load(storage, key);

		final InvertedIndex loaded = bundle.sharedValueIndexes().get(key);
		assertNotNull(loaded, "the PAGED filter part must rebuild a shared inverted index");
		final ValueToRecordBitmap[] actual = loaded.getValueToRecordBitmap();
		assertEquals(expected.length, actual.length, "the reloaded tree must hold every bucket");
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i].getValue(), actual[i].getValue(), "value @ " + i);
			assertArrayEquals(
				expected[i].getRecordIds().getArray(), actual[i].getRecordIds().getArray(), "record set @ " + i
			);
		}
	}

	@Test
	@DisplayName("loads a granular PAGED owner sort part by reading its leaf pages and reconstructs the owner tree")
	void shouldLoadPagedSortAndReconstructTree() {
		final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_PRIORITY, null);
		final OwnerSortIndex source = new OwnerSortIndex(String.class, key);
		// more than one leaf block (256) of distinct values so the owned tree spans multiple leaves and pages out
		for (int i = 0; i < 1_000; i++) {
			source.addRecord(String.format("value-%05d", i), i + 1);
		}

		final SeededStorage storage = new SeededStorage();
		// NO FILTER part for the key, so the SORT must reload in owner mode (not bound to a shared tree)
		final SortIndexStoragePart emittedRoot = storage.seedPagedSort(key, source);
		assertTrue(emittedRoot.isPaged(), "the seeded owner must span multiple leaves (PAGED)");

		final AttributeIndexes bundle = load(storage, key);

		final SortIndex loaded = bundle.sortIndexes().get(key);
		assertNotNull(loaded, "the PAGED sort part must rebuild a sort index");
		assertInstanceOf(
			OwnerSortIndex.class, loaded,
			"a PAGED sort without a shared FILTER tree must reload as a standalone OwnerSortIndex"
		);
		// the positional sortedRecords façade (not persisted for a PAGED owner) must be reconstructed byte-for-byte
		assertArrayEquals(
			source.getSortedRecords(), loaded.getSortedRecords(),
			"the reconstructed sortedRecords must equal the live source array"
		);
		assertArrayEquals(
			source.getSortedRecordValues(), loaded.getSortedRecordValues(),
			"the reconstructed ordered distinct values must equal the live source"
		);
		assertTrue(
			loaded.getRecordsEqualTo("value-00000").contains(1),
			"a representative value must resolve to its record through the reloaded owned tree"
		);
		assertTrue(bundle.sharedValueIndexes().isEmpty(), "no shared tree must be built without a FILTER part");
	}

	/**
	 * Invokes the production loader against the seeded storage, wrapping the seeded parts in a
	 * {@link LoadContext} whose manifest advertises exactly the seeded keys.
	 *
	 * @param storage the pre-seeded in-memory storage
	 * @param keys    the attribute index keys the manifest should advertise (in addition to any
	 *                manifest-only keys already registered on `storage`)
	 * @return the reconstructed attribute-index bundle
	 */
	@Nonnull
	private static AttributeIndexes load(@Nonnull SeededStorage storage, @Nonnull AttributeIndexKey... keys) {
		for (final AttributeIndexKey key : keys) {
			// manifest entries are derived from the seeded parts; explicit keys are a no-op when already
			// present, but make each test's intent self-documenting
			assertNotNull(key);
		}
		final EntityIndexStoragePart manifest = storage.buildManifest();
		final LoadContext context = new LoadContext(
			CATALOG_VERSION,
			INDEX_PK,
			buildSchema(manifest),
			ENTITY_INDEX_KEY,
			manifest,
			manifest.getVersion(),
			EmptyBitmap.INSTANCE,
			Map.of(),
			storage,
			null
		);
		final LoadedComponentBundle bundle = new AttributeIndexLoader().load(context);
		return assertInstanceOf(AttributeIndexes.class, bundle, "Loader must return an AttributeIndexes bundle");
	}

	/**
	 * Builds a real {@link EntitySchema} declaring every entity-level attribute name advertised by the manifest, so
	 * the loader's schema-based `indexedDecimalPlaces` resolution succeeds. The tests index no `BigDecimal` attribute,
	 * so a plain `String` attribute (scale `0`) is sufficient for each name.
	 *
	 * @param manifest the manifest whose attribute keys drive the schema's attribute set
	 * @return an entity schema declaring all those attributes
	 */
	@Nonnull
	private static EntitySchema buildSchema(@Nonnull EntityIndexStoragePart manifest) {
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
		);
		EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
			catalogSchema, EntitySchema._internalBuild(ENTITY_TYPE)
		);
		final Set<String> declared = new LinkedHashSet<>(8);
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			// only entity-level attributes appear in these tests; record each distinct name once
			final String attributeName = key.attribute().attributeName();
			if (key.attribute().referenceName() == null && declared.add(attributeName)) {
				builder = builder.withAttribute(
					attributeName, String.class, thatIs -> thatIs.filterable().sortable()
				);
			}
		}
		return (EntitySchema) builder.toInstance();
	}

	/**
	 * In-memory {@link StoragePartPersistenceService} fake. Only {@link #getStoragePart} and
	 * {@link #getReadOnlyKeyCompressor} are exercised by the loader; every other method throws
	 * {@link UnsupportedOperationException} so an accidental dependency on unimplemented behavior
	 * surfaces loudly rather than silently returning a default.
	 *
	 * The single shared {@link ReadWriteKeyCompressor} guarantees the seeded part ids agree with the
	 * ids the loader recomputes via `AttributeIndexStoragePart.computeUniquePartId`.
	 */
	private static final class SeededStorage implements StoragePartPersistenceService<StorageDescriptor> {

		/** Shared compressor — used both to seed part ids and to answer the loader's lookups. */
		@Nonnull private final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(new HashMap<>(16));
		/** Pre-seeded storage parts keyed by their computed unique part id. */
		@Nonnull private final Map<Long, StoragePart> partsById = new HashMap<>(16);
		/** Manifest keys advertised for the entity index, in seed order. */
		@Nonnull private final Set<AttributeIndexStorageKey> manifestKeys = new LinkedHashSet<>(16);

		/**
		 * Seeds a FILTER part holding one histogram point (`value` -> `recordId`) and registers its key
		 * in the manifest.
		 *
		 * @param key       the attribute key
		 * @param type      the attribute value type
		 * @param value     the indexed value
		 * @param recordId  the record bearing the value
		 */
		void seedFilter(
			@Nonnull AttributeIndexKey key, @Nonnull Class<?> type,
			@Nonnull Serializable value, int recordId
		) {
			final ValueToRecordBitmap[] points = {new ValueToRecordBitmap(value, recordId)};
			seed(
				AttributeIndexType.FILTER, key,
				new FilterIndexStoragePart(INDEX_PK, key, type, points, null)
			);
		}

		/**
		 * Seeds a FILTER part with an explicit frozen `indexedDecimalPlaces` scale, used to prove the loader reads the
		 * scale back from the part verbatim rather than re-deriving it from the schema.
		 *
		 * @param key                  the attribute key
		 * @param type                 the attribute value type
		 * @param value                the indexed value
		 * @param recordId             the record bearing the value
		 * @param indexedDecimalPlaces the scale to freeze into the part
		 */
		void seedFilter(
			@Nonnull AttributeIndexKey key, @Nonnull Class<?> type,
			@Nonnull Serializable value, int recordId, int indexedDecimalPlaces
		) {
			final ValueToRecordBitmap[] points = {new ValueToRecordBitmap(value, recordId)};
			seed(
				AttributeIndexType.FILTER, key,
				new FilterIndexStoragePart(INDEX_PK, key, type, points, null, indexedDecimalPlaces, null)
			);
		}

		/**
		 * Seeds a granular `PAGED` FILTER part: emits the source index's leaf pages, stores each as a
		 * {@link FilterIndexLeafPagePart} keyed by `pack(streamId, pageSequence)`, and stores the `PAGED` root carrying the
		 * high-water and the ordered leaf-page list. The stream id is resolved through the shared compressor exactly as
		 * the loader resolves it on read.
		 *
		 * @param key    the attribute key
		 * @param type   the attribute value type
		 * @param source a multi-leaf inverted index whose leaf pages are persisted
		 */
		void seedPagedFilter(@Nonnull AttributeIndexKey key, @Nonnull Class<?> type, @Nonnull InvertedIndex source) {
			seedPagedFilter(key, type, source, source.getNextValueId(), NO_STRIPPED_PAGE);
		}

		/**
		 * Seeds a granular `PAGED` FILTER part exactly as the three-argument sibling does, but lets the caller write a
		 * value id high-water mark into the root that differs from the source's, and persist one page WITHOUT its id
		 * column. Those are the two shapes a persisted generation can take when a run changed the tree's id-carrying
		 * mode and did not rewrite everything it had already written.
		 *
		 * @param key                 the attribute key
		 * @param type                the attribute value type
		 * @param source              a multi-leaf inverted index whose leaf pages are persisted
		 * @param nextValueId         the value id high-water mark to write into the root
		 * @param strippedPageOrdinal the position, in ascending key order, of the page to persist without its id
		 *                            column, or {@link #NO_STRIPPED_PAGE} to keep every column
		 */
		void seedPagedFilter(
			@Nonnull AttributeIndexKey key, @Nonnull Class<?> type, @Nonnull InvertedIndex source,
			int nextValueId, int strippedPageOrdinal
		) {
			final int streamId = this.keyCompressor.getId(
				new LeafStreamKey(INDEX_PK, new AttributeKeyWithIndexType(key, AttributeIndexType.FILTER))
			);
			final PageEmission<InvertedIndex.LeafPage> emission = source.collectChangedPages();
			final int[] orderedPageSequences = emission.orderedPageSequences();
			final int strippedPageSequence = strippedPageOrdinal == NO_STRIPPED_PAGE
				? NO_STRIPPED_PAGE : orderedPageSequences[strippedPageOrdinal];
			for (final InvertedIndex.LeafPage page : emission.changedPages()) {
				final long pagePk = AbstractLeafPagePart.computeUniquePartId(streamId, page.pageSequence());
				final int[] valueIds = page.pageSequence() == strippedPageSequence ? null : page.valueIds();
				this.partsById.put(
					pagePk,
					new FilterIndexLeafPagePart(streamId, page.pageSequence(), page.buckets(), valueIds, pagePk)
				);
			}
			seed(
				AttributeIndexType.FILTER, key,
				new FilterIndexStoragePart(
					INDEX_PK, key, type, new ValueToRecordBitmap[0], null, 0,
					true, emission.highWaterPageSequence(), orderedPageSequences,
					false, -1, new int[0],
					nextValueId, null, null
				)
			);
		}

		/**
		 * Seeds an inline (`SINGLE`) FILTER part carrying the source's buckets and their value id column — the shape a
		 * small id-carrying index is persisted in, where the whole tree rides the root rather than per-leaf pages.
		 *
		 * @param key    the attribute key
		 * @param type   the attribute value type
		 * @param source a single-leaf inverted index whose buckets ride the root
		 */
		void seedInlineFilter(@Nonnull AttributeIndexKey key, @Nonnull Class<?> type, @Nonnull InvertedIndex source) {
			seedInlineFilter(key, type, source, source.getNextValueId(), source.getValueIds());
		}

		/**
		 * Seeds an inline (`SINGLE`) FILTER part exactly as the three-argument sibling does, but lets the caller
		 * write a value id high-water mark and an inline id column that disagree with each other. Those are the two
		 * shapes an inline generation takes when a run changed its id-carrying mode and rewrote only one of the two.
		 *
		 * @param key            the attribute key
		 * @param type           the attribute value type
		 * @param source         a single-leaf inverted index whose buckets ride the root
		 * @param nextValueId    the value id high-water mark to write into the root
		 * @param inlineValueIds the inline id column to write into the root, or `null` to write none
		 */
		void seedInlineFilter(
			@Nonnull AttributeIndexKey key, @Nonnull Class<?> type, @Nonnull InvertedIndex source,
			int nextValueId, @Nullable int[] inlineValueIds
		) {
			seed(
				AttributeIndexType.FILTER, key,
				new FilterIndexStoragePart(
					INDEX_PK, key, type, source.getValueToRecordBitmap(), null, 0,
					false, -1, new int[0],
					false, -1, new int[0],
					nextValueId, inlineValueIds, null
				)
			);
		}

		/**
		 * Seeds a granular `PAGED` OWNER-mode SORT part: drains the owner index's commit emission, stores each emitted
		 * {@link SortIndexLeafPagePart} keyed by `pack(streamId, pageSequence)`, and seeds the `PAGED` root carrying the
		 * high-water and the ordered leaf-page list. The SORT stream id is resolved through the shared compressor exactly
		 * as the loader resolves it on read, keeping the SORT stream disjoint from the FILTER stream of the same attribute.
		 *
		 * @param key    the attribute key
		 * @param source a multi-leaf OWNER sort index whose leaf pages are persisted
		 * @return the emitted `PAGED` root part (so the caller can assert it is paged)
		 */
		@Nonnull
		SortIndexStoragePart seedPagedSort(@Nonnull AttributeIndexKey key, @Nonnull OwnerSortIndex source) {
			final int streamId = this.keyCompressor.getId(
				new LeafStreamKey(INDEX_PK, new AttributeKeyWithIndexType(key, AttributeIndexType.SORT))
			);
			final TrappedChanges trappedChanges = new TrappedChanges();
			source.appendStorageParts(INDEX_PK, trappedChanges);
			SortIndexStoragePart root = null;
			final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
			while (iterator.hasNext()) {
				final StoragePart part = iterator.next();
				if (part instanceof SortIndexLeafPagePart leafPage) {
					final long pagePk = AbstractLeafPagePart.computeUniquePartId(streamId, leafPage.getPageSequence());
					this.partsById.put(pagePk, leafPage);
				} else if (part instanceof SortIndexStoragePart sortRoot) {
					root = sortRoot;
				}
			}
			assertNotNull(root, "the owner emission must carry a SortIndexStoragePart root");
			seed(AttributeIndexType.SORT, key, root);
			return root;
		}

		/**
		 * Seeds a LEGACY full UNIQUE part carrying its own value-to-record map and record-id bitmap, and
		 * registers its key in the manifest.
		 *
		 * @param key       the attribute key
		 * @param type      the attribute value type
		 * @param value     the unique value the legacy map points at
		 * @param recordId  the record the legacy map points at
		 */
		void seedLegacyFullUnique(
			@Nonnull AttributeIndexKey key, @Nonnull Class<? extends Serializable> type,
			@Nonnull Serializable value, int recordId
		) {
			final Serializable[] values = {value};
			final int[] recordIds = {recordId};
			seed(
				AttributeIndexType.UNIQUE, key,
				new UniqueIndexStoragePart(INDEX_PK, key, type, values, recordIds)
			);
		}

		/**
		 * Seeds a SORT part with the given sorted records / values (no value cardinalities) and registers
		 * its key in the manifest.
		 *
		 * @param key                 the attribute key
		 * @param type                the attribute value type
		 * @param sortedRecords       record ids in sort order
		 * @param sortedRecordsValues values aligned with `sortedRecords`
		 */
		void seedSort(
			@Nonnull AttributeIndexKey key, @Nonnull Class<? extends Comparable<?>> type,
			@Nonnull int[] sortedRecords, @Nonnull Serializable[] sortedRecordsValues
		) {
			final ComparatorSource[] comparatorBase = {
				new ComparatorSource(type, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			seed(
				AttributeIndexType.SORT, key,
				new SortIndexStoragePart(
					INDEX_PK, key, comparatorBase, sortedRecords, sortedRecordsValues, new HashMap<>(0)
				)
			);
		}

		/**
		 * Seeds an owner-mode SORT part with an explicit frozen `indexedDecimalPlaces` scale, used to prove the loader
		 * reads the scale back from the part verbatim rather than re-deriving it from the schema.
		 *
		 * @param key                  the attribute key
		 * @param type                 the attribute value type
		 * @param sortedRecords        record ids in sort order
		 * @param sortedRecordsValues  values aligned with `sortedRecords`
		 * @param indexedDecimalPlaces the scale to freeze into the part
		 */
		void seedSort(
			@Nonnull AttributeIndexKey key, @Nonnull Class<? extends Comparable<?>> type,
			@Nonnull int[] sortedRecords, @Nonnull Serializable[] sortedRecordsValues, int indexedDecimalPlaces
		) {
			final ComparatorSource[] comparatorBase = {
				new ComparatorSource(type, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			seed(
				AttributeIndexType.SORT, key,
				new SortIndexStoragePart(
					INDEX_PK, key, comparatorBase, sortedRecords, sortedRecordsValues, new HashMap<>(0),
					indexedDecimalPlaces, null
				)
			);
		}

		/**
		 * Registers a manifest key WITHOUT seeding any backing part — used for the CARDINALITY key that
		 * the loader must skip and never look up.
		 *
		 * @param key  the attribute key
		 * @param type the index type to advertise
		 */
		void manifestOnly(@Nonnull AttributeIndexKey key, @Nonnull AttributeIndexType type) {
			this.manifestKeys.add(new AttributeIndexStorageKey(ENTITY_INDEX_KEY, type, key));
		}

		/**
		 * Stores a part under its computed unique part id and records its manifest key.
		 *
		 * @param type the index type of the part
		 * @param key  the attribute key of the part
		 * @param part the storage part to seed
		 */
		private void seed(
			@Nonnull AttributeIndexType type, @Nonnull AttributeIndexKey key, @Nonnull StoragePart part
		) {
			final long partId = AttributeIndexStoragePart.computeUniquePartId(
				INDEX_PK, type, key, this.keyCompressor
			);
			this.partsById.put(partId, part);
			this.manifestKeys.add(new AttributeIndexStorageKey(ENTITY_INDEX_KEY, type, key));
		}

		/**
		 * Builds the manifest advertising every seeded (and manifest-only) attribute key under the default
		 * {@link AttributeIndexLoaderTest#ENTITY_INDEX_KEY}.
		 *
		 * @return a fresh manifest for the seeded storage
		 */
		@Nonnull
		EntityIndexStoragePart buildManifest() {
			return buildManifest(ENTITY_INDEX_KEY);
		}

		/**
		 * Builds the manifest advertising every seeded (and manifest-only) attribute key under the given owning index
		 * key — the discriminator of which the loader uses to derive a reference attribute's scope.
		 *
		 * @param entityIndexKey the owning entity index key carried by the manifest
		 * @return a fresh manifest for the seeded storage
		 */
		@Nonnull
		EntityIndexStoragePart buildManifest(@Nonnull EntityIndexKey entityIndexKey) {
			return new EntityIndexStoragePart(
				INDEX_PK, 1, entityIndexKey,
				new BaseBitmap(), new HashMap<Locale, TransactionalBitmap>(0),
				this.manifestKeys, Set.of(), false, Set.of(), Set.of()
			);
		}

		@Nullable
		@Override
		@SuppressWarnings("unchecked")
		public <T extends StoragePart> T getStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			assertEquals(CATALOG_VERSION, catalogVersion, "Loader read at an unexpected catalog version");
			final StoragePart part = this.partsById.get(storagePartPk);
			if (part == null) {
				return null;
			}
			assertInstanceOf(containerType, part, "Seeded part type mismatch for id " + storagePartPk);
			return (T) part;
		}

		@Nonnull
		@Override
		public KeyCompressor getReadOnlyKeyCompressor() {
			return this.keyCompressor;
		}

		// --- the loader never calls anything below; fail loudly if that changes -------------------

		@Nonnull
		@Override
		public StoragePartPersistenceService<StorageDescriptor> createTransactionalService(@Nonnull UUID transactionId) {
			throw new UnsupportedOperationException();
		}

		@Nullable
		@Override
		public <T extends StoragePart> byte[] getStoragePartAsBinary(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean removeStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean containsStoragePart(
			long catalogVersion, long primaryKey, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int countStorageParts(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> T deserializeStoragePart(
			@Nonnull byte[] storagePart, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public KeyCompressorSnapshot getKeyCompressorSnapshot() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getVersion() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void forgetVolatileData() {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor flush(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor copySnapshotTo(
			long catalogVersion, @Nonnull OutputStream outputStream,
			@Nullable IntConsumer progressConsumer, @Nullable StoragePart... updatedStorageParts
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isNew() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isClosed() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Pins the freeze contract: the `indexedDecimalPlaces` scale a `BigDecimal` index was created with is persisted
	 * into the storage part and read back by the loader **verbatim** — the schema is NOT consulted at load. This is
	 * deliberately stronger than a schema lookup: each test seeds a part whose frozen scale DIFFERS from the scale the
	 * schema declares for the same attribute and asserts the loaded index keeps the part's scale, proving a later
	 * schema change to `indexedDecimalPlaces` cannot silently reinterpret the on-disk scaled keys (such drift is caught
	 * on the next modification by {@code FilterIndex.assertIndexedDecimalPlacesUnchanged}, not here).
	 */
	@Nested
	@DisplayName("frozen indexedDecimalPlaces read back from the part")
	class FrozenScaleReadback {

		private static final String REFERENCE_NAME = "stocks";
		private static final String REFERENCE_ATTRIBUTE = "quantityOnStock";
		/** An ENTITY-level BigDecimal attribute (`rating`) whose value copy is held by the reference index. */
		private static final String ENTITY_ATTRIBUTE = "rating";
		private static final EntityIndexKey REFERENCED_TYPE_INDEX_KEY =
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME);

		@Test
		@DisplayName("should read the frozen FILTER scale from the part, not from the schema")
		void shouldReadFrozenFilterScaleFromPartVerbatim() {
			// the legacy persisted key has a NULL reference name (the bridge cannot recover it from the old AttributeKey)
			final AttributeIndexKey legacyKey = new AttributeIndexKey(null, REFERENCE_ATTRIBUTE, null);
			final SeededStorage storage = new SeededStorage();
			// freeze a scale (5) that DIFFERS from the schema's declared scale for `quantityOnStock` (2); 150000 == 1.50000
			storage.seedFilter(legacyKey, BigDecimal.class, 150000, 10, 5);

			final AttributeIndexes bundle = loadReferenced(storage, legacyKey);

			final FilterIndex filterIndex = bundle.filterIndexes().get(legacyKey);
			assertNotNull(filterIndex, "Filter index for the reference attribute must be reconstructed");
			// the loader must read the FROZEN scale from the part (5), never the schema's current declaration (2)
			assertEquals(
				5, filterIndex.getIndexedDecimalPlaces(),
				"Scale must be read back from the part verbatim, ignoring the schema's current declaration"
			);
		}

		@Test
		@DisplayName("should read the frozen SORT scale from the part, not from the schema")
		void shouldReadFrozenSortScaleFromPartVerbatim() {
			// `rating` is declared at entity level with indexDecimalPlaces(3); freeze a different scale (7) into the part
			final AttributeIndexKey legacyKey = new AttributeIndexKey(null, ENTITY_ATTRIBUTE, null);
			final SeededStorage storage = new SeededStorage();
			// owner-mode sort part (no FILTER part for the key) carrying a single scaled value at the frozen scale 7
			storage.seedSort(legacyKey, BigDecimal.class, new int[]{11}, new Serializable[]{42}, 7);

			final AttributeIndexes bundle = loadReferenced(storage, legacyKey);

			final SortIndex sortIndex = bundle.sortIndexes().get(legacyKey);
			assertNotNull(sortIndex, "Sort index for the attribute must be reconstructed");
			// the loader must read the FROZEN scale from the part (7), never the schema's current declaration (3)
			assertEquals(
				7, sortIndex.getIndexedDecimalPlaces(),
				"Scale must be read back from the part verbatim, ignoring the schema's current declaration"
			);
		}

		/**
		 * Invokes the production loader against a `REFERENCED_ENTITY_TYPE` index whose manifest advertises exactly the
		 * seeded keys, with a schema declaring the `stocks` reference carrying a scaled `BigDecimal` `quantityOnStock`.
		 * The schema's scales are deliberately different from the frozen part scales to prove the loader ignores them.
		 *
		 * @param storage the pre-seeded in-memory storage
		 * @param keys    the attribute index keys the manifest should advertise
		 * @return the reconstructed attribute-index bundle
		 */
		@Nonnull
		private static AttributeIndexes loadReferenced(
			@Nonnull SeededStorage storage, @Nonnull AttributeIndexKey... keys
		) {
			for (final AttributeIndexKey key : keys) {
				assertNotNull(key);
			}
			final EntityIndexStoragePart manifest = storage.buildManifest(REFERENCED_TYPE_INDEX_KEY);
			final LoadContext context = new LoadContext(
				CATALOG_VERSION,
				INDEX_PK,
				buildReferenceSchema(),
				REFERENCED_TYPE_INDEX_KEY,
				manifest,
				manifest.getVersion(),
				EmptyBitmap.INSTANCE,
				Map.of(),
				storage,
				// REFERENCED_ENTITY_TYPE indexes carry a String discriminator and a `null` referenceKey, exactly as the
				// engine's DefaultEntityCollectionPersistenceService builds the context
				null
			);
			final LoadedComponentBundle bundle = new AttributeIndexLoader().load(context);
			return assertInstanceOf(AttributeIndexes.class, bundle, "Loader must return an AttributeIndexes bundle");
		}

		/**
		 * Builds a real {@link EntitySchema} declaring two distinct scaled `BigDecimal` attributes:
		 *
		 * - `quantityOnStock` at `indexDecimalPlaces(2)` on the `stocks` reference (a reference attribute), and
		 * - `rating` at `indexDecimalPlaces(3)` at the entity level (an entity attribute whose value copy a reference
		 *   index also holds).
		 *
		 * The two scales differ on purpose so a test asserting the resolved scale proves WHICH schema the resolver read.
		 *
		 * @return the entity schema with one reference-scoped and one entity-level scaled BigDecimal attribute
		 */
		@Nonnull
		private static EntitySchema buildReferenceSchema() {
			final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
				APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
				null,
				EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
			);
			final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(
				catalogSchema, EntitySchema._internalBuild(ENTITY_TYPE)
			).withAttribute(
				ENTITY_ATTRIBUTE, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(3)
			).withReferenceTo(
				REFERENCE_NAME, "Stock", Cardinality.ZERO_OR_MORE,
				thatIs -> thatIs.indexed().withAttribute(
					REFERENCE_ATTRIBUTE, BigDecimal.class,
					whichIs -> whichIs.filterable().indexDecimalPlaces(2)
				)
			);
			return (EntitySchema) builder.toInstance();
		}
	}

	/**
	 * Pins the loader's refusal to reload a filter index whose persisted generation disagrees with itself about value
	 * ids. The id column is written for every leaf page of a generation or for none of them, and the root's high-water
	 * mark records which of the two it is. A generation that breaks either rule was written by a run that changed the
	 * tree's id-carrying mode without rewriting what it had already written — and loading it would mint replacement ids
	 * over the pages that lost theirs, handing already-published ids to different values while the tree looks perfectly
	 * healthy. Every one of these tests therefore asserts that the load THROWS: an assertion on the resulting ids would
	 * pass vacuously against a tree that silently re-minted.
	 */
	@Nested
	@DisplayName("value ids are all-or-nothing across a persisted generation")
	class ValueIdConsistencyOnLoad {

		private static final String ATTRIBUTE_NAME = "name";
		/** Distinct values seeded into each paged source — more than one leaf block (256), so the tree pages out. */
		private static final int VALUE_COUNT = 1_000;
		/** Distinct values seeded into an inline source — comfortably inside one leaf block, so it stays `SINGLE`. */
		private static final int INLINE_VALUE_COUNT = 20;
		/** Names the registration that switches the id column on; only its presence matters, never its value. */
		private static final String ID_CONSUMER = "attribute-index-loader-test";

		@Test
		@DisplayName("should refuse a generation whose leaf pages disagree about carrying value ids")
		void shouldRejectMixedValueIdColumnsAcrossLeafPages() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = pagedSourceWithValueIds(key);
			final SeededStorage storage = new SeededStorage();
			// the first page loses its id column while the rest keep theirs - what a run leaves behind when it switches
			// the ids on or off and rewrites only the pages it happened to dirty
			storage.seedPagedFilter(key, String.class, source, source.getNextValueId(), 0);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> load(storage, key)
			);
			assertTrue(
				error.getMessage().contains("disagree about value ids"),
				"the refusal must name the disagreement, but was: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should refuse a root claiming value ids its leaf pages do not carry")
		void shouldRejectRootClaimingValueIdsItsPagesDoNotCarry() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = pagedSourceWithoutValueIds(key);
			final SeededStorage storage = new SeededStorage();
			// no page carries an id column, yet the root records a high-water mark - the shape a run leaves behind when
			// it drops the ids and the pages that held them are never rewritten
			storage.seedPagedFilter(key, String.class, source, 500, NO_STRIPPED_PAGE);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> load(storage, key)
			);
			assertTrue(
				error.getMessage().contains("do not carry"),
				"the refusal must say which side carries the ids, but was: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should refuse leaf pages carrying value ids their root does not claim")
		void shouldRejectPagesCarryingValueIdsTheRootDoesNotClaim() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = pagedSourceWithValueIds(key);
			final SeededStorage storage = new SeededStorage();
			// every page carries its id column while the root says the tree has none - reloading this would restart the
			// allocator at the unassigned sentinel and hand ids already written into the pages out a second time
			storage.seedPagedFilter(key, String.class, source, ValueIdAllocator.UNASSIGNED_VALUE_ID, NO_STRIPPED_PAGE);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> load(storage, key)
			);
			// asserted on the text because the allocator itself refuses a zero high-water further down the load path:
			// without this check the reload still fails, but with a message naming neither the attribute nor the cause
			assertTrue(
				error.getMessage().contains(ATTRIBUTE_NAME) && error.getMessage().contains("disagree about value ids"),
				"the refusal must name the attribute and the disagreement, but was: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should reload a consistent id-carrying generation with every id attached to its own value")
		void shouldLoadConsistentValueIdCarryingGeneration() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = pagedSourceWithValueIds(key);
			final SeededStorage storage = new SeededStorage();
			storage.seedPagedFilter(key, String.class, source);

			final AttributeIndexes bundle = load(storage, key);

			final InvertedIndex loaded = bundle.sharedValueIndexes().get(key);
			assertNotNull(loaded, "the PAGED filter part must rebuild a shared inverted index");
			assertTrue(loaded.carriesValueIds(), "an id-carrying generation must reload id-carrying");
			assertEquals(source.getNextValueId(), loaded.getNextValueId(), "the high-water mark must continue");
			for (int i = 0; i < VALUE_COUNT; i++) {
				final String value = String.format("value-%05d", i);
				assertEquals(
					source.getValueId(value), loaded.getValueId(value),
					"value " + value + " did not come back with the id it was persisted under"
				);
			}
		}

		@Test
		@DisplayName("should reload an inline generation with its id column stamped back in key order")
		void shouldLoadInlineValueIdCarryingGeneration() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = inlineSourceWithValueIds(key);
			final SeededStorage storage = new SeededStorage();
			storage.seedInlineFilter(key, String.class, source);

			final AttributeIndexes bundle = load(storage, key);

			final InvertedIndex loaded = bundle.sharedValueIndexes().get(key);
			assertNotNull(loaded, "the SINGLE filter part must rebuild a shared inverted index");
			assertTrue(loaded.carriesValueIds(), "an id-carrying generation must reload id-carrying");
			assertEquals(source.getNextValueId(), loaded.getNextValueId(), "the high-water mark must continue");
			for (int i = 0; i < INLINE_VALUE_COUNT; i++) {
				final String value = String.format("value-%05d", i);
				assertEquals(
					source.getValueId(value), loaded.getValueId(value),
					"value " + value + " did not come back with the id it was persisted under"
				);
			}
		}

		@Test
		@DisplayName("should refuse an inline root claiming value ids its own column does not carry")
		void shouldRejectInlineRootClaimingValueIdsItsColumnDoesNotCarry() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = inlineSourceWithValueIds(key);
			final SeededStorage storage = new SeededStorage();
			// the root records a high-water mark while its inline column is gone - the shape a run leaves behind when
			// it drops the ids of a SINGLE index and rewrites the buckets without the mark
			storage.seedInlineFilter(key, String.class, source, 500, null);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> load(storage, key)
			);
			assertTrue(
				error.getMessage().contains(ATTRIBUTE_NAME) && error.getMessage().contains("disagree about value ids"),
				"the refusal must name the attribute and the disagreement, but was: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should refuse an inline id column under a root claiming no value ids")
		void shouldRejectInlineColumnUnderARootClaimingNoValueIds() {
			final AttributeIndexKey key = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);
			final InvertedIndex source = inlineSourceWithValueIds(key);
			final SeededStorage storage = new SeededStorage();
			// the converse: the buckets keep their id column while the root says the tree has none, so a reload would
			// restart the allocator at the unassigned sentinel and hand the persisted ids out a second time
			storage.seedInlineFilter(
				key, String.class, source, ValueIdAllocator.UNASSIGNED_VALUE_ID, source.getValueIds()
			);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> load(storage, key)
			);
			// asserted on the text because the allocator itself refuses a zero high-water further down the load path:
			// without this check the reload still fails, but with a message naming neither the attribute nor the cause
			assertTrue(
				error.getMessage().contains(ATTRIBUTE_NAME) && error.getMessage().contains("disagree about value ids"),
				"the refusal must name the attribute and the disagreement, but was: " + error.getMessage()
			);
		}

		/**
		 * Builds a multi-leaf inverted index that carries value ids, by registering a consumer on it while it is still
		 * empty (the only moment a tree may be switched on) and filling it afterwards.
		 *
		 * @param key the attribute key whose comparator and normalizer the index is built with
		 * @return the populated, id-carrying, multi-leaf index
		 */
		@Nonnull
		private static InvertedIndex pagedSourceWithValueIds(@Nonnull AttributeIndexKey key) {
			final InvertedIndex source = newSource(key);
			source.attachValueIdConsumer(ID_CONSUMER);
			fill(source);
			return source;
		}

		/**
		 * Builds a single-leaf inverted index that carries value ids, filled in DESCENDING key order.
		 *
		 * The order is the whole point: the inline shape replays its buckets through the ordinary insert path and has
		 * its persisted column stamped back positionally, so a fixture whose ids happen to be minted in the same order
		 * the column is written in cannot fail however the stamp-back is ordered.
		 *
		 * @param key the attribute key whose comparator and normalizer the index is built with
		 * @return the populated, id-carrying, single-leaf index
		 */
		@Nonnull
		private static InvertedIndex inlineSourceWithValueIds(@Nonnull AttributeIndexKey key) {
			final InvertedIndex source = newSource(key);
			source.attachValueIdConsumer(ID_CONSUMER);
			for (int i = INLINE_VALUE_COUNT - 1; i >= 0; i--) {
				source.addRecord(String.format("value-%05d", i), i + 1);
			}
			assertFalse(source.isPaged(), "the seeded index must stay inside a single leaf (SINGLE)");
			return source;
		}

		/**
		 * Builds a multi-leaf inverted index that carries no value ids at all — the shape of every tree no subsystem
		 * has registered as a consumer of.
		 *
		 * @param key the attribute key whose comparator and normalizer the index is built with
		 * @return the populated, id-less, multi-leaf index
		 */
		@Nonnull
		private static InvertedIndex pagedSourceWithoutValueIds(@Nonnull AttributeIndexKey key) {
			final InvertedIndex source = newSource(key);
			fill(source);
			return source;
		}

		/**
		 * @param key the attribute key whose comparator and normalizer the index is built with
		 * @return a fresh empty `String` inverted index
		 */
		@Nonnull
		private static InvertedIndex newSource(@Nonnull AttributeIndexKey key) {
			return new InvertedIndex(
				String.class, FilterIndex.getNormalizer(String.class, 0),
				FilterIndex.getComparator(key, String.class), 0
			);
		}

		/**
		 * Inserts {@link #VALUE_COUNT} distinct values, one record each, so the tree spans several leaves and is
		 * persisted in the `PAGED` shape.
		 *
		 * @param source the index to fill
		 */
		private static void fill(@Nonnull InvertedIndex source) {
			for (int i = 0; i < VALUE_COUNT; i++) {
				source.addRecord(String.format("value-%05d", i), i);
			}
			assertTrue(source.isPaged(), "the seeded index must be multi-leaf (PAGED)");
		}
	}
}
