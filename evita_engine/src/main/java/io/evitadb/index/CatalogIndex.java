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

package io.evitadb.index;

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.Transaction;
import io.evitadb.index.CatalogIndex.CatalogIndexChanges;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.TransactionalContainerChanges;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayer;
import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.index.attribute.AttributeIndex.verifyLocalizedAttribute;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * This class represents main data structure that keeps all information connected with shared catalog data, that could
 * be used for searching, sorting or another computational task upon these data. There is always only one catalog index
 * present anytime.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CatalogIndex implements Index<CatalogIndexKey>, TransactionalLayerProducer<CatalogIndexChanges, CatalogIndex>, IndexDataStructure {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	protected final TransactionalBoolean dirty;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link UniqueIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, GlobalUniqueIndex> uniqueIndex;
	/**
	 * Version of the entity index that gets increased with each atomic change in the index (incremented by one when
	 * transaction is committed and anything in this index was changed).
	 */
	@Getter private final int version;
	/**
	 * Contains reference to the current catalog instance.
	 * Beware this reference changes with each entity collection exchange during transactional commit.
	 */
	private Catalog catalog;

	public CatalogIndex(@Nonnull Catalog catalog) {
		this.version = 1;
		this.dirty = new TransactionalBoolean();
		this.catalog = catalog;
		this.uniqueIndex = new TransactionalMap<>(new HashMap<>(), GlobalUniqueIndex.class, Function.identity());
	}

	public CatalogIndex(@Nonnull Catalog catalog, int version, @Nonnull Map<AttributeKey, GlobalUniqueIndex> uniqueIndex) {
		this.version = version;
		this.dirty = new TransactionalBoolean();
		this.catalog = catalog;
		this.uniqueIndex = new TransactionalMap<>(uniqueIndex, GlobalUniqueIndex.class, Function.identity());
	}

	/**
	 * Replaces reference to the new catalog object. This needs to be done when transaction is
	 * committed and new GlobalUniqueIndex is created with link to the original transactional Catalog but finally
	 * new {@link Catalog} is created and the new indexes linking old collection needs to be
	 * migrated to new catalog instance.
	 */
	public void updateReferencesTo(@Nonnull Catalog newCatalog) {
		this.catalog = newCatalog;
		this.uniqueIndex.values().forEach(it -> it.updateReferencesTo(newCatalog));
	}

	@Nonnull
	@Override
	public CatalogIndexKey getIndexKey() {
		return CatalogIndexKey.INSTANCE;
	}

	@Nonnull
	@Override
	public Collection<StoragePart> getModifiedStorageParts() {
		final List<StoragePart> dirtyParts = new LinkedList<>();
		if (dirty.isTrue()) {
			dirtyParts.add(createStoragePart());
		}
		for (Entry<AttributeKey, GlobalUniqueIndex> entry : uniqueIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entry.getKey()))
				.ifPresent(dirtyParts::add);
		}
		return dirtyParts;
	}

	/**
	 * Method inserts new unique attribute to the index.
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public void insertUniqueAttribute(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalAttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		final GlobalUniqueIndex theUniqueIndex = this.uniqueIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final GlobalUniqueIndex newUniqueIndex = new GlobalUniqueIndex(lookupKey, attributeSchema.getType(), catalog);
				ofNullable(getTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newUniqueIndex));
				this.dirty.setToTrue();
				return newUniqueIndex;
			}
		);
		theUniqueIndex.registerUniqueKey(value, entitySchema.getName(), locale, recordId);
	}

	/**
	 * Method removes existing unique attribute from the index.
	 *
	 * @throws IllegalArgumentException when passed value doesn't match the unique value associated with the record key
	 */
	public void removeUniqueAttribute(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GlobalAttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		final GlobalUniqueIndex theUniqueIndex = this.uniqueIndex.get(lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute `" + attributeSchema.getName() + "` not found!");
		theUniqueIndex.unregisterUniqueKey(value, entitySchema.getName(), locale, recordId);

		if (theUniqueIndex.isEmpty()) {
			this.uniqueIndex.remove(lookupKey);
			ofNullable(getTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theUniqueIndex));
			this.dirty.setToTrue();
		}
	}

	/**
	 * Returns {@link GlobalUniqueIndex} for passed `attributeName`, if it's present.
	 */
	@Nullable
	public GlobalUniqueIndex getGlobalUniqueIndex(@Nonnull GlobalAttributeSchemaContract attributeSchema, @Nullable Locale locale) {
		final boolean uniqueGloballyWithinLocale = attributeSchema.isUniqueGloballyWithinLocale();
		Assert.isTrue(
			locale != null || !uniqueGloballyWithinLocale,
			() -> new EntityLocaleMissingException(attributeSchema.getName())
		);
		return this.uniqueIndex.get(
			uniqueGloballyWithinLocale ?
				new AttributeKey(attributeSchema.getName(), locale) :
				new AttributeKey(attributeSchema.getName())
		);
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void resetDirty() {
		this.dirty.reset();
		for (GlobalUniqueIndex theUniqueIndex : uniqueIndex.values()) {
			theUniqueIndex.resetDirty();
		}
	}

	@Nullable
	@Override
	public CatalogIndexChanges createLayer() {
		return isTransactionAvailable() ? new CatalogIndexChanges() : null;
	}

	@Nonnull
	@Override
	public CatalogIndex createCopyWithMergedTransactionalMemory(@Nullable CatalogIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty, transaction);
		final CatalogIndex newCatalogIndex = new CatalogIndex(
			catalog, version + (wasDirty ? 1 : 0), transactionalLayer.getStateCopyWithCommittedChanges(uniqueIndex, transaction)
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return newCatalogIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.uniqueIndex.removeLayer(transactionalLayer);

		final CatalogIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #uniqueIndex} transactional maps.
	 */
	public static class CatalogIndexChanges {
		private final TransactionalContainerChanges<TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>>, GlobalUniqueIndex, GlobalUniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull GlobalUniqueIndex uniqueIndex) {
			uniqueIndexChanges.addCreatedItem(uniqueIndex);
		}

		public void addRemovedItem(@Nonnull GlobalUniqueIndex uniqueIndex) {
			uniqueIndexChanges.addRemovedItem(uniqueIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			uniqueIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			uniqueIndexChanges.cleanAll(transactionalLayer);
		}

	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Method creates and verifies validity of attribute key from passed arguments.
	 */
	@Nonnull
	private static AttributeKey createAttributeKey(
		@Nonnull GlobalAttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
		}
		if (attributeSchema.isUniqueGloballyWithinLocale()) {
			return new AttributeKey(attributeSchema.getName(), locale);
		} else {
			return new AttributeKey(attributeSchema.getName());
		}
	}

	/**
	 * Method creates container that is possible to serialize with Kryo and store
	 * into persistent storage.
	 */
	private StoragePart createStoragePart() {
		return new CatalogIndexStoragePart(
			version, uniqueIndex.keySet()
		);
	}

}
