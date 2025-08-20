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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.Transaction;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.attribute.AttributeIndex.AttributeIndexChanges;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.core.Transaction.isTransactionAvailable;
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
public class AttributeIndex implements AttributeIndexContract,
	TransactionalLayerProducer<AttributeIndexChanges, AttributeIndex>,
	IndexDataStructure,
	Serializable
{
	@Serial private static final long serialVersionUID = 479979988960202298L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains type of the entity this index belongs to.
	 */
	@Getter private final String entityType;
	/**
	 * Reference key (discriminator) of the {@link ReducedEntityIndex} this index belongs to. Or null if this index
	 * is part of the global {@link GlobalEntityIndex}.
	 */
	@Nullable private final ReferenceKey referenceKey;
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
	 * This transactional map (index) contains for each attribute single instance of {@link ChainIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, ChainIndex> chainIndex;

	/**
	 * Method verifies whether the localized attribute refers allowed locale.
	 */
	public static void verifyLocalizedAttribute(
		@Nonnull String attributeName,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		notNull(
			locale,
			"Attribute `" + attributeName + "` is marked as localized. Value " + unknownToString(value) + " is expected to be localized but is not!"
		);
		isTrue(
			allowedLocales.contains(locale),
			"Attribute `" + attributeName + "` is in locale `" + locale + "` that is not among allowed locales for this entity: " + allowedLocales.stream().map(it -> "`" + it.toString() + "`").collect(Collectors.joining(", ")) + "!"
		);
	}

	/**
	 * Method creates an attribute key based on the given attribute schema, allowed locales, locale, and value.
	 * If the attribute schema is localized, it verifies whether the provided locale is allowed and returns
	 * a new AttributeKey object with the attribute name and locale. If the attribute schema is not localized,
	 * it returns a new AttributeKey object with only the attribute name.
	 *
	 * @param attributeSchema The attribute schema contract.
	 * @param allowedLocales  The set of allowed locales for the entity.
	 * @param locale          The locale to be checked against the allowed locales.
	 * @param value           The value of the attribute.
	 * @return An AttributeKey object with the attribute name and optional locale.
	 */
	@Nonnull
	public static AttributeKey createAttributeKey(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
			return new AttributeKey(attributeSchema.getName(), locale);
		} else {
			return new AttributeKey(attributeSchema.getName());
		}
	}

	/**
	 * Method creates and verifies validity of attribute key from passed arguments.
	 * This method can be used only for unique indexes and differs from {@link #createAttributeKey(AttributeSchemaContract, Set, Locale, Object)}
	 * in the sense that it creates locale specific key only if {@link AttributeSchemaContract#isUniqueWithinLocale()} is true.
	 *
	 * @param attributeSchema The attribute schema contract.
	 * @param allowedLocales  The set of allowed locales.
	 * @param locale          The locale (can be null).
	 * @param value           The attribute value.
	 * @return An AttributeKey object with the attribute name and optional locale.
	 */
	@Nonnull
	public static AttributeKey createUniqueAttributeKey(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Object value
	) {
		if (attributeSchema.isLocalized()) {
			verifyLocalizedAttribute(attributeSchema.getName(), allowedLocales, locale, value);
		}
		if (attributeSchema.isUniqueWithinLocaleInScope(scope)) {
			return new AttributeKey(attributeSchema.getName(), locale);
		} else {
			return new AttributeKey(attributeSchema.getName());
		}
	}

	public AttributeIndex(@Nonnull String entityType) {
		this(entityType, null);
	}

	public AttributeIndex(@Nonnull String entityType, @Nullable ReferenceKey referenceKey) {
		this.entityType = entityType;
		this.referenceKey = referenceKey;
		this.uniqueIndex = new TransactionalMap<>(new HashMap<>(), UniqueIndex.class, Function.identity());
		this.filterIndex = new TransactionalMap<>(new HashMap<>(), FilterIndex.class, Function.identity());
		this.sortIndex = new TransactionalMap<>(new HashMap<>(), SortIndex.class, Function.identity());
		this.chainIndex = new TransactionalMap<>(new HashMap<>(), ChainIndex.class, Function.identity());
	}

	public AttributeIndex(
		@Nonnull String entityType,
		@Nullable ReferenceKey referenceKey,
		@Nonnull Map<AttributeKey, UniqueIndex> uniqueIndex,
		@Nonnull Map<AttributeKey, FilterIndex> filterIndex,
		@Nonnull Map<AttributeKey, SortIndex> sortIndex,
		@Nonnull Map<AttributeKey, ChainIndex> chainIndex
	) {
		this.entityType = entityType;
		this.referenceKey = referenceKey;
		this.uniqueIndex = new TransactionalMap<>(uniqueIndex, UniqueIndex.class, Function.identity());
		this.filterIndex = new TransactionalMap<>(filterIndex, FilterIndex.class, Function.identity());
		this.sortIndex = new TransactionalMap<>(sortIndex, SortIndex.class, Function.identity());
		this.chainIndex = new TransactionalMap<>(chainIndex, ChainIndex.class, Function.identity());
	}

	@Override
	public void insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final UniqueIndex theUniqueIndex = this.uniqueIndex.computeIfAbsent(
			createUniqueAttributeKey(attributeSchema, allowedLocales, scope, locale, value),
			lookupKey -> {
				final UniqueIndex newUniqueIndex = new UniqueIndex(
					entityType,
					lookupKey,
					attributeSchema.getType()
				);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newUniqueIndex));
				return newUniqueIndex;
			}
		);
		theUniqueIndex.registerUniqueKey(value, recordId);
	}

	@Override
	public void removeUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final AttributeKey lookupKey = createUniqueAttributeKey(attributeSchema, allowedLocales, scope, locale, value);
		final UniqueIndex theUniqueIndex = this.uniqueIndex.get(lookupKey);
		notNull(theUniqueIndex, "Unique index for attribute `" + attributeSchema.getName() + "` not found!");
		theUniqueIndex.unregisterUniqueKey(value, recordId);

		if (theUniqueIndex.isEmpty()) {
			this.uniqueIndex.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theUniqueIndex));
		}
	}

	@Override
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final FilterIndex theFilterIndex = this.filterIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final FilterIndex newFilterIndex = new FilterIndex(lookupKey, attributeSchema.getPlainType());
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newFilterIndex));
				return newFilterIndex;
			}
		);
		theFilterIndex.addRecord(recordId, value);
	}

	@Override
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = this.filterIndex.get(lookupKey);
		notNull(theFilterIndex, "Filter index for `" + attributeSchema.getName() + "` not found!");
		theFilterIndex.removeRecord(recordId, value);

		if (theFilterIndex.isEmpty()) {
			this.filterIndex.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theFilterIndex));
		}
	}

	@Override
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		final FilterIndex theFilterIndex = this.filterIndex.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final FilterIndex newFilterIndex = new FilterIndex(lookupKey, attributeSchema.getPlainType());
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newFilterIndex));
				return newFilterIndex;
			}
		);
		theFilterIndex.addRecordDelta(recordId, value);
	}

	@Override
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		final FilterIndex theFilterIndex = this.filterIndex.get(lookupKey);
		notNull(theFilterIndex, "Filter index for `" + attributeSchema.getName() + "` not found!");
		theFilterIndex.removeRecordDelta(recordId, value);

		if (theFilterIndex.isEmpty()) {
			this.filterIndex.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(theFilterIndex));
		}
	}

	@Override
	public void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final AttributeKey attributeKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
		if (value instanceof Predecessor predecessor) {
			final ChainIndex theSortIndex = getOrCreateChainIndex(attributeKey);
			theSortIndex.upsertPredecessor(predecessor, recordId);
		} else if (value instanceof ReferencedEntityPredecessor referencedEntityPredecessor) {
			final ChainIndex theSortIndex = getOrCreateChainIndex(attributeKey);
			theSortIndex.upsertPredecessor(referencedEntityPredecessor, recordId);
		} else {
			final SortIndex theSortIndex = this.sortIndex.computeIfAbsent(
				attributeKey,
				lookupKey -> {
					final SortIndex newSortIndex = new SortIndex(attributeSchema.getPlainType(), this.referenceKey, lookupKey);
					ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
						.ifPresent(it -> it.addCreatedItem(newSortIndex));
					return newSortIndex;
				}
			);
			theSortIndex.addRecord(value, recordId);
		}
	}

	@Override
	public void removeSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final AttributeKey lookupKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);

		if (Predecessor.class.equals(attributeSchema.getType()) || ReferencedEntityPredecessor.class.equals(attributeSchema.getType())) {
			final ChainIndex theChainIndex = this.chainIndex.get(lookupKey);
			notNull(theChainIndex, "Chain index for attribute `" + attributeSchema.getName() + "` not found!");
			theChainIndex.removePredecessor(recordId);

			if (theChainIndex.isEmpty()) {
				this.chainIndex.remove(lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addRemovedItem(theChainIndex));
			}
		} else {
			final SortIndex theSortIndex = this.sortIndex.get(lookupKey);
			notNull(theSortIndex, "Sort index for attribute `" + attributeSchema.getName() + "` not found!");
			theSortIndex.removeRecord(value, recordId);

			if (theSortIndex.isEmpty()) {
				this.sortIndex.remove(lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addRemovedItem(theSortIndex));
			}
		}
	}

	@Override
	public void insertSortAttributeCompound(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		final AttributeKey attributeKey = locale == null ?
			new AttributeKey(compoundSchemaContract.getName()) :
			new AttributeKey(compoundSchemaContract.getName(), locale);
		final SortIndex theSortIndex = this.sortIndex.computeIfAbsent(
			attributeKey,
			lookupKey -> {
				final SortIndex newSortIndex = new SortIndex(
					compoundSchemaContract.getAttributeElements()
						.stream()
						.map(it -> new ComparatorSource(
							attributeTypeProvider.apply(it.attributeName()),
							it.direction(),
							it.behaviour()
						))
						.toArray(ComparatorSource[]::new),
					this.referenceKey,
					lookupKey
				);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newSortIndex));
				return newSortIndex;
			}
		);
		theSortIndex.addRecord(value, recordId);
	}

	@Override
	public void removeSortAttributeCompound(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		final AttributeKey lookupKey = locale == null ?
			new AttributeKey(compoundSchemaContract.getName()) :
			new AttributeKey(compoundSchemaContract.getName(), locale);

		final SortIndex theSortIndex = this.sortIndex.get(lookupKey);
		notNull(theSortIndex, "Sort index for sortable attribute compound `" + compoundSchemaContract.getName() + "` not found!");
		theSortIndex.removeRecord(value, recordId);

		if (theSortIndex.isEmpty()) {
			this.sortIndex.remove(lookupKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
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
	public UniqueIndex getUniqueIndex(@Nonnull AttributeSchemaContract attributeSchema, @Nonnull Scope scope, @Nullable Locale locale) {
		final boolean uniqueWithinLocale = attributeSchema.isUniqueWithinLocaleInScope(scope);
		Assert.isTrue(
			locale != null || !uniqueWithinLocale,
			() -> new EntityLocaleMissingException(attributeSchema.getName())
		);
		final AttributeKey attributeKey = uniqueWithinLocale ?
			new AttributeKey(attributeSchema.getName(), locale) :
			new AttributeKey(attributeSchema.getName());
		return this.uniqueIndex.get(attributeKey);
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

	@Nonnull
	@Override
	public Set<AttributeKey> getChainIndexes() {
		return this.chainIndex.keySet();
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(@Nonnull AttributeKey lookupKey) {
		return this.chainIndex.get(lookupKey);
	}

	@Nullable
	@Override
	public ChainIndex getChainIndex(@Nonnull String attributeName, @Nullable Locale locale) {
		return ofNullable(locale)
			.map(it -> this.chainIndex.get(new AttributeKey(attributeName, locale)))
			.orElseGet(() -> this.chainIndex.get(new AttributeKey(attributeName)));
	}

	@Override
	public boolean isAttributeIndexEmpty() {
		return this.uniqueIndex.isEmpty() && this.filterIndex.isEmpty() &&
			this.sortIndex.isEmpty() && this.chainIndex.isEmpty();
	}
	@Override
	public void getModifiedStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges trappedChanges) {
		for (Entry<AttributeKey, UniqueIndex> entry : this.uniqueIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
		for (Entry<AttributeKey, FilterIndex> entry : this.filterIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
		for (Entry<AttributeKey, SortIndex> entry : this.sortIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
		for (Entry<AttributeKey, ChainIndex> entry : this.chainIndex.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(entityIndexPrimaryKey))
				.ifPresent(trappedChanges::addChangeToStore);
		}
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
		for (ChainIndex theChainIndex : chainIndex.values()) {
			theChainIndex.resetDirty();
		}
	}

	@Nullable
	@Override
	public AttributeIndexChanges createLayer() {
		return isTransactionAvailable() ? new AttributeIndexChanges() : null;
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.uniqueIndex.removeLayer(transactionalLayer);
		this.filterIndex.removeLayer(transactionalLayer);
		this.sortIndex.removeLayer(transactionalLayer);
		this.chainIndex.removeLayer(transactionalLayer);
		final AttributeIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	@Nonnull
	@Override
	public AttributeIndex createCopyWithMergedTransactionalMemory(AttributeIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final AttributeIndex attributeIndex = new AttributeIndex(
			this.entityType,
			this.referenceKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.filterIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.sortIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.chainIndex)
		);
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return attributeIndex;
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
			return theUniqueIndex.createStoragePart(entityIndexPrimaryKey);
		} else if (indexType == AttributeIndexType.FILTER) {
			final AttributeKey attribute = storageKey.attribute();
			final FilterIndex theFilterIndex = this.filterIndex.get(attribute);
			notNull(theFilterIndex, "Filter index for attribute `" + attribute + "` was not found!");
			return theFilterIndex.createStoragePart(entityIndexPrimaryKey);
		} else if (indexType == AttributeIndexType.SORT) {
			final AttributeKey attribute = storageKey.attribute();
			final SortIndex theSortIndex = this.sortIndex.get(attribute);
			notNull(theSortIndex, "Sort index for attribute `" + attribute + "` was not found!");
			return theSortIndex.createStoragePart(entityIndexPrimaryKey);
		} else if (indexType == AttributeIndexType.CHAIN) {
			final AttributeKey attribute = storageKey.attribute();
			final ChainIndex theChainIndex = this.chainIndex.get(attribute);
			notNull(theChainIndex, "Chain index for attribute `" + attribute + "` was not found!");
			return theChainIndex.createStoragePart(entityIndexPrimaryKey);
		} else {
			throw new GenericEvitaInternalError("Cannot handle attribute storage part key of type `" + indexType + "`");
		}
	}

	/**
	 * Method retrieves or creates a ChainIndex based on the provided AttributeKey.
	 * If it does not exist in the chainIndex map, it creates and adds a new one.
	 *
	 * @param attributeKey The attribute key used for lookup or creation of the ChainIndex.
	 * @return The existing or newly created ChainIndex.
	 */
	@Nonnull
	private ChainIndex getOrCreateChainIndex(@Nonnull AttributeKey attributeKey) {
		return this.chainIndex.computeIfAbsent(
			attributeKey,
			lookupKey -> {
				final ChainIndex newSortIndex = new ChainIndex(this.referenceKey, lookupKey);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newSortIndex));
				return newSortIndex;
			}
		);
	}

	/**
	 * This class collects changes in {@link #uniqueIndex}, {@link #filterIndex}, {@link #sortIndex} and {@link #chainIndex}
	 * transactional maps.
	 */
	public static class AttributeIndexChanges {
		private final TransactionalContainerChanges<TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>>, UniqueIndex, UniqueIndex> uniqueIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<Void, FilterIndex, FilterIndex> filterIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<SortIndexChanges, SortIndex, SortIndex> sortIndexChanges = new TransactionalContainerChanges<>();
		private final TransactionalContainerChanges<ChainIndexChanges, ChainIndex, ChainIndex> chainIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull UniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addCreatedItem(uniqueIndex);
		}

		public void addRemovedItem(@Nonnull UniqueIndex uniqueIndex) {
			this.uniqueIndexChanges.addRemovedItem(uniqueIndex);
		}

		public void addCreatedItem(@Nonnull FilterIndex filterIndex) {
			this.filterIndexChanges.addCreatedItem(filterIndex);
		}

		public void addRemovedItem(@Nonnull FilterIndex filterIndex) {
			this.filterIndexChanges.addRemovedItem(filterIndex);
		}

		public void addCreatedItem(@Nonnull SortIndex sortIndex) {
			this.sortIndexChanges.addCreatedItem(sortIndex);
		}

		public void addRemovedItem(@Nonnull SortIndex sortIndex) {
			this.sortIndexChanges.addRemovedItem(sortIndex);
		}

		public void addCreatedItem(@Nonnull ChainIndex chainIndex) {
			this.chainIndexChanges.addCreatedItem(chainIndex);
		}

		public void addRemovedItem(@Nonnull ChainIndex chainIndex) {
			this.chainIndexChanges.addRemovedItem(chainIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.clean(transactionalLayer);
			this.filterIndexChanges.clean(transactionalLayer);
			this.sortIndexChanges.clean(transactionalLayer);
			this.chainIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.uniqueIndexChanges.cleanAll(transactionalLayer);
			this.filterIndexChanges.cleanAll(transactionalLayer);
			this.sortIndexChanges.cleanAll(transactionalLayer);
			this.chainIndexChanges.cleanAll(transactionalLayer);
		}

	}

}
