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

package io.evitadb.store.catalog;


import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.RangePoint;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.store.entity.model.schema.EntitySchemaStoragePart;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.spi.HeaderInfoSupplier;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePartDeprecated;
import io.evitadb.store.spi.model.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.ReferenceNameKey;
import io.evitadb.store.spi.model.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.NumberUtils;
import lombok.RequiredArgsConstructor;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.ofNullable;

/**
 * Migration interface containing one-time migration logic for upgrading data storage structures from version 3
 * to version 4. The migration primarily handles the conversion of deprecated ReferencedTypeEntityIndex formats
 * to the new format introduced in version 4.
 *
 * This migration performs the following tasks:
 * - Updates entity collection headers and their associated storage parts
 * - Converts deprecated referenced type cardinality indexes to new format
 * - Updates the catalog storage protocol version from 3 to 4
 *
 * @deprecated introduced with #906 and could be removed later when no version prior to 2025.7 is used
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Deprecated(since = "2025.6", forRemoval = true)
public interface Migration_2025_6 {

	/**
	 * Upgrades the catalog storage protocol version from version 3 to version 4.
	 *
	 * This method processes entity collections within the catalog to update the storage format
	 * to comply with the requirements of protocol version 4. It ensures any outdated
	 * ReferencedTypeEntityIndex formats are migrated and relevant changes are applied to storage parts.
	 * Once the upgrade is complete, it triggers a post-upgrade action.
	 *
	 * @param catalogHeader The header of the catalog to be upgraded, containing metadata and file references.
	 * @param storagePartPersistenceService The persistence service responsible for managing catalog storage parts.
	 * @param entityCollectionPersistenceServiceFactory A factory function to create persistence services for specific entity collections.
	 * @param postUpgradeAction A consumer that executes a custom action after the catalog has been upgraded, accepting the updated catalog header.
	 */
	static void upgradeFromStorageProtocolVersion_3_to_4(
		@Nonnull CatalogHeader catalogHeader,
		@Nonnull CatalogOffsetIndexStoragePartPersistenceService storagePartPersistenceService,
		@Nonnull Function<EntityCollectionHeader, DefaultEntityCollectionPersistenceService> entityCollectionPersistenceServiceFactory,
		@Nonnull Consumer<CatalogHeader> postUpgradeAction
	) {
		// upgrade storage protocol version 3 to 4
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` contains an old format of ReferencedTypeEntityIndex (the protocol version 3). Upgrading.",
			ConsoleColor.BRIGHT_BLUE
		);

		final long catalogVersion = catalogHeader.version();
		final Collection<CollectionFileReference> entityTypeFileIndexes = catalogHeader.getEntityTypeFileIndexes();
		final HashMap<String, CollectionFileReference> newCollectionFileIndex = CollectionUtils.createHashMap(entityTypeFileIndexes.size());
		// fetch all entity collection headers
		for (CollectionFileReference entityTypeFileIndex : entityTypeFileIndexes) {
			final EntityCollectionHeader entityCollectionHeader = Objects.requireNonNull(
				storagePartPersistenceService.getStoragePart(
					catalogVersion, entityTypeFileIndex.entityTypePrimaryKey(), EntityCollectionHeader.class
				)
			);
			final DefaultEntityCollectionPersistenceService collectionPersistenceService = Objects.requireNonNull(
				entityCollectionPersistenceServiceFactory.apply(
					new EntityCollectionHeader(
						entityCollectionHeader.version(),
						entityCollectionHeader.fileLocation(),
						migrateStrings(entityCollectionHeader.compressedKeys()),
						entityCollectionHeader.entityType(),
						entityCollectionHeader.entityTypePrimaryKey(),
						entityCollectionHeader.entityTypeFileIndex(),
						entityCollectionHeader.recordCount(),
						entityCollectionHeader.lastPrimaryKey(),
						entityCollectionHeader.lastEntityIndexPrimaryKey(),
						entityCollectionHeader.lastInternalPriceId(),
						entityCollectionHeader.storageDescriptor(),
						entityCollectionHeader.globalEntityIndexPrimaryKey(),
						entityCollectionHeader.usedEntityIndexPrimaryKeys(),
						entityCollectionHeader.lastKeyId(),
						entityCollectionHeader.activeRecordShare()
					)
				)
			);
			final OffsetIndexStoragePartPersistenceService collectionStoragePartService =
				collectionPersistenceService.getStoragePartPersistenceService();
			final EntitySchema entitySchema = Objects.requireNonNull(
				collectionStoragePartService.getStoragePart(catalogVersion, 1, EntitySchemaStoragePart.class)
			).entitySchema();
			// first prepare map of referenced entity ids to their reduced entity index primary keys
			final Map<String, Set<Long>> referencedEntityIdToReducedEntityIndexPrimaryKey = new HashMap<>(256);
			final Set<Integer> indexIds = new HashSet<>(entityCollectionHeader.usedEntityIndexPrimaryKeys());
			final IntIntMap referencedEntityToIndexIdMap = new IntIntHashMap();
			if (entityCollectionHeader.globalEntityIndexPrimaryKey() != null) {
				indexIds.add(entityCollectionHeader.globalEntityIndexPrimaryKey());
			}
			for (Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart storagePart = Objects.requireNonNull(
					collectionStoragePartService.getStoragePart(
						catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
					)
				);
				if (storagePart.getEntityIndexKey().type() == EntityIndexType.REFERENCED_ENTITY || storagePart.getEntityIndexKey().type() == EntityIndexType.REFERENCED_HIERARCHY_NODE) {
					final RepresentativeReferenceKey referenceKey = (RepresentativeReferenceKey) Objects.requireNonNull(
						storagePart.getEntityIndexKey().discriminator()
					);
					referencedEntityIdToReducedEntityIndexPrimaryKey.computeIfAbsent(
						referenceKey.referenceName(),
						__ -> new java.util.HashSet<>(1024)
					).add(NumberUtils.join(referenceKey.primaryKey(), indexPrimaryKey));
					referencedEntityToIndexIdMap.put(referenceKey.primaryKey(), indexPrimaryKey);
				}
			}
			// migrate all entity indexes
			int indexesMigrated = 0;
			for (Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart storagePart = collectionStoragePartService.getStoragePart(
					catalogVersion, indexPrimaryKey, EntityIndexStoragePart.class
				);
				if (storagePart instanceof EntityIndexStoragePartDeprecated deprecatedIndexBody) {
					// migrate deprecated referenced type cardinality index to new format
					if (deprecatedIndexBody.getEntityIndexKey().type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
						final String referenceName = (String) Objects.requireNonNull(deprecatedIndexBody.getEntityIndexKey().discriminator());
						migrateReferencedTypeCardinalityIndex(
							catalogHeader,
							indexPrimaryKey,
							referencedEntityIdToReducedEntityIndexPrimaryKey,
							referenceName,
							collectionStoragePartService
						);
						// migrate attribute index
						for (AttributeIndexStorageKey attributeIndexKey : deprecatedIndexBody.getAttributeIndexes()) {
							switch (attributeIndexKey.indexType()) {
								case UNIQUE -> migrateUniqueIndex(
									catalogVersion,
									indexPrimaryKey,
									attributeIndexKey,
									collectionStoragePartService,
									referencedEntityToIndexIdMap
								);
								case FILTER -> migrateFilterIndex(
									catalogVersion,
									indexPrimaryKey,
									attributeIndexKey,
									collectionStoragePartService,
									referencedEntityToIndexIdMap,
									referenceName,
									entitySchema
								);
								case CARDINALITY -> migrateAttributeCardinalityIndex(
									catalogVersion, indexPrimaryKey, attributeIndexKey, collectionStoragePartService,
									referencedEntityToIndexIdMap
								);
							}
						}
					}
					// store upgraded entity index storage part
					collectionStoragePartService.putStoragePart(
						catalogVersion,
						new EntityIndexStoragePart(
							deprecatedIndexBody.getPrimaryKey(),
							deprecatedIndexBody.getVersion(),
							deprecatedIndexBody.getEntityIndexKey(),
							deprecatedIndexBody.getEntityIds(),
							deprecatedIndexBody.getEntityIdsByLanguage(),
							deprecatedIndexBody.getAttributeIndexes(),
							deprecatedIndexBody.getPriceIndexes(),
							deprecatedIndexBody.isHierarchyIndex(),
							deprecatedIndexBody.getFacetIndexes()
						)
					);
					indexesMigrated++;
				} else if (storagePart == null) {
					throw new GenericEvitaInternalError("Entity index storage part for primary key " + indexPrimaryKey + " is missing!");
				} else {
					throw new GenericEvitaInternalError("Unexpected storage part type for primary key " + indexPrimaryKey + ": " + storagePart.getClass().getSimpleName());
				}
			}
			final OffsetIndexDescriptor offsetIndexDescriptor = collectionPersistenceService.flush(
				catalogVersion,
				new NoChangeHeaderInfoSupplier(entityCollectionHeader)
			);
			final EntityCollectionHeader newCollectionHeader = collectionPersistenceService.getEntityCollectionHeader();
			storagePartPersistenceService.putStoragePart(
				catalogVersion,
				newCollectionHeader
			);
			newCollectionFileIndex.put(
				entityTypeFileIndex.entityType(),
				new CollectionFileReference(
					entityTypeFileIndex.entityType(),
					entityTypeFileIndex.entityTypePrimaryKey(),
					entityTypeFileIndex.fileIndex(),
					offsetIndexDescriptor.fileLocation()
				)
			);
			ConsoleWriter.writeLine(
				"Entity collection `" + entityCollectionHeader.entityType() + "`: migrated " + indexesMigrated + " indexes.",
				ConsoleColor.BRIGHT_BLUE
			);
		}
		// there is nothing to migrate in the storage, just run the post upgrade action
		postUpgradeAction.accept(
			new CatalogHeader(
				4,
				catalogVersion,
				catalogHeader.walFileReference(),
				newCollectionFileIndex,
				migrateStrings(catalogHeader.compressedKeys()),
				catalogHeader.catalogId(),
				catalogHeader.catalogName(),
				catalogHeader.catalogState(),
				catalogHeader.lastEntityCollectionPrimaryKey(),
				catalogHeader.activeRecordShare()
			)
		);
		// finish
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` was successfully upgraded to the protocol version 4.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
	}

	/**
	 * Migrates the cardinality index of an attribute by updating references and persisting the updated index.
	 *
	 * @param catalogVersion The version of the catalog to which the attribute cardinality index belongs.
	 * @param indexPrimaryKey The unique primary key identifier for the attribute cardinality index.
	 * @param attributeIndexKey The key representing the specific attribute index being migrated.
	 * @param collectionStoragePartService The persistence service used to interact with storage parts.
	 * @param referencedEntityToIndexIdMap A mapping of referenced entity primary keys to their corresponding index IDs.
	 */
	private static void migrateAttributeCardinalityIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService,
		@Nonnull IntIntMap referencedEntityToIndexIdMap
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey,
			AttributeIndexType.CARDINALITY,
			attributeIndexKey.attribute(),
			collectionStoragePartService.getReadOnlyKeyCompressor()
		);
		final AttributeCardinalityIndexStoragePart cardinalityIndexCnt = collectionStoragePartService.getStoragePart(
			catalogVersion, primaryKey, AttributeCardinalityIndexStoragePart.class
		);
		isPremiseValid(
			cardinalityIndexCnt != null,
			"Cardinality index with id " + indexPrimaryKey + " with key " + attributeIndexKey.attribute() + " was not found in persistent storage!"
		);
		final AttributeCardinalityIndex cardinalityIndex = cardinalityIndexCnt.getCardinalityIndex();
		final Map<AttributeCardinalityKey, Integer> cardinalities = cardinalityIndex.getCardinalities();
		final Map<AttributeCardinalityKey, Integer> migratedCardinalities = CollectionUtils.createHashMap(cardinalities.size());
		for (Entry<AttributeCardinalityKey, Integer> entry : cardinalities.entrySet()) {
			migratedCardinalities.put(
				new AttributeCardinalityKey(
					referencedEntityToIndexIdMap.get(entry.getKey().recordId()),
					entry.getKey().value()
				),
				entry.getValue()
			);
		}
		collectionStoragePartService.putStoragePart(
			catalogVersion,
			new AttributeCardinalityIndexStoragePart(
				cardinalityIndexCnt.getEntityIndexPrimaryKey(),
				cardinalityIndexCnt.getAttributeIndexKey(),
				new AttributeCardinalityIndex(
					cardinalityIndex.getValueType(),
					migratedCardinalities
				),
				cardinalityIndexCnt.getStoragePartPK()
			)
		);
	}

	/**
	 * Migrates the filter index for a specific catalog entity to a new format, ensuring compatibility
	 * with the updated catalog storage protocol. This method updates histogram points and range indexes
	 * for the attribute index being processed and persists the changes to the storage service.
	 *
	 * @param catalogVersion               The version of the catalog being migrated.
	 * @param indexPrimaryKey              The primary key of the filter index to be migrated.
	 * @param attributeIndexKey            Unique key identifying the attribute index being migrated.
	 * @param collectionStoragePartService The persistence service used to manage storage parts during the migration.
	 * @param referencedEntityToIndexIdMap A mapping of referenced entity IDs to their corresponding reduced index IDs,
	 *                                     utilized during migration.
	 */
	private static void migrateFilterIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService,
		@Nonnull IntIntMap referencedEntityToIndexIdMap,
		@Nonnull String referenceName,
		@Nonnull EntitySchema entitySchema
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey,
			AttributeIndexType.FILTER,
			attributeIndexKey.attribute(),
			collectionStoragePartService.getReadOnlyKeyCompressor()
		);
		final FilterIndexStoragePart filterIndexCnt = collectionStoragePartService.getStoragePart(
			catalogVersion, primaryKey, FilterIndexStoragePart.class
		);
		isPremiseValid(
			filterIndexCnt != null,
			"Filter index with id " + indexPrimaryKey + " with key " + attributeIndexKey.attribute() + " was not found in persistent storage!"
		);
		final ValueToRecordBitmap[] histogramPoints = filterIndexCnt.getHistogramPoints();
		final ValueToRecordBitmap[] migratedHistogramPoints = new ValueToRecordBitmap[histogramPoints.length];
		for (int i = 0; i < histogramPoints.length; i++) {
			final ValueToRecordBitmap histogramPoint = histogramPoints[i];
			migratedHistogramPoints[i] = new ValueToRecordBitmap(
				histogramPoint.getValue(),
				migrateReferencedEntityIdsBitmap(
					histogramPoint.getRecordIds(), referencedEntityToIndexIdMap
				)
			);
		}
		final RangeIndex rangeIndex = filterIndexCnt.getRangeIndex();
		final RangeIndex migratedRangeIndex;
		if (rangeIndex == null) {
			migratedRangeIndex = null;
		} else {
			final RangePoint<?>[] ranges = rangeIndex.getRanges();
			final TransactionalRangePoint[] migratedRanges = new TransactionalRangePoint[ranges.length];
			for (int i = 0; i < ranges.length; i++) {
				final RangePoint<?> range = ranges[i];
				migratedRanges[i] = new TransactionalRangePoint(
					range.getThreshold(),
					migrateReferencedEntityIdsBitmap(range.getStarts(), referencedEntityToIndexIdMap),
					migrateReferencedEntityIdsBitmap(range.getEnds(), referencedEntityToIndexIdMap)
				);
			}
			migratedRangeIndex = new RangeIndex(migratedRanges);
		}
		//noinspection rawtypes,unchecked
		collectionStoragePartService.putStoragePart(
			catalogVersion,
			new FilterIndexStoragePart(
				indexPrimaryKey,
				filterIndexCnt.getAttributeIndexKey(),
				ofNullable(filterIndexCnt.getAttributeType())
					.orElseGet(
						() -> (Class) entitySchema
							.getReferenceOrThrowException(referenceName)
							.getAttribute(attributeIndexKey.attribute().attributeName())
							.orElseThrow()
							.getType()
					),
				migratedHistogramPoints,
				migratedRangeIndex,
				filterIndexCnt.getStoragePartPK()
			)
		);
	}

	/**
	 * Migrates a unique index from the old format to the updated format, ensuring compatibility
	 * with the current catalog storage structure and protocol. This process involves resolving
	 * and reassigning referencing mappings, compressing keys, and persisting the updated storage parts.
	 *
	 * @param catalogVersion The version of the catalog being migrated.
	 * @param indexPrimaryKey The primary key of the unique index being migrated.
	 * @param attributeIndexKey A unique key that identifies the attribute index to be migrated.
	 * @param collectionStoragePartService The persistence service responsible for managing storage parts during the migration.
	 * @param referencedEntityToIndexIdMap A mapping of referenced entity IDs to their corresponding reduced index IDs, used during the migration process.
	 */
	private static void migrateUniqueIndex(
		long catalogVersion,
		int indexPrimaryKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService,
		@Nonnull IntIntMap referencedEntityToIndexIdMap
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			indexPrimaryKey,
			AttributeIndexType.UNIQUE,
			attributeIndexKey.attribute(),
			collectionStoragePartService.getReadOnlyKeyCompressor()
		);
		final UniqueIndexStoragePart uniqueIndexCnt = collectionStoragePartService.getStoragePart(
			catalogVersion, primaryKey, UniqueIndexStoragePart.class);
		isPremiseValid(
			uniqueIndexCnt != null,
			"Unique index with id " + indexPrimaryKey + " with key " + attributeIndexKey.attribute() + " was not found in persistent storage!"
		);
		final Map<Serializable, Integer> uniqueValueToRecordId = uniqueIndexCnt.getUniqueValueToRecordId();
		final Map<Serializable, Integer> migratedUniqueValueToRecordId = CollectionUtils.createHashMap(
			uniqueValueToRecordId.size()
		);
		final RoaringBitmapWriter<RoaringBitmap> migratedRecordIdsWriter = RoaringBitmapBackedBitmap.buildWriter();
		for (Entry<Serializable, Integer> entry : uniqueValueToRecordId.entrySet()) {
			final int indexId = referencedEntityToIndexIdMap.get(entry.getValue());
			migratedUniqueValueToRecordId.put(entry.getKey(), indexId);
			migratedRecordIdsWriter.add(indexId);
		}
		collectionStoragePartService.putStoragePart(
			catalogVersion,
			new UniqueIndexStoragePart(
				uniqueIndexCnt.getEntityIndexPrimaryKey(),
				uniqueIndexCnt.getAttributeIndexKey(),
				uniqueIndexCnt.getType(),
				migratedUniqueValueToRecordId,
				new BaseBitmap(migratedRecordIdsWriter.get()),
				uniqueIndexCnt.getStoragePartPK()
			)
		);
	}

	/**
	 * Migrates a reference type cardinality index for a given catalog entity. This method processes
	 * specific mappings between referenced entity IDs and their reduced index primary keys, using
	 * the provided reference name, and updates the corresponding storage parts accordingly.
	 *
	 * @param catalogHeader The header of the catalog containing metadata and version information.
	 * @param indexPrimaryKey The primary key of the index being migrated.
	 * @param referencedEntityIdToReducedEntityIndexPrimaryKey A map associating reference names to a set of
	 *                                                         compressed values representing referenced entity IDs
	 *                                                         and their corresponding reduced index primary keys.
	 * @param referenceName The name of the reference to process during the migration.
	 * @param collectionStoragePartService The service responsible for managing and persisting storage parts in
	 *                                      the catalog.
	 */
	private static void migrateReferencedTypeCardinalityIndex(
		@Nonnull CatalogHeader catalogHeader,
		int indexPrimaryKey,
		@Nonnull Map<String, Set<Long>> referencedEntityIdToReducedEntityIndexPrimaryKey,
		@Nonnull String referenceName,
		@Nonnull OffsetIndexStoragePartPersistenceService collectionStoragePartService
	) {
		final ReferenceTypeCardinalityIndex referenceTypeCardinalityIndex = new ReferenceTypeCardinalityIndex();
		final Set<Long> keyMapping = referencedEntityIdToReducedEntityIndexPrimaryKey.get(referenceName);
		for (Long compressedValue : keyMapping) {
			final int[] split = NumberUtils.split(compressedValue);
			final int referencedEntityPrimaryKey = split[0];
			final int referencedEntityIndexPrimaryKey = split[1];
			referenceTypeCardinalityIndex.addRecord(referencedEntityIndexPrimaryKey, referencedEntityPrimaryKey);
		}
		final ReferenceTypeCardinalityIndexStoragePart referenceTypeCardinalityIndexStoragePart =
			referenceTypeCardinalityIndex.createStoragePart(indexPrimaryKey, referenceName);
		// store the new reference type cardinality index
		if (referenceTypeCardinalityIndexStoragePart != null) {
			collectionStoragePartService.putStoragePart(
				catalogHeader.version(),
				referenceTypeCardinalityIndexStoragePart
			);
		}
	}

	/**
	 * Migrates the referenced entity IDs from the given {@code histogramPoint} to a new bitmap structure,
	 * mapping the entity IDs to their corresponding reduced index IDs using the {@code referencedEntityToIndexIdMap}.
	 *
	 * @param referencedEntityToIndexIdMap A mapping of referenced entity IDs to their reduced index IDs.
	 * @return A {@link RoaringBitmapWriter} containing the migrated data structure with updated entity IDs mapped.
	 */
	@Nonnull
	private static Bitmap migrateReferencedEntityIdsBitmap(
		@Nonnull Bitmap recordIds,
		@Nonnull IntIntMap referencedEntityToIndexIdMap
	) {
		final RoaringBitmapWriter<RoaringBitmap> migratedBitmapWriter = RoaringBitmapBackedBitmap.buildWriter();
		final OfInt it = recordIds.iterator();
		while (it.hasNext()) {
			migratedBitmapWriter.add(referencedEntityToIndexIdMap.get(it.nextInt()));
		}
		return new BaseBitmap(migratedBitmapWriter.get());
	}

	/**
	 * In previous versions the reference name was stored as a plain string. Since 2025.6 we store it as a
	 * {@link ReferenceNameKey} instance, so that we avoid conflicts on generic class type such as String.
	 * We always need specialized wrapper for compressed keys to avoid potential issues.
	 * This method migrates the map accordingly.
	 *
	 * @param compressedKeys map to be migrated
	 * @return migrated map
	 */
	@Nonnull
	static Map<Integer, Object> migrateStrings(@Nonnull Map<Integer, Object> compressedKeys) {
		final HashMap<Integer, Object> migratedKeys = CollectionUtils.createHashMap(compressedKeys.size());
		for (Entry<Integer, Object> entry : compressedKeys.entrySet()) {
			migratedKeys.put(
				entry.getKey(),
				entry.getValue() instanceof String str ? new ReferenceNameKey(str) : entry.getValue()
			);
		}
		return migratedKeys;
	}

	/**
	 * An implementation of the {@link HeaderInfoSupplier} interface that provides access to header information
	 * without modifying the header data. This class utilizes the {@link EntityCollectionHeader} to retrieve various
	 * header details related to an {@link EntityCollection}.
	 *
	 * This class is immutable and ensures that the header information is retrieved exactly as it exists
	 * in the associated {@link EntityCollectionHeader}.
	 */
	@RequiredArgsConstructor
	class NoChangeHeaderInfoSupplier implements HeaderInfoSupplier {
		private final EntityCollectionHeader entityCollectionHeader;

		@Override
		public int getLastAssignedPrimaryKey() {
			return this.entityCollectionHeader.lastPrimaryKey();
		}

		@Override
		public int getLastAssignedIndexKey() {
			return this.entityCollectionHeader.lastEntityIndexPrimaryKey();
		}

		@Override
		public int getLastAssignedInternalPriceId() {
			return this.entityCollectionHeader.lastInternalPriceId();
		}

		@Nonnull
		@Override
		public OptionalInt getGlobalIndexPrimaryKey() {
			return this.entityCollectionHeader.globalEntityIndexPrimaryKey() == null ?
				OptionalInt.empty() :
				OptionalInt.of(this.entityCollectionHeader.globalEntityIndexPrimaryKey());
		}

		@Nonnull
		@Override
		public List<Integer> getIndexPrimaryKeys() {
			return this.entityCollectionHeader.usedEntityIndexPrimaryKeys();
		}
	}
}
