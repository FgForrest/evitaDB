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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.Transaction;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.attribute.AttributeIndex.AttributeIndexChanges;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.TransactionalContainerChanges;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalMemory;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.Assert.notNull;
import static io.evitadb.utils.StringUtils.unknownToString;
import static java.util.Optional.ofNullable;

/**
 * Attribute index maintains search look-up indexes for {@link Entity#getAttributeValues()} - i.e. unique, filter
 * and sort index. {@link AttributeIndex} handles all attribute indexes for the {@link Entity#getType()}.
 * 
 * Thread safety:
 * 
 * Histogram supports transaction memory. This means, that the histogram can be updated by multiple writers and also
 * multiple readers can read from its original array without spotting the changes made in transactional access. Each
 * transaction is bound to the same thread and different threads doesn't see changes in another threads.
 * 
 * If no transaction is opened, changes are applied directly to the delegate array. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public class AttributeIndex implements AttributeIndexContract, TransactionalLayerProducer<AttributeIndexChanges, AttributeIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 479979988960202298L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains type of the entity this index belongs to.
	 */
	@Getter private final String entityType;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link UniqueIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, UniqueIndex> uniqueIndex;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link FilterIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, FilterIndex> filterIndex;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link SortIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, SortIndex> sortIndex;

	/**
	 * Method verifies whether the localized attribute refers allowed locale.
	 */
	public static void verifyLocalizedAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		notNull(
			locale,
			"Attribute `" + attributeSchema.getName() + "` is marked as localized. Value " + unknownToString(value) + " is expected to be localized but is not!"
		);
		isTrue(
			allowedLocales.contains(locale),
			"Attribute `" + attributeSchema.getName() + "` is in locale `" + locale + "` that is not among allowed locales for this entity: " + allowedLocales.stream().map(it -> "`" + it.toString() + "`").collect(Collectors.joining(", ")) + "!"
		);
	}

	/**
	 * Method creates and verifies validity of attribute key from passed arguments.
	 */
	@Nonnull
	public static AttributeKey createAttributeKey(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema, allowedLocales, locale, value);
			return new AttributeKey(attributeSchema.getName(), locale);
		} else {
			return new AttributeKey(attributeSchema.getName());
		}
	}

	public AttributeIndex(@Nonnull String entityType) {
		this.entityType = entityType;
		this.uniqueIndex = new TransactionalMap<>(new HashMap<>());
		this.filterIndex = new TransactionalMap<>(new HashMap<>());
		this.sortIndex = new TransactionalMap<>(new HashMap<>());
	}

	public AttributeIndex(@Nonnull String entityType, @Nonnull Map<AttributeKey, UniqueIndex> uniqueIndex, @Nonnull Map<AttributeKey, FilterIndex> filterIndex, @Nonnull Map<AttributeKey, SortIndex> sortIndex) {
		this.entityType = entityType;
		this.uniqueIndex = new TransactionalMap<>(uniqueIndex);
		this.filterIndex = new TransactionalMap<>(filterIndex);
		this.sortIndex = new TransactionalMap<>(sortIndex);
	}

	@Override
	public void insertUniqueAttribute(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Set<Locale> allowedLocales, @Nullable Locale locale, @Nonnull Object value, int recordId) {
		final UniqueIndex theUniqueIndex = this.uniqueIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final UniqueIndex newUniqueIndex = new UniqueIndex(entityType, attributeSchema.getName(), attributeSchema.getType());
				ofNullable(TransactionalMemory.getTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newUniqueIndex));
				return newUniqueIndex;
			}
		);
		theUniqueIndex.registerUniqueKey(value, recordId);
	}

	@Override
	public void removeUniqueAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		final UniqueIndex theUniqueIndex = this.uniqueIndex.get(lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute " + attributeSchema.getName() + " not found!");
		theUniqueIndex.unregisterUniqueKey(value, recordId);

		if (theUniqueIndex.isEmpty()) {
			this.uniqueIndex.remove(lookupKey);
			ofNullable(TransactionalMemory.getTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theUniqueIndex));
		}
	}

	@Override
	public void insertFilterAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		final FilterIndex theFilterIndex = this.filterIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final FilterIndex newFilterIndex = new FilterIndex(attributeSchema.getPlainType());
				ofNullable(TransactionalMemory.getTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newFilterIndex));
				return newFilterIndex;
			}
		);
		theFilterIndex.addRecord(recordId, value);
	}

	@Override
	public void removeFilterAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = this.filterIndex.get(lookupKey);
		notNull(theFilterIndex, "Filter index for " + attributeSchema + " not found!");
		theFilterIndex.removeRecord(recordId, value);

		if (theFilterIndex.isEmpty()) {
			this.filterIndex.remove(lookupKey);
			ofNullable(TransactionalMemory.getTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theFilterIndex));
		}
	}

	@Override
	public void insertSortAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		final SortIndex theSortIndex = this.sortIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final SortIndex newSortIndex = new SortIndex(attributeSchema.getPlainType());
				ofNullable(TransactionalMemory.getTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newSortIndex));
				return newSortIndex;
			}
		);
		theSortIndex.addRecord(value, recordId);
	}

	@Override
	public void removeSortAttribute(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Set<Locale> allowedLocales, @Nullable Locale locale, @Nonnull Object value, int recordId) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		final SortIndex theSortIndex = this.sortIndex.get(lookupKey);
		notNull(theSortIndex, "Sort index for " + attributeSchema + " not found!");
		theSortIndex.removeRecord(value, recordId);

		if (theSortIndex.isEmpty()) {
			this.sortIndex.remove(lookupKey);
			ofNullable(TransactionalMemory.getTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theSortIndex));
		}
	}

	@Override
	@Nonnull
	public Set<AttributeKey> getUniqueIndexes() {
		return this.uniqueIndex.keySet();
	}

	@Override
	@Nullable
	public UniqueIndex getUniqueIndex(@Nonnull AttributeKey lookupKey) {
		return this.uniqueIndex.get(lookupKey);
	}

	@Override
	@Nullable
	public UniqueIndex getUniqueIndex(@Nonnull String attributeName, @Nullable Locale locale) {
		return ofNullable(locale)
			.map(it -> this.uniqueIndex.get(new AttributeKey(attributeName, locale)))
			.orElseGet(() -> this.uniqueIndex.get(new AttributeKey(attributeName)));
	}

	@Override
	@Nonnull
	public Set<AttributeKey> getFilterIndexes() {
		return this.filterIndex.keySet();
	}

	@Override
	@Nullable
	public FilterIndex getFilterIndex(@Nonnull AttributeKey lookupKey) {
		return this.filterIndex.get(lookupKey);
	}

	@Override
	@Nullable
	public FilterIndex getFilterIndex(@Nonnull String attributeName, @Nullable Locale locale) {
		return ofNullable(locale)
			.map(it -> this.filterIndex.get(new AttributeKey(attributeName, locale)))
			.orElseGet(() -> this.filterIndex.get(new AttributeKey(attributeName)));
	}

	@Override
	@Nonnull
	public Set<AttributeKey> getSortIndexes() {
		return this.sortIndex.keySet();
	}

	@Override
	@Nullable
	public SortIndex getSortIndex(@Nonnull AttributeKey lookupKey) {
		return this.sortIndex.get(lookupKey);
	}

	@Override
	@Nullable
	public SortIndex getSortIndex(@Nonnull String attributeName, @Nullable Locale locale) {
		return ofNullable(locale)
			.map(it -> this.sortIndex.get(new AttributeKey(attributeName, locale)))
			.orElseGet(() -> this.sortIndex.get(new AttributeKey(attributeName)));
	}

	@Override
	public boolean isAttributeIndexEmpty() {
		return this.uniqueIndex.isEmpty() && this.filterIndex.isEmpty() && this.sortIndex.isEmpty();
	}

	@Nonnull
	@Override
	public Collection<StoragePart> getModifiedStorageParts(int entityIndexPrimaryKey) {
		final List<StoragePart> dirtyParts = new LinkedList<>();
		for (Entry<AttributeKey, UniqueIndex> entry : uniqueIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey, entry.getKey()))
				.ifPresent(dirtyParts::add);
		}
		for (Entry<AttributeKey, FilterIndex> entry : filterIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey, entry.getKey()))
				.ifPresent(dirtyParts::add);
		}
		for (Entry<AttributeKey, SortIndex> entry : sortIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey, entry.getKey()))
				.ifPresent(dirtyParts::add);
		}
		return dirtyParts;
	}

	@Override
	public void resetDirty() {
		for (UniqueIndex theUniqueIndex : uniqueIndex.values()) {
			theUniqueIndex.resetDirty();
		}
		for (FilterIndex theFilterIndex : filterIndex.values()) {
			theFilterIndex.resetDirty();
		}
		for (SortIndex theSortIndex : sortIndex.values()) {
			theSortIndex.resetDirty();
		}
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nullable
	@Override
	public AttributeIndexChanges createLayer() {
		return TransactionalMemory.isTransactionalMemoryAvailable() ? new AttributeIndexChanges() : null;
	}

	@Nonnull
	@Override
	public AttributeIndex createCopyWithMergedTransactionalMemory(AttributeIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		final AttributeIndex attributeIndex = new AttributeIndex(
			entityType,
			transactionalLayer.getStateCopyWithCommittedChanges(uniqueIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(filterIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(sortIndex, transaction)
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return attributeIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.uniqueIndex.removeLayer(transactionalLayer);
		this.filterIndex.removeLayer(transactionalLayer);
		this.sortIndex.removeLayer(transactionalLayer);
		final AttributeIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * Method creates container for storing any of attribute related indexes from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey, @Nonnull AttributeIndexStorageKey storageKey) {
		final AttributeIndexType indexType = storageKey.indexType();
		if (indexType == AttributeIndexType.UNIQUE) {
			final AttributeKey attribute = storageKey.attribute();
			final UniqueIndex theUniqueIndex = this.uniqueIndex.get(attribute);
			notNull(theUniqueIndex, "Unique index for attribute `" + attribute + "` was not found!");
			return theUniqueIndex.createStoragePart(entityIndexPrimaryKey, attribute);
		} else if (indexType == AttributeIndexType.FILTER) {
			final AttributeKey attribute = storageKey.attribute();
			final FilterIndex theFilterIndex = this.filterIndex.get(attribute);
			notNull(theFilterIndex, "Filter index for attribute `" + attribute + "` was not found!");
			return theFilterIndex.createStoragePart(entityIndexPrimaryKey, attribute);
		} else if (indexType == AttributeIndexType.SORT) {
			final AttributeKey attribute = storageKey.attribute();
			final SortIndex theSortIndex = this.sortIndex.get(attribute);
			notNull(theSortIndex, "Sort index for attribute `" + attribute + "` was not found!");
			return theSortIndex.createStoragePart(entityIndexPrimaryKey, attribute);
		} else {
			throw new EvitaInternalError("Cannot handle attribute storage part key of type `" + indexType + "`");
		}
	}

	/**
	 * This class collects changes in {@link #uniqueIndex}, {@link #filterIndex} and {@link #sortIndex} transactional
	 * maps.
	 */
	public static class AttributeIndexChanges {
		private final TransactionalContainerChanges<TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>>, UniqueIndex, UniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<Void, FilterIndex, FilterIndex> filterIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<SortIndexChanges, SortIndex, SortIndex> sortIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull UniqueIndex uniqueIndex) {
			uniqueIndexChanges.addCreatedItem(uniqueIndex);
		}

		public void addRemovedItem(@Nonnull UniqueIndex uniqueIndex) {
			uniqueIndexChanges.addRemovedItem(uniqueIndex);
		}

		public void addCreatedItem(@Nonnull FilterIndex filterIndex) {
			filterIndexChanges.addCreatedItem(filterIndex);
		}

		public void addRemovedItem(@Nonnull FilterIndex filterIndex) {
			filterIndexChanges.addRemovedItem(filterIndex);
		}

		public void addCreatedItem(@Nonnull SortIndex sortIndex) {
			sortIndexChanges.addCreatedItem(sortIndex);
		}

		public void addRemovedItem(@Nonnull SortIndex sortIndex) {
			sortIndexChanges.addRemovedItem(sortIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			uniqueIndexChanges.clean(transactionalLayer);
			filterIndexChanges.clean(transactionalLayer);
			sortIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			uniqueIndexChanges.cleanAll(transactionalLayer);
			filterIndexChanges.cleanAll(transactionalLayer);
			sortIndexChanges.cleanAll(transactionalLayer);
		}

	}

}
