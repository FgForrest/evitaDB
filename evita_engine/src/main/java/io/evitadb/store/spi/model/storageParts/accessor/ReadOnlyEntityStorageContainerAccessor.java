/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.DataStoreTxMemoryBuffer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.transactionalMemory.diff.DataSourceChanges;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * This accessor serves as a cached accessor to entity containers / storage parts. The accessor is not thread safe and
 * is meant to be instantiated when paginated entity result is retrieved and ad-hoc data needs to be read from
 * the persistent storage. All read containers are kept cached in internal data structures so repeated reads of the same
 * container type for the same entity primary key don't involve an I/O operation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
public final class ReadOnlyEntityStorageContainerAccessor extends AbstractEntityStorageContainerAccessor {
	/**
	 * Cache for the {@link EntityBodyStoragePart} by the entity primary key.
	 */
	private IntObjectMap<EntityBodyStoragePart> entityContainer;
	/**
	 * Cache for the {@link AttributesStoragePart} by the entity primary key.
	 */
	private IntObjectMap<AttributesStoragePart> globalAttributesStorageContainer;
	/**
	 * Cache for the localized {@link AttributesStoragePart} by the entity primary key.
	 */
	private IntObjectMap<Map<Locale, AttributesStoragePart>> languageSpecificAttributesContainer;
	/**
	 * Cache for the {@link AssociatedDataStoragePart} by the entity primary key.
	 */
	private IntObjectMap<Map<AssociatedDataKey, AssociatedDataStoragePart>> associatedDataContainers;
	/**
	 * Cache for the {@link PricesStoragePart} by the entity primary key.
	 */
	private IntObjectMap<PricesStoragePart> pricesContainer;
	/**
	 * Cache for the {@link ReferencesStoragePart} by the entity primary key.
	 */
	private IntObjectMap<ReferencesStoragePart> referencesStorageContainer;

	public ReadOnlyEntityStorageContainerAccessor(
		@Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer,
		@Nonnull Supplier<EntitySchema> schemaAccessor) {
		super(storageContainerBuffer, schemaAccessor);
	}

	@Nullable
	@Override
	protected EntityBodyStoragePart getCachedEntityStorageContainer(int entityPrimaryKey) {
		return getEntityStorageContainerHolder().get(entityPrimaryKey);
	}

	@Nonnull
	@Override
	protected EntityBodyStoragePart cacheEntityStorageContainer(int entityPrimaryKey, @Nonnull EntityBodyStoragePart entityStorageContainer) {
		getEntityStorageContainerHolder().put(entityPrimaryKey, entityStorageContainer);
		return entityStorageContainer;
	}

	@Nullable
	@Override
	protected AttributesStoragePart getCachedAttributeStorageContainer(int entityPrimaryKey) {
		return getAttributesStorageContainerHolder().get(entityPrimaryKey);
	}

	@Nonnull
	@Override
	protected AttributesStoragePart cacheAttributeStorageContainer(int entityPrimaryKey, @Nonnull AttributesStoragePart attributesStorageContainer) {
		getAttributesStorageContainerHolder().put(entityPrimaryKey, attributesStorageContainer);
		return attributesStorageContainer;
	}

	@Nonnull
	@Override
	protected Map<Locale, AttributesStoragePart> getOrCreateCachedLocalizedAttributesStorageContainer(int entityPrimaryKey) {
		final IntObjectMap<Map<Locale, AttributesStoragePart>> holder = ofNullable(this.languageSpecificAttributesContainer).orElseGet(() -> {
			this.languageSpecificAttributesContainer = new IntObjectHashMap<>();
			return this.languageSpecificAttributesContainer;
		});
		return ofNullable(holder.get(entityPrimaryKey))
			.orElseGet(() -> {
				final HashMap<Locale, AttributesStoragePart> localeSpecificMap = new HashMap<>();
				holder.put(entityPrimaryKey, localeSpecificMap);
				return localeSpecificMap;
			});
	}

	@Nonnull
	@Override
	protected Map<AssociatedDataKey, AssociatedDataStoragePart> getOrCreateCachedAssociatedDataStorageContainer(int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		final IntObjectMap<Map<AssociatedDataKey, AssociatedDataStoragePart>> holder = ofNullable(this.associatedDataContainers).orElseGet(() -> {
			this.associatedDataContainers = new IntObjectHashMap<>();
			return this.associatedDataContainers;
		});
		return ofNullable(holder.get(entityPrimaryKey))
			.orElseGet(() -> {
				final HashMap<AssociatedDataKey, AssociatedDataStoragePart> associatedDataMap = new HashMap<>();
				holder.put(entityPrimaryKey, associatedDataMap);
				return associatedDataMap;
			});
	}

	@Nonnull
	@Override
	protected ReferencesStoragePart getCachedReferenceStorageContainer(int entityPrimaryKey) {
		return getReferencesStorageContainerHolder().get(entityPrimaryKey);
	}

	@Nonnull
	@Override
	protected ReferencesStoragePart cacheReferencesStorageContainer(int entityPrimaryKey, @Nonnull ReferencesStoragePart referencesStorageContainer) {
		getReferencesStorageContainerHolder().put(entityPrimaryKey, referencesStorageContainer);
		return referencesStorageContainer;
	}

	@Nonnull
	@Override
	protected PricesStoragePart getCachedPricesStorageContainer(int entityPrimaryKey) {
		return getPricesStorageContainerHolder().get(entityPrimaryKey);
	}

	@Nonnull
	@Override
	protected PricesStoragePart cachePricesStorageContainer(int entityPrimaryKey, @Nonnull PricesStoragePart pricesStorageContainer) {
		getPricesStorageContainerHolder().put(entityPrimaryKey, pricesStorageContainer);
		return pricesStorageContainer;
	}

	/*
		PRIVATE METHODS
	 */

	private IntObjectMap<EntityBodyStoragePart> getEntityStorageContainerHolder() {
		return ofNullable(this.entityContainer).orElseGet(() -> {
			this.entityContainer = new IntObjectHashMap<>();
			return this.entityContainer;
		});
	}

	private IntObjectMap<AttributesStoragePart> getAttributesStorageContainerHolder() {
		return ofNullable(this.globalAttributesStorageContainer).orElseGet(() -> {
			this.globalAttributesStorageContainer = new IntObjectHashMap<>();
			return this.globalAttributesStorageContainer;
		});
	}

	private IntObjectMap<PricesStoragePart> getPricesStorageContainerHolder() {
		return ofNullable(this.pricesContainer).orElseGet(() -> {
			this.pricesContainer = new IntObjectHashMap<>();
			return this.pricesContainer;
		});
	}

	private IntObjectMap<ReferencesStoragePart> getReferencesStorageContainerHolder() {
		return ofNullable(this.referencesStorageContainer).orElseGet(() -> {
			this.referencesStorageContainer = new IntObjectHashMap<>();
			return this.referencesStorageContainer;
		});
	}

}
