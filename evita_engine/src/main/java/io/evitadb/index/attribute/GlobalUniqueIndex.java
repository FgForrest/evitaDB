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

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Transaction;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.transactionalMemory.TransactionalContainerChanges;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.index.attribute.UniqueIndex.verifyValue;
import static io.evitadb.index.attribute.UniqueIndex.verifyValueArray;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * Unique index maintains information about single unique attribute - its value to record id relation.
 * It protects duplicate unique attribute insertion and allows to easily translate unique attribute value to record id
 * that occupies it.
 *
 * It uses simple {@link HashMap} data structure to keep the data. This means that look-ups are retrieved with O(1)
 * complexity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class GlobalUniqueIndex implements TransactionalLayerProducer<TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>>, GlobalUniqueIndex>, IndexDataStructure {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains name of the attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Keeps the unique value to record id mappings. Fairly large HashMap is expected here.
	 */
	@Nonnull private final TransactionalMap<Serializable, EntityWithTypeTuple> uniqueValueToEntityTuple;
	/**
	 * Keeps internal index where each locale has assigned its own unique integer primary key.
	 * These primary keys are assigned internally and don't leave this unique index, but are serialized and deserialized
	 * along with it.
	 */
	@Nonnull private final TransactionalMap<Locale, Integer> localeToIdIndex;
	/**
	 * Keeps reverted index of {@link #localeToIdIndex}.
	 */
	@Nonnull private final TransactionalMap<Integer, Locale> idToLocaleIndex;
	/**
	 * Keeps internal sequence of already assigned primary keys to locales.
	 * The sequence starts with the highest assigned id found in {@link #localeToIdIndex} in constructor.
	 */
	private final AtomicInteger localePkSequence = new AtomicInteger();
	/**
	 * Contains reference to the current catalog instance.
	 * Beware this reference changes with each entity collection exchange during transactional commit.
	 * The reference is used to translate {@link EntityCollection#getEntityType()} to {@link EntityCollection#getEntityTypePrimaryKey()}
	 * and vice versa. We want to use short int ids in {@link EntityWithTypeTuple} so that we save a few bytes for object
	 * pointer.
	 */
	private Catalog catalog;
	private final Map<Integer, String> primaryKeyToEntityType = new ConcurrentHashMap<>();
	private final Map<String, Integer> entityTypeToPk = new ConcurrentHashMap<>();

	public GlobalUniqueIndex(@Nonnull AttributeKey attributeKey, @Nonnull Class<? extends Serializable> attributeType, @Nonnull Catalog catalog) {
		this.dirty = new TransactionalBoolean();
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.catalog = catalog;
		this.uniqueValueToEntityTuple = new TransactionalMap<>(new HashMap<>());
		this.localeToIdIndex = new TransactionalMap<>(new HashMap<>());
		this.idToLocaleIndex = new TransactionalMap<>(new HashMap<>());
	}

	public GlobalUniqueIndex(
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull Catalog catalog,
		@Nonnull Map<Serializable, EntityWithTypeTuple> uniqueValueToEntityTuple,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.catalog = catalog;
		this.uniqueValueToEntityTuple = new TransactionalMap<>(uniqueValueToEntityTuple);
		this.idToLocaleIndex = new TransactionalMap<>(localeIndex);
		this.localeToIdIndex = new TransactionalMap<>(
			localeIndex.entrySet().stream()
				.peek(it -> this.localePkSequence.getAndUpdate(currentValue -> currentValue < it.getKey() ? it.getKey() : currentValue))
				.collect(
					Collectors.toMap(
						Entry::getValue,
						Entry::getKey
					)
				)
		);
	}

	/**
	 * Replaces reference to the new catalog object. This needs to be done when transaction is
	 * committed and new GlobalUniqueIndex is created with link to the original transactional Catalog but finally
	 * new {@link Catalog} is created and the new indexes linking old collection needs to be
	 * migrated to new catalog instance.
	 */
	public void updateReferencesTo(@Nonnull Catalog newCatalog) {
		this.catalog = newCatalog;
	}

	/**
	 * Registers new record id to a single unique value.
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public void registerUniqueKey(@Nonnull Object value, @Nonnull String entityType, @Nullable Locale locale, int recordId) {
		final int classifierId = fromClassifier(entityType);
		final int localeId = fromLocale(locale);
		registerUniqueKeyValue(value, new EntityWithTypeTuple(classifierId, recordId, localeId));
	}

	/**
	 * Unregisters new record id from a single unique value.
	 *
	 * @return removed record id relation
	 */
	@Nullable
	public EntityReferenceWithLocale unregisterUniqueKey(@Nonnull Object value, @Nonnull String entityType, @Nullable Locale locale, int recordId) {
		final int classifierId = fromClassifier(entityType);
		final int localeId = fromLocale(locale);
		return unregisterUniqueKeyValue(value, new EntityWithTypeTuple(classifierId, recordId, localeId)) == null ?
			null : new EntityReferenceWithLocale(entityType, recordId, locale);
	}

	/**
	 * Returns record id by its unique value.
	 */
	@Nullable
	public EntityReferenceWithLocale getEntityReferenceByUniqueValue(@Nonnull Serializable value) {
		return ofNullable(this.uniqueValueToEntityTuple.get(value))
			.map(it -> new EntityReferenceWithLocale(toClassifier(it.entityType()), it.entityPrimaryKey(), toLocale(it.locale())))
			.orElse(null);
	}

	/**
	 * Returns number of unique keys in this index.
	 */
	public int size() {
		return uniqueValueToEntityTuple.size();
	}

	/**
	 * Returns true if index is empty.
	 */
	public boolean isEmpty() {
		return this.uniqueValueToEntityTuple.isEmpty();
	}

	/**
	 * Method creates container for storing unique index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(@Nonnull AttributeKey attribute) {
		if (this.dirty.isTrue()) {
			return new GlobalUniqueIndexStoragePart(attribute, type, uniqueValueToEntityTuple, idToLocaleIndex);
		} else {
			return null;
		}
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Nullable
	@Override
	public TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>> createLayer() {
		return isTransactionAvailable() ? new TransactionalContainerChanges<>() : null;
	}

	@Nonnull
	@Override
	public GlobalUniqueIndex createCopyWithMergedTransactionalMemory(@Nullable TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		final GlobalUniqueIndex uniqueKeyIndex = new GlobalUniqueIndex(
			attributeKey, type, catalog,
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueValueToEntityTuple, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.idToLocaleIndex, transaction)
		);
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty, transaction);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.localeToIdIndex);
		// we can safely throw away dirty flag now
		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return uniqueKeyIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.uniqueValueToEntityTuple.removeLayer(transactionalLayer);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Returns index of unique values mapped to record ids.
	 */
	@Nonnull
	Map<Serializable, EntityWithTypeTuple> getUniqueValueToEntityReference() {
		return Collections.unmodifiableMap(this.uniqueValueToEntityTuple);
	}

	/**
	 * Returns index of locale ids.
	 */
	@Nonnull
	Map<Integer, Locale> getLocaleIndex() {
		return Collections.unmodifiableMap(this.idToLocaleIndex);
	}

	/**
	 * Returns array of sorted references maintained by this index. Method is extremely slow - use in tests only!
	 */
	@Nonnull
	EntityReference[] getEntityReferences() {
		return uniqueValueToEntityTuple
			.values()
			.stream()
			.map(it -> new EntityReference(toClassifier(it.entityType()), it.entityPrimaryKey()))
			.sorted()
			.toArray(EntityReference[]::new);
	}

	@SuppressWarnings("unchecked")
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull Object key, @Nonnull EntityWithTypeTuple record) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			verifyValueArray(key);
			// first verify removed data without modifications
			for (Object valueItem : valueArray) {
				final T theValueItem = (T) valueItem;
				final EntityWithTypeTuple existingRecordId = this.uniqueValueToEntityTuple.get(theValueItem);
				assertUniqueKeyIsFree(theValueItem, record, existingRecordId);
			}
			// now perform alteration
			for (Object valueItem : valueArray) {
				registerUniqueKeyValue(valueItem, record);
			}
		} else {
			verifyValue(key);
			registerUniqueKeyValue((T) key, record);
		}
		this.dirty.setToTrue();
	}

	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull T key, @Nonnull EntityWithTypeTuple record) {
		final EntityWithTypeTuple existingRecordId = uniqueValueToEntityTuple.get(key);
		assertUniqueKeyIsFree(key, record, existingRecordId);
		this.uniqueValueToEntityTuple.put(key, record);
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull Object key, @Nonnull EntityWithTypeTuple expectedRecord) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			verifyValueArray(key);
			// first verify removed data without modifications
			for (Object valueItem : valueArray) {
				final T theValueItem = (T) valueItem;
				final EntityWithTypeTuple existingRecord = this.uniqueValueToEntityTuple.get(theValueItem);
				assertUniqueKeyOwnership(theValueItem, expectedRecord, existingRecord);
			}
			// now perform alteration
			for (Object valueItem : valueArray) {
				unregisterUniqueKeyValue((T) valueItem, expectedRecord);
			}
			this.dirty.setToTrue();
			return null;
		} else {
			verifyValue(key);
			final EntityWithTypeTuple originalValue = unregisterUniqueKeyValue((T) key, expectedRecord);
			this.dirty.setToTrue();
			return originalValue;
		}
	}

	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull T key, EntityWithTypeTuple expectedRecordId) {
		final EntityWithTypeTuple existingRecordId = this.uniqueValueToEntityTuple.remove(key);
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		return existingRecordId;
	}

	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, EntityWithTypeTuple record, @Nullable EntityWithTypeTuple existingRecord) {
		if (!(existingRecord == null || existingRecord.equals(record))) {
			throw new UniqueValueViolationException(
				attributeKey.getAttributeName(), key,
				toClassifier(existingRecord.entityType()), existingRecord.entityPrimaryKey(),
				toClassifier(record.entityType()), record.entityPrimaryKey()
			);
		}
	}

	@Nonnull
	private String toClassifier(int entityType) {
		return primaryKeyToEntityType.computeIfAbsent(
			entityType,
			epk -> {
				final EntityCollection entityCollection = catalog.getCollectionForEntityPrimaryKeyOrThrowException(epk);
				return entityCollection.getEntityType();
			}
		);
	}

	private int fromClassifier(@Nonnull String entityType) {
		return entityTypeToPk.computeIfAbsent(
			entityType,
			et -> catalog.getCollectionForEntityOrThrowException(et).getEntityTypePrimaryKey()
		);
	}

	@Nullable
	private Locale toLocale(int locale) {
		return locale == -1 ? null : Objects.requireNonNull(this.idToLocaleIndex.get(locale));
	}

	private int fromLocale(@Nullable Locale locale) {
		return locale == null ? -1 : this.localeToIdIndex.computeIfAbsent(
			locale,
			theLocale -> {
				final int assignedId = localePkSequence.incrementAndGet();
				this.idToLocaleIndex.put(assignedId, theLocale);
				return assignedId;
			}
		);
	}

	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(@Nonnull T key, EntityWithTypeTuple expectedRecordId, @Nullable EntityWithTypeTuple existingRecordId) {
		isTrue(
			Objects.equals(existingRecordId, expectedRecordId),
			() -> existingRecordId == null ?
				"No unique key exists for `" + attributeKey.getAttributeName() + "` key: `" + key + "`!" :
				"Unique key exists for `" + attributeKey.getAttributeName() + "` key: `" + key + "` belongs to record with id `" + existingRecordId + "` and not `" + expectedRecordId + "` as expected!"
		);
	}

	/**
	 * Internal representation of the entity reference optimized for low memory consumption.
	 *
	 * @param entityType       the entity type primary key
	 * @param entityPrimaryKey the primary key of the entity
	 * @param locale           the locale of associated key
	 */
	public record EntityWithTypeTuple(
		int entityType,
		int entityPrimaryKey,
		int locale
	) {
	}

}
