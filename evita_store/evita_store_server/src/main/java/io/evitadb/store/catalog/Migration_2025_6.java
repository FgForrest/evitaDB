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


import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.EntityCollection;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.spi.HeaderInfoSupplier;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePartDeprecated;
import io.evitadb.store.spi.model.storageParts.index.ReferenceNameKey;
import io.evitadb.store.spi.model.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.NumberUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

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

		final Collection<CollectionFileReference> entityTypeFileIndexes = catalogHeader.getEntityTypeFileIndexes();
		final HashMap<String, CollectionFileReference> newCollectionFileIndex = CollectionUtils.createHashMap(entityTypeFileIndexes.size());
		// fetch all entity collection headers
		for (CollectionFileReference entityTypeFileIndex : entityTypeFileIndexes) {
			final EntityCollectionHeader entityCollectionHeader = Objects.requireNonNull(
				storagePartPersistenceService.getStoragePart(
					catalogHeader.version(), entityTypeFileIndex.entityTypePrimaryKey(), EntityCollectionHeader.class
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
			// first prepare map of referenced entity ids to their reduced entity index primary keys
			final Map<String, Set<Long>> referencedEntityIdToReducedEntityIndexPrimaryKey = new HashMap<>(256);
			final Set<Integer> indexIds = new HashSet<>(entityCollectionHeader.usedEntityIndexPrimaryKeys());
			if (entityCollectionHeader.globalEntityIndexPrimaryKey() != null) {
				indexIds.add(entityCollectionHeader.globalEntityIndexPrimaryKey());
			}
			for (Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart storagePart = Objects.requireNonNull(
					collectionStoragePartService.getStoragePart(
						catalogHeader.version(), indexPrimaryKey, EntityIndexStoragePart.class
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
				}
			}
			// migrate all entity indexes
			for (Integer indexPrimaryKey : indexIds) {
				final EntityIndexStoragePart storagePart = collectionStoragePartService.getStoragePart(
					catalogHeader.version(), indexPrimaryKey, EntityIndexStoragePart.class
				);
				if (storagePart instanceof EntityIndexStoragePartDeprecated deprecatedIndexBody) {
					// migrate deprecated referenced type cardinality index to new format
					if (deprecatedIndexBody.getEntityIndexKey().type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
						final String referenceName = (String) Objects.requireNonNull(deprecatedIndexBody.getEntityIndexKey().discriminator());
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
					// store upgraded entity index storage part
					collectionStoragePartService.putStoragePart(
						catalogHeader.version(),
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
				} else if (storagePart == null) {
					throw new GenericEvitaInternalError("Entity index storage part for primary key " + indexPrimaryKey + " is missing!");
				} else {
					throw new GenericEvitaInternalError("Unexpected storage part type for primary key " + indexPrimaryKey + ": " + storagePart.getClass().getSimpleName());
				}
			}
			final OffsetIndexDescriptor offsetIndexDescriptor = collectionPersistenceService.flush(
				catalogHeader.version(),
				new NoChangeHeaderInfoSupplier(entityCollectionHeader)
			);
			final EntityCollectionHeader newCollectionHeader = collectionPersistenceService.getEntityCollectionHeader();
			storagePartPersistenceService.putStoragePart(
				catalogHeader.version(),
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
		}
		// there is nothing to migrate in the storage, just run the post upgrade action
		postUpgradeAction.accept(
			new CatalogHeader(
				4,
				catalogHeader.version(),
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
