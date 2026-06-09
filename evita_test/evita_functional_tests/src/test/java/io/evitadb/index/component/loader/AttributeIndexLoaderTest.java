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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.attribute.UniqueIndexView;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.dataType.Scope;
import io.evitadb.index.component.loader.LoadedComponentBundle.AttributeIndexes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			EntitySchema._internalBuild(ENTITY_TYPE),
			ENTITY_INDEX_KEY,
			manifest,
			storage,
			null
		);
		final LoadedComponentBundle bundle = new AttributeIndexLoader().load(context);
		return assertInstanceOf(AttributeIndexes.class, bundle, "Loader must return an AttributeIndexes bundle");
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
			final Map<Serializable, Integer> valueToRecord = new HashMap<>(2);
			valueToRecord.put(value, recordId);
			final Bitmap recordIds = new BaseBitmap(recordId);
			seed(
				AttributeIndexType.UNIQUE, key,
				new UniqueIndexStoragePart(INDEX_PK, key, type, valueToRecord, recordIds)
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
		 * Builds the manifest advertising every seeded (and manifest-only) attribute key.
		 *
		 * @return a fresh manifest for the seeded storage
		 */
		@Nonnull
		EntityIndexStoragePart buildManifest() {
			return new EntityIndexStoragePart(
				INDEX_PK, 1, ENTITY_INDEX_KEY,
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
}
