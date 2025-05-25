/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.spi.model.storageParts.accessor;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * This implementation delegates entity storage part access method evaluation to appropriate
 * {@link ReadOnlyEntityStorageContainerAccessor} by the passed {@link EntitySchema#getName() entity type} parameter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class CatalogReadOnlyEntityStorageContainerAccessor implements EntityStoragePartAccessor {
	@Nonnull private final Catalog catalog;
	/**
	 * Contains cache to the {@link ReadOnlyEntityStorageContainerAccessor} by {@link EntitySchema#getName() entity type}.
	 * The cache is created on first request.
	 */
	private Map<String, ReadOnlyEntityStorageContainerAccessor> entitySpecificAccessors;

	@Nonnull
	@Override
	public EntityBodyStoragePart getEntityStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects) {
		final ReadOnlyEntityStorageContainerAccessor delegate = getDelegate(entityType);
		return delegate.getEntityStoragePart(entityType, entityPrimaryKey, expects);
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		final ReadOnlyEntityStorageContainerAccessor delegate = getDelegate(entityType);
		return delegate.getAttributeStoragePart(entityType, entityPrimaryKey);
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale) {
		final ReadOnlyEntityStorageContainerAccessor delegate = getDelegate(entityType);
		return delegate.getAttributeStoragePart(entityType, entityPrimaryKey, locale);
	}

	@Nonnull
	@Override
	public AssociatedDataStoragePart getAssociatedDataStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		final ReadOnlyEntityStorageContainerAccessor delegate = getDelegate(entityType);
		return delegate.getAssociatedDataStoragePart(entityType, entityPrimaryKey, key);
	}

	@Nonnull
	@Override
	public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		final ReadOnlyEntityStorageContainerAccessor delegate = getDelegate(entityType);
		return delegate.getReferencesStoragePart(entityType, entityPrimaryKey);
	}

	@Nonnull
	@Override
	public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		final ReadOnlyEntityStorageContainerAccessor delegate = getDelegate(entityType);
		return delegate.getPriceStoragePart(entityType, entityPrimaryKey);
	}

	@Nonnull
	private ReadOnlyEntityStorageContainerAccessor getDelegate(@Nonnull String entityType) {
		this.entitySpecificAccessors = Optional.ofNullable(this.entitySpecificAccessors).orElseGet(HashMap::new);
		return this.entitySpecificAccessors.computeIfAbsent(
			entityType,
			eType -> {
				final EntityCollection entityCollection = this.catalog.getCollectionForEntityOrThrowException(entityType);
				return new ReadOnlyEntityStorageContainerAccessor(
					this.catalog.getVersion(),
					entityCollection.getDataStoreReader()
				);
			}
		);
	}

}
