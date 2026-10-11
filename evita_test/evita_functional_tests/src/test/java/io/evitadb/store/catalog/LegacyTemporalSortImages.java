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

package io.evitadb.store.catalog;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.LegacyOwnerSortIndexWriter;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Writes into a catalog on disk, with the engine stopped, the owner sort indexes a release up to 2026.2 wrote - every
 * temporal value stored as it was written, not in its millisecond form - and reads back what the storage migration
 * to protocol 8 made of them. The legacy parts are built by {@link LegacyOwnerSortIndexWriter}, a replica of the old
 * writer, over the records the current engine indexed: no binary fixture is involved.
 *
 * Each sort index replaced is remembered by its identity - the entity index holding it, the primary key of its root
 * and the primary keys of its leaf pages - so a test can tell, through storage reads, whether a legacy part survived
 * the migration, rather than only whether the catalog still counts the right number of parts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class LegacyTemporalSortImages {

	private LegacyTemporalSortImages() {
	}

	/**
	 * Replaces every owner sort index of the named attributes, in every entity index the collection lists, with the
	 * parts a release storing raw temporal values wrote for the same records - inline when the replaced index is
	 * inline, as leaf pages and a paged root when it is paged - and publishes the result under the current storage
	 * protocol, through the same flush-and-re-address sequence a migration publishes a rewritten collection with. A
	 * sort index with a filter index under the same key is a view and is left alone, as the load path ignores its
	 * values. The leaf pages of a replaced paged index that no legacy page overwrites are removed, so the image holds
	 * the legacy pages only.
	 *
	 * @param configuration    the configuration of the engine
	 * @param catalogName      the catalog
	 * @param entityType       the collection
	 * @param attributeNames   the attributes whose owner sort indexes are replaced
	 * @param rawValueOfRecord the raw value the old release stored for a record
	 * @return the identities of the legacy sort indexes written
	 */
	@Nonnull
	static List<LegacySortIndex> writeRawSortIndexes(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull Set<String> attributeNames,
		@Nonnull IntFunction<Serializable> rawValueOfRecord
	) {
		final int catalogFileIndex = lastBootstrap(configuration, catalogName).catalogFileIndex();
		return withOfflineCatalog(
			configuration, catalogName,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				final CollectionFileReference reference = Objects.requireNonNull(
					header.collectionFileIndex().get(entityType), "the catalog has no collection `" + entityType + "`"
				);
				final DefaultEntityCollectionPersistenceService collectionService =
					service.getOrCreateEntityCollectionPersistenceService(
						catalogVersion, entityType, reference.entityTypePrimaryKey()
					);
				final OffsetIndexStoragePartPersistenceService storage =
					collectionService.getStoragePartPersistenceService();
				final List<LegacySortIndex> written = new ArrayList<>(8);
				for (final Integer indexPrimaryKey : listedIndexes(collectionService.getEntityCollectionHeader())) {
					final EntityIndexStoragePart manifest = Objects.requireNonNull(
						storage.getStoragePart(catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class)
					);
					for (final AttributeIndexKey attributeKey : ownerSortKeys(manifest, attributeNames)) {
						written.add(
							replaceWithRawSortIndex(
								catalogVersion, storage, manifest, indexPrimaryKey, attributeKey, rawValueOfRecord
							)
						);
					}
				}
				assertFalse(written.isEmpty(), "the collection `" + entityType + "` holds no sort index to replace");
				final OffsetIndexDescriptor descriptor = collectionService.flush(
					catalogVersion, new NoChangeHeaderInfoSupplier(collectionService.getEntityCollectionHeader())
				);
				catalogParts.putStoragePart(catalogVersion, collectionService.getEntityCollectionHeader());
				final Map<String, CollectionFileReference> collectionFileIndex =
					new HashMap<>(header.collectionFileIndex());
				collectionFileIndex.put(
					entityType,
					new CollectionFileReference(
						entityType, reference.entityTypePrimaryKey(), reference.fileIndex(), descriptor.fileLocation()
					)
				);
				catalogParts.writeCatalogHeader(
					PersistenceService.STORAGE_PROTOCOL_VERSION, catalogVersion, service.getCatalogStoragePath(),
					header.walFileReference(), collectionFileIndex, header.catalogId(), header.catalogName(),
					header.catalogState(), header.lastEntityCollectionPrimaryKey()
				);
				service.recordBootstrap(catalogVersion, catalogName, catalogFileIndex, null);
				return written;
			}
		);
	}

	/**
	 * Asserts that the published state of the catalog still holds every legacy sort index exactly as it was written:
	 * its root with raw values, and every leaf page it listed.
	 *
	 * @param label          names the moment in failure messages
	 * @param configuration  the configuration of the engine
	 * @param catalogName    the catalog
	 * @param entityType     the collection
	 * @param legacyIndexes  the legacy sort indexes written
	 */
	static void assertStillRaw(
		@Nonnull String label,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull List<LegacySortIndex> legacyIndexes
	) {
		withOfflineCatalog(
			configuration, catalogName,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final OffsetIndexStoragePartPersistenceService storage =
					collectionServiceOf(service, catalogVersion, entityType).getStoragePartPersistenceService();
				for (final LegacySortIndex legacy : legacyIndexes) {
					final SortIndexStoragePart root = Objects.requireNonNull(
						storage.getStoragePart(catalogVersion, legacy.rootPrimaryKey(), SortIndexStoragePart.class),
						label + ": the legacy root of " + legacy + " must be stored"
					);
					assertArrayEquals(
						legacy.pageSequences(),
						root.isPaged() ? root.getLeafPageSequences() : ArrayUtils.EMPTY_INT_ARRAY,
						label + ": the root of " + legacy + " must list the legacy pages"
					);
					for (final long pagePrimaryKey : legacy.pagePrimaryKeys()) {
						assertTrue(
							storage.containsStoragePart(catalogVersion, pagePrimaryKey, SortIndexLeafPagePart.class),
							label + ": the legacy leaf page " + pagePrimaryKey + " of " + legacy + " must be stored"
						);
					}
					final List<Serializable> values = readValues(catalogVersion, storage, legacy, root);
					assertTrue(
						values.stream().anyMatch(value -> !isNormalized(root.getComparatorBase(), value)),
						label + ": " + legacy + " must still hold raw values: " + values
					);
				}
				return Boolean.TRUE;
			}
		);
	}

	/**
	 * Asserts what the published state of the catalog holds of every legacy sort index after the migration. The sort
	 * index of an entity index the collection still lists is stored in normalized form, and equals the owner sort index
	 * the current engine builds from the same raw values - record order and values. Its root is stored anew, and none
	 * of its legacy leaf pages is stored any more: every leaf page its root lists lies above the legacy high-water. The
	 * sort index of an entity index the collection no longer lists - a partition converted into a row - stores neither
	 * its root nor any legacy leaf page.
	 *
	 * The leaf pages are checked by their identities, through storage reads: a count of parts would not tell a legacy
	 * page left behind from a page of the rewritten index. For the same reason no page the rewritten root lists may
	 * carry the identity of a legacy page - a page written over a legacy one would read as stored and rewritten alike.
	 *
	 * @param label         names the moment in failure messages
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @param entityType    the collection
	 * @param legacyIndexes the legacy sort indexes written before the migration
	 * @return how many of them are still listed and were found rewritten
	 */
	static int assertRewritten(
		@Nonnull String label,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull String entityType,
		@Nonnull List<LegacySortIndex> legacyIndexes
	) {
		return withOfflineCatalog(
			configuration, catalogName,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final DefaultEntityCollectionPersistenceService collectionService =
					collectionServiceOf(service, catalogVersion, entityType);
				final OffsetIndexStoragePartPersistenceService storage =
					collectionService.getStoragePartPersistenceService();
				final Set<Integer> listed = new HashSet<>(listedIndexes(collectionService.getEntityCollectionHeader()));
				final List<String> violations = new ArrayList<>(4);
				int rewritten = 0;
				for (final LegacySortIndex legacy : legacyIndexes) {
					for (final long pagePrimaryKey : legacy.pagePrimaryKeys()) {
						if (storage.containsStoragePart(catalogVersion, pagePrimaryKey, SortIndexLeafPagePart.class)) {
							violations.add("legacy leaf page " + pagePrimaryKey + " of " + legacy + " is still stored");
						}
					}
					final SortIndexStoragePart root = storage.getStoragePart(
						catalogVersion, legacy.rootPrimaryKey(), SortIndexStoragePart.class
					);
					if (!listed.contains(legacy.entityIndexPrimaryKey())) {
						if (root != null) {
							violations.add("the root of " + legacy + " of a converted partition is still stored");
						}
						continue;
					}
					if (root == null) {
						violations.add("the root of " + legacy + " is not stored");
						continue;
					}
					if (root.isPaged()) {
						final Set<Long> legacyPages = new HashSet<>(legacy.pagePrimaryKeys().length);
						for (final long pagePrimaryKey : legacy.pagePrimaryKeys()) {
							legacyPages.add(pagePrimaryKey);
						}
						final int streamId = streamIdOf(
							storage, legacy.entityIndexPrimaryKey(), root.getAttributeIndexKey()
						);
						for (final int pageSequence : root.getLeafPageSequences()) {
							// no rewritten page may take the identity of a legacy one - not even of one it removed
							final long pageKey = AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence);
							if (legacyPages.contains(pageKey)) {
								violations.add(
									"the rewritten root of " + legacy + " lists page " + pageSequence +
										", which reuses the identity of a legacy page"
								);
							}
							if (pageSequence <= legacy.highWaterPageSequence()) {
								violations.add(
									"the rewritten root of " + legacy + " lists page " + pageSequence +
										", not above the legacy high-water " + legacy.highWaterPageSequence()
								);
							}
						}
					}
					final List<Serializable> values = readValues(catalogVersion, storage, legacy, root);
					for (final Serializable value : values) {
						if (!isNormalized(root.getComparatorBase(), value)) {
							violations.add(legacy + " still holds the raw value `" + value + "`");
						}
					}
					final OwnerSortIndex stored = load(catalogVersion, storage, legacy, root);
					final OwnerSortIndex expected = legacy.writer().toCurrentOwner();
					if (!Arrays.equals(expected.getSortedRecords(), stored.getSortedRecords())) {
						violations.add(
							legacy + " orders its records " + Arrays.toString(stored.getSortedRecords()) +
								", the current engine " + Arrays.toString(expected.getSortedRecords())
						);
					}
					if (!Arrays.equals(expected.getSortedRecordValues(), stored.getSortedRecordValues())) {
						violations.add(
							legacy + " holds the values " + Arrays.toString(stored.getSortedRecordValues()) +
								", the current engine " + Arrays.toString(expected.getSortedRecordValues())
						);
					}
					rewritten++;
				}
				if (!violations.isEmpty()) {
					fail(label + ": " + violations.size() + " violation(s):\n" + String.join("\n", violations));
				}
				return rewritten;
			}
		);
	}

	/**
	 * Replaces one owner sort index with the parts a release storing raw temporal values wrote for its records.
	 *
	 * @param catalogVersion   the catalog version
	 * @param storage          the storage of the collection
	 * @param manifest         the manifest of the entity index holding the sort index
	 * @param indexPrimaryKey  the primary key of the entity index
	 * @param attributeKey     the attribute of the sort index
	 * @param rawValueOfRecord the raw value the old release stored for a record
	 * @return the identity of the legacy sort index written
	 */
	@Nonnull
	private static LegacySortIndex replaceWithRawSortIndex(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull EntityIndexStoragePart manifest,
		int indexPrimaryKey,
		@Nonnull AttributeIndexKey attributeKey,
		@Nonnull IntFunction<Serializable> rawValueOfRecord
	) {
		final long rootPrimaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey, AttributeIndexType.SORT, attributeKey, storage.getReadOnlyKeyCompressor()
		);
		final SortIndexStoragePart current = Objects.requireNonNull(
			storage.getStoragePart(catalogVersion, rootPrimaryKey, SortIndexStoragePart.class)
		);
		final LegacyOwnerSortIndexWriter writer = new LegacyOwnerSortIndexWriter(
			indexPrimaryKey, current.getAttributeIndexKey(), current.getComparatorBase()
		);
		if (current.isPaged()) {
			for (final ValueToRecord[] page : readPages(catalogVersion, storage, indexPrimaryKey, current)) {
				for (final ValueToRecord bucket : page) {
					for (final int record : bucket.getRecordIds().getArray()) {
						writer.add(rawValueOfRecord.apply(record), record);
					}
				}
			}
		} else {
			for (final int record : current.getSortedRecords()) {
				writer.add(rawValueOfRecord.apply(record), record);
			}
		}
		final List<StoragePart> parts = current.isPaged() ?
			writer.toPagedParts(current.getHighWaterPageSequence()) : List.of(writer.toInlinePart());
		for (final StoragePart part : parts) {
			storage.putStoragePart(catalogVersion, part);
		}
		final SortIndexStoragePart legacyRoot = root(parts);
		if (!legacyRoot.isPaged()) {
			return new LegacySortIndex(
				manifest.getEntityIndexKey(), indexPrimaryKey, current.getAttributeIndexKey(), rootPrimaryKey,
				-1, ArrayUtils.EMPTY_INT_ARRAY, new long[0], writer
			);
		}
		final int streamId = streamIdOf(storage, indexPrimaryKey, current.getAttributeIndexKey());
		final Set<Integer> legacyPages = new HashSet<>();
		for (final int pageSequence : legacyRoot.getLeafPageSequences()) {
			legacyPages.add(pageSequence);
		}
		// the pages of the replaced index no legacy page overwrites go, so the image holds the legacy pages only
		for (final int pageSequence : current.getLeafPageSequences()) {
			if (!legacyPages.contains(pageSequence)) {
				storage.removeStoragePart(
					catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
					SortIndexLeafPagePart.class
				);
			}
		}
		final int[] pageSequences = legacyRoot.getLeafPageSequences();
		final long[] pagePrimaryKeys = new long[pageSequences.length];
		for (int i = 0; i < pageSequences.length; i++) {
			pagePrimaryKeys[i] = AbstractLeafPagePart.computeUniquePartId(streamId, pageSequences[i]);
		}
		return new LegacySortIndex(
			manifest.getEntityIndexKey(), indexPrimaryKey, current.getAttributeIndexKey(), rootPrimaryKey,
			legacyRoot.getHighWaterPageSequence(), pageSequences, pagePrimaryKeys, writer
		);
	}

	/**
	 * Returns the keys of the owner sort indexes of the named attributes in an entity index - the sort indexes with no
	 * filter index under the same key.
	 *
	 * @param manifest       the manifest of the entity index
	 * @param attributeNames the attributes
	 * @return the keys
	 */
	@Nonnull
	private static List<AttributeIndexKey> ownerSortKeys(
		@Nonnull EntityIndexStoragePart manifest,
		@Nonnull Set<String> attributeNames
	) {
		final Set<AttributeIndexKey> filterKeys = new HashSet<>();
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			if (key.indexType() == AttributeIndexType.FILTER) {
				filterKeys.add(key.attribute());
			}
		}
		final List<AttributeIndexKey> sortKeys = new ArrayList<>(2);
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			if (
				key.indexType() == AttributeIndexType.SORT && !filterKeys.contains(key.attribute()) &&
					attributeNames.contains(key.attribute().attributeName())
			) {
				sortKeys.add(key.attribute());
			}
		}
		return sortKeys;
	}

	/**
	 * Returns every value a stored sort index holds, in its stored order.
	 *
	 * @param catalogVersion the catalog version
	 * @param storage        the storage of the collection
	 * @param legacy         the identity of the sort index
	 * @param root           its stored root
	 * @return the values
	 */
	@Nonnull
	private static List<Serializable> readValues(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull LegacySortIndex legacy,
		@Nonnull SortIndexStoragePart root
	) {
		if (!root.isPaged()) {
			return List.of(root.getSortedRecordsValues());
		}
		final List<Serializable> values = new ArrayList<>(64);
		for (final ValueToRecord[] page : readPages(catalogVersion, storage, legacy.entityIndexPrimaryKey(), root)) {
			for (final ValueToRecord bucket : page) {
				values.add(bucket.getValue());
			}
		}
		return values;
	}

	/**
	 * Loads a stored sort index the way the load path loads an owner sort index - which corrects nothing.
	 *
	 * @param catalogVersion the catalog version
	 * @param storage        the storage of the collection
	 * @param legacy         the identity of the sort index
	 * @param root           its stored root
	 * @return the loaded index
	 */
	@Nonnull
	private static OwnerSortIndex load(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		@Nonnull LegacySortIndex legacy,
		@Nonnull SortIndexStoragePart root
	) {
		if (root.isPaged()) {
			return OwnerSortIndex.fromPersistedPages(
				root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
				root.getLeafPageSequences(),
				readPages(catalogVersion, storage, legacy.entityIndexPrimaryKey(), root),
				root.getHighWaterPageSequence()
			);
		} else {
			return (OwnerSortIndex) SortIndex.create(
				root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
				root.getSortedRecords(), root.getSortedRecordsValues(), root.getValueCardinalities(), null
			);
		}
	}

	/**
	 * Reads the buckets of every leaf page a paged root lists, in the order it lists them.
	 *
	 * @param catalogVersion  the catalog version
	 * @param storage         the storage of the collection
	 * @param indexPrimaryKey the entity index holding the sort index
	 * @param root            the paged root
	 * @return the buckets of every leaf page
	 */
	@Nonnull
	private static ValueToRecord[][] readPages(
		long catalogVersion,
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		int indexPrimaryKey,
		@Nonnull SortIndexStoragePart root
	) {
		final int streamId = streamIdOf(storage, indexPrimaryKey, root.getAttributeIndexKey());
		final int[] pageSequences = root.getLeafPageSequences();
		final ValueToRecord[][] buckets = new ValueToRecord[pageSequences.length][];
		for (int i = 0; i < pageSequences.length; i++) {
			final SortIndexLeafPagePart page = storage.getStoragePart(
				catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequences[i]),
				SortIndexLeafPagePart.class
			);
			assertNotNull(
				page, "leaf page " + pageSequences[i] + " of `" + root.getAttributeIndexKey() + "` is missing"
			);
			buckets[i] = page.getBuckets();
		}
		return buckets;
	}

	/**
	 * Returns the id of the leaf-page stream of a sort index.
	 *
	 * @param storage         the storage of the collection
	 * @param indexPrimaryKey the entity index holding the sort index
	 * @param attributeKey    the attribute of the sort index
	 * @return the stream id
	 */
	private static int streamIdOf(
		@Nonnull OffsetIndexStoragePartPersistenceService storage,
		int indexPrimaryKey,
		@Nonnull AttributeIndexKey attributeKey
	) {
		return storage.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(indexPrimaryKey, new AttributeKeyWithIndexType(attributeKey, AttributeIndexType.SORT))
		);
	}

	/**
	 * Returns `true` when every temporal element of the value is in its normalized form - the form the temporal
	 * normalizer maps onto itself.
	 *
	 * @param base  the comparator base of the sort index
	 * @param value the stored value
	 * @return `true` when the value is normalized
	 */
	private static boolean isNormalized(@Nonnull ComparatorSource[] base, @Nonnull Serializable value) {
		final Serializable[] elements = value instanceof SortIndex.ComparableArray compound ?
			compound.array() : new Serializable[]{value};
		for (int i = 0; i < base.length; i++) {
			final Class<?> type = base[i].type();
			final boolean temporal = OffsetDateTime.class.isAssignableFrom(type) ||
				LocalDateTime.class.isAssignableFrom(type) || LocalTime.class.isAssignableFrom(type);
			if (
				temporal && elements[i] != null && FilterIndex.getNormalizer(type, 0).apply(elements[i]) != elements[i]
			) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the primary keys of every entity index a collection header lists, its global index included.
	 *
	 * @param header the collection header
	 * @return the primary keys
	 */
	@Nonnull
	private static List<Integer> listedIndexes(@Nonnull EntityCollectionFileHeader header) {
		final List<Integer> indexes = new ArrayList<>(header.usedEntityIndexPrimaryKeys());
		if (header.globalEntityIndexPrimaryKey() != null && !indexes.contains(header.globalEntityIndexPrimaryKey())) {
			indexes.add(header.globalEntityIndexPrimaryKey());
		}
		return indexes;
	}

	/**
	 * Returns the root of a list of sort index parts.
	 *
	 * @param parts the parts
	 * @return the root
	 */
	@Nonnull
	private static SortIndexStoragePart root(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexStoragePart root) {
				return root;
			}
		}
		return fail("The parts carry no sort index root: " + parts);
	}

	/**
	 * Returns the persistence service of a collection of a catalog opened with the engine stopped.
	 *
	 * @param service        the persistence service of the catalog
	 * @param catalogVersion the catalog version
	 * @param entityType     the entity type
	 * @return the persistence service of the collection
	 */
	@Nonnull
	private static DefaultEntityCollectionPersistenceService collectionServiceOf(
		@Nonnull DefaultCatalogPersistenceService service,
		long catalogVersion,
		@Nonnull String entityType
	) {
		final CollectionFileReference reference = Objects.requireNonNull(
			service.getCatalogHeader(catalogVersion).collectionFileIndex().get(entityType),
			"the catalog has no collection `" + entityType + "`"
		);
		return service.getOrCreateEntityCollectionPersistenceService(
			catalogVersion, entityType, reference.entityTypePrimaryKey()
		);
	}

	/**
	 * The identity of one legacy sort index written into a catalog.
	 *
	 * @param entityIndexKey        the key of the entity index holding it
	 * @param entityIndexPrimaryKey the primary key of that entity index
	 * @param attributeKey          the attribute of the sort index
	 * @param rootPrimaryKey        the primary key of its root part
	 * @param highWaterPageSequence the high-water of its leaf-page stream, `-1` for an inline index
	 * @param pageSequences         the leaf pages its root lists, empty for an inline index
	 * @param pagePrimaryKeys       the primary keys of those leaf pages
	 * @param writer                the legacy writer holding the raw value of every record
	 */
	record LegacySortIndex(
		@Nonnull EntityIndexKey entityIndexKey,
		int entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeKey,
		long rootPrimaryKey,
		int highWaterPageSequence,
		@Nonnull int[] pageSequences,
		@Nonnull long[] pagePrimaryKeys,
		@Nonnull LegacyOwnerSortIndexWriter writer
	) {

		/**
		 * Returns `true` for a sort index written as leaf pages and a paged root.
		 *
		 * @return `true` when paged
		 */
		boolean paged() {
			return this.pageSequences.length > 0;
		}

		@Nonnull
		@Override
		public String toString() {
			return "`" + this.attributeKey + "` of entity index " + this.entityIndexPrimaryKey + " " +
				this.entityIndexKey + (paged() ? " (" + this.pageSequences.length + " leaf pages)" : " (inline)");
		}
	}
}
