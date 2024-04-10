/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.spi.model.storageParts.accessor;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart.EntityAssociatedDataKey;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.serializer.EntitySchemaContext;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * This abstract class centralizes the logic for accessing entity storage containers / storage parts stored in the
 * persistent storage, using {@link DataStoreMemoryBuffer} as a mean for accessing it and getting advantage of data
 * trapped in volatile memory.
 *
 * See descendants of this class to get an idea about use-cases of this abstract class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class AbstractEntityStorageContainerAccessor implements EntityStoragePartAccessor {
	/**
	 * Represents the catalog version the storage container accessor is related to.
	 */
	protected final long catalogVersion;
	/**
	 * Contains CURRENT storage buffer that traps transactional and intermediate volatile data.
	 */
	@Nonnull protected final DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer;
	/**
	 * Function returns CURRENT {@link EntitySchema} to be used for deserialized objects.
	 */
	@Nonnull private final Supplier<EntitySchema> schemaAccessor;

	@Nonnull
	public EntityBodyStoragePart getEntityStoragePart(
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull EntityExistence expects
	) {
		assertEntityTypeMatches(entityType);
		// if entity container is already present - return it quickly
		return ofNullable(getCachedEntityStorageContainer(entityPrimaryKey))
			// when not
			.orElseGet(() -> {
				final EntitySchema schema = schemaAccessor.get();
				return EntitySchemaContext.executeWithSchemaContext(
					schema,
					() -> {
						final Serializable theEntityType = schema.getName();
						// read it from mem table
						return cacheEntityStorageContainer(
							entityPrimaryKey,
							ofNullable(storageContainerBuffer.fetch(catalogVersion, entityPrimaryKey, EntityBodyStoragePart.class))
								.map(it -> {
									// if it was found, verify whether it was expected
									if (expects == EntityExistence.MUST_NOT_EXIST && !it.isMarkedForRemoval()) {
										throw new InvalidMutationException(
											"There is already entity " + theEntityType + " with primary key " +
												entityPrimaryKey + " present! Please fetch this entity and perform update " +
												"operation on top of it."
										);
									} else if (expects == EntityExistence.MUST_EXIST && it.isMarkedForRemoval()) {
										throw new InvalidMutationException(
											"There is no entity " + theEntityType + " with primary key " +
												entityPrimaryKey + " present! This means, that you're probably trying to update " +
												"entity that has been already removed!"
										);
									}
									return it;
								})
								.orElseGet(() -> {
									// if it was not found, verify whether it was expected
									if (expects == EntityExistence.MUST_EXIST) {
										throw new InvalidMutationException(
											"There is no entity " + theEntityType + " with primary key " +
												entityPrimaryKey + " present! This means, that you're probably trying to update " +
												"entity that has been already removed!"
										);
									} else {
										// create new container for the entity
										return new EntityBodyStoragePart(entityPrimaryKey);
									}
								})
						);
					});
			});
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		assertEntityTypeMatches(entityType);
		// if attributes container is already present - return it quickly
		return ofNullable(getCachedAttributeStorageContainer(entityPrimaryKey))
			// when not
			.orElseGet(
				() -> EntitySchemaContext.executeWithSchemaContext(
					schemaAccessor.get(),
					() -> {
						// try to compute container id (keyCompressor must already recognize the EntityAttributesSetKey)
						final EntityAttributesSetKey globalAttributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, null);
						return cacheAttributeStorageContainer(
							entityPrimaryKey,
							ofNullable(storageContainerBuffer.fetch(catalogVersion, globalAttributeSetKey, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId))
								// when not found in storage - create new container
								.orElseGet(() -> new AttributesStoragePart(entityPrimaryKey))
						);
					})
			);
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale) {
		assertEntityTypeMatches(entityType);
		// check existence locale specific attributes index
		final Map<Locale, AttributesStoragePart> attributesContainer = getOrCreateCachedLocalizedAttributesStorageContainer(entityPrimaryKey);
		// if attributes container is already present in the index - return it quickly
		return attributesContainer.computeIfAbsent(
			locale,
			language -> EntitySchemaContext.executeWithSchemaContext(
				schemaAccessor.get(),
				() -> {
					// try to compute container id (keyCompressor must already recognize the EntityAttributesSetKey)
					final EntityAttributesSetKey localeSpecificAttributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, language);
					return ofNullable(storageContainerBuffer.fetch(catalogVersion, localeSpecificAttributeSetKey, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId))
						// when not found in storage - create new container
						.orElseGet(() -> new AttributesStoragePart(entityPrimaryKey, locale));
				}
			)
		);
	}

	@Nonnull
	@Override
	public AssociatedDataStoragePart getAssociatedDataStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		assertEntityTypeMatches(entityType);
		// check existence locale specific associated data index
		final Map<AssociatedDataKey, AssociatedDataStoragePart> associatedDataContainer = getOrCreateCachedAssociatedDataStorageContainer(entityPrimaryKey, key);
		// if associated data container is already present in the index - return it quickly
		return associatedDataContainer.computeIfAbsent(
			key,
			associatedDataKey -> EntitySchemaContext.executeWithSchemaContext(
				schemaAccessor.get(),
				() -> {
					// try to compute container id (keyCompressor must already recognize the EntityAssociatedDataKey)
					final EntityAssociatedDataKey entityAssociatedDataKey = new EntityAssociatedDataKey(entityPrimaryKey, key.associatedDataName(), key.locale());
					return ofNullable(storageContainerBuffer.fetch(catalogVersion, entityAssociatedDataKey, AssociatedDataStoragePart.class, AssociatedDataStoragePart::computeUniquePartId))
						// when not found in storage - create new container
						.orElseGet(() -> new AssociatedDataStoragePart(entityPrimaryKey, associatedDataKey));
				})
		);
	}

	@Nonnull
	@Override
	public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		assertEntityTypeMatches(entityType);
		// if reference container is already present - return it quickly
		return ofNullable(getCachedReferenceStorageContainer(entityPrimaryKey))
			//when not
			.orElseGet(
				() -> EntitySchemaContext.executeWithSchemaContext(
					schemaAccessor.get(),
					// read it from mem table
					() -> cacheReferencesStorageContainer(
						entityPrimaryKey,
						ofNullable(storageContainerBuffer.fetch(catalogVersion, entityPrimaryKey, ReferencesStoragePart.class))
							// and when not found even there create new container
							.orElseGet(() -> new ReferencesStoragePart(entityPrimaryKey))
					)
				));
	}

	@Nonnull
	@Override
	public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		assertEntityTypeMatches(entityType);
		// if price container is already present - return it quickly
		return ofNullable(getCachedPricesStorageContainer(entityPrimaryKey))
			//when not
			.orElseGet(
				() -> EntitySchemaContext.executeWithSchemaContext(
					schemaAccessor.get(),
					// read it from mem table
					() -> cachePricesStorageContainer(
						entityPrimaryKey,
						ofNullable(storageContainerBuffer.fetch(catalogVersion, entityPrimaryKey, PricesStoragePart.class))
							// and when not found even there create new container
							.orElseGet(() -> new PricesStoragePart(entityPrimaryKey))
					)
				));
	}

	@Nullable
	protected abstract EntityBodyStoragePart getCachedEntityStorageContainer(int entityPrimaryKey);

	@Nonnull
	protected abstract EntityBodyStoragePart cacheEntityStorageContainer(int entityPrimaryKey, @Nonnull EntityBodyStoragePart entityStorageContainer);

	@Nullable
	protected abstract AttributesStoragePart getCachedAttributeStorageContainer(int entityPrimaryKey);

	@Nonnull
	protected abstract AttributesStoragePart cacheAttributeStorageContainer(int entityPrimaryKey, @Nonnull AttributesStoragePart attributesStorageContainer);

	@Nonnull
	protected abstract Map<Locale, AttributesStoragePart> getOrCreateCachedLocalizedAttributesStorageContainer(int entityPrimaryKey);

	@Nonnull
	protected abstract Map<AssociatedDataKey, AssociatedDataStoragePart> getOrCreateCachedAssociatedDataStorageContainer(int entityPrimaryKey, @Nonnull AssociatedDataKey key);

	@Nullable
	protected abstract ReferencesStoragePart getCachedReferenceStorageContainer(int entityPrimaryKey);

	@Nonnull
	protected abstract ReferencesStoragePart cacheReferencesStorageContainer(int entityPrimaryKey, @Nonnull ReferencesStoragePart referencesStorageContainer);

	@Nullable
	protected abstract PricesStoragePart getCachedPricesStorageContainer(int entityPrimaryKey);

	@Nonnull
	protected abstract PricesStoragePart cachePricesStorageContainer(int entityPrimaryKey, @Nonnull PricesStoragePart pricesStorageContainer);

	protected void assertEntityTypeMatches(@Nonnull String entityType) {
		Assert.isPremiseValid(
			Objects.equals(entityType, schemaAccessor.get().getName()),
			() -> "Entity types must match! Expected `" + entityType + "`, got `" + schemaAccessor.get().getName() + "`"
		);
	}

}
