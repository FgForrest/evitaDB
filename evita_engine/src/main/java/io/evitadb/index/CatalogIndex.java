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

package io.evitadb.index;

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogRelatedDataStructure;
import io.evitadb.core.Transaction;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex.CatalogIndexChanges;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
public class CatalogIndex implements
	Index<CatalogIndexKey>, TransactionalLayerProducer<CatalogIndexChanges, CatalogIndex>,
	IndexDataStructure,
	CatalogRelatedDataStructure<CatalogIndex>
{
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Type of the index.
	 */
	@Getter protected final CatalogIndexKey indexKey;
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

	public CatalogIndex(@Nonnull Scope scope) {
		this.version = 1;
		this.indexKey = new CatalogIndexKey(scope);
		this.dirty = new TransactionalBoolean();
		this.uniqueIndex = new TransactionalMap<>(new HashMap<>(), GlobalUniqueIndex.class, Function.identity());
	}

	public CatalogIndex(
		int version,
		@Nonnull CatalogIndexKey indexKey,
		@Nonnull Map<AttributeKey, GlobalUniqueIndex> uniqueIndex
	) {
		this.version = version;
		this.indexKey = indexKey;
		this.dirty = new TransactionalBoolean();
		this.uniqueIndex = new TransactionalMap<>(uniqueIndex, GlobalUniqueIndex.class, Function.identity());
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		Assert.isPremiseValid(this.catalog == null, "Catalog was already attached to this index!");
		this.catalog = catalog;
		for (GlobalUniqueIndex globalUniqueIndex : this.uniqueIndex.values()) {
			globalUniqueIndex.attachToCatalog(null, catalog);
		}
	}

	@Nonnull
	@Override
	public CatalogIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new CatalogIndex(
			this.version,
			this.indexKey,
			this.uniqueIndex
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						entry -> entry.getValue().createCopyForNewCatalogAttachment(catalogState)
					)
				)
		);
	}

	@Override
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		if (this.dirty.isTrue()) {
			trappedChanges.addChangeToStore(createStoragePart());
		}
		for (Entry<AttributeKey, GlobalUniqueIndex> entry : this.uniqueIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entry.getKey()))
				.ifPresent(trappedChanges::addChangeToStore);
		}
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
			createAttributeKey(attributeSchema, allowedLocales, getIndexKey().scope(), locale, value),
			lookupKey -> {
				final GlobalUniqueIndex newUniqueIndex = new GlobalUniqueIndex(
					this.getIndexKey().scope(), lookupKey, attributeSchema.getType()
				);
				newUniqueIndex.attachToCatalog(null, this.catalog);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
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
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, getIndexKey().scope(), locale, value);
		final GlobalUniqueIndex theUniqueIndex = this.uniqueIndex.get(lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute `" + attributeSchema.getName() + "` not found!");
		theUniqueIndex.unregisterUniqueKey(value, entitySchema.getName(), locale, recordId);

		if (theUniqueIndex.isEmpty()) {
			Assert.isPremiseValid(theUniqueIndex == this.uniqueIndex.remove(lookupKey), "Expected unique index was not removed!");
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theUniqueIndex));
			this.dirty.setToTrue();
		}
	}

	/**
	 * Returns {@link GlobalUniqueIndex} for passed `attributeName`, if it's present.
	 */
	@Nullable
	public GlobalUniqueIndex getGlobalUniqueIndex(@Nonnull GlobalAttributeSchemaContract attributeSchema, @Nullable Locale locale) {
		final boolean uniqueGloballyWithinLocale = attributeSchema.isUniqueGloballyWithinLocaleInScope(getIndexKey().scope());
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

	/**
	 * Returns true if index contains no data whatsoever.
	 */
	public boolean isEmpty() {
		return this.uniqueIndex.isEmpty();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void resetDirty() {
		this.dirty.reset();
		for (GlobalUniqueIndex theUniqueIndex : this.uniqueIndex.values()) {
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
	public CatalogIndex createCopyWithMergedTransactionalMemory(@Nullable CatalogIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		final CatalogIndex newCatalogIndex = new CatalogIndex(
			this.version + (wasDirty ? 1 : 0),
			this.indexKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueIndex)
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
		private final TransactionalContainerChanges<Void, GlobalUniqueIndex, GlobalUniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull GlobalUniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addCreatedItem(uniqueIndex);
		}

		public void addRemovedItem(@Nonnull GlobalUniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addRemovedItem(uniqueIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.cleanAll(transactionalLayer);
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
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
		}
		if (attributeSchema.isUniqueGloballyWithinLocaleInScope(scope)) {
			return new AttributeKey(attributeSchema.getName(), locale);
		} else {
			return new AttributeKey(attributeSchema.getName());
		}
	}

	/**
	 * Method creates container that is possible to serialize with Kryo and store
	 * into persistent storage.
	 */
	@Nonnull
	private StoragePart createStoragePart() {
		return new CatalogIndexStoragePart(
			this.version, this.indexKey, this.uniqueIndex.keySet()
		);
	}

}
