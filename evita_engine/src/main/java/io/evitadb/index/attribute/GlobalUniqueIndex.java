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

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogRelatedDataStructure;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.evitadb.index.attribute.UniqueIndex.verifyValue;
import static io.evitadb.index.attribute.UniqueIndex.verifyValueArray;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * Global (catalog-wide) unique index maintains information about a single unique attribute - its value to entity
 * tuple relation. It protects duplicate unique attribute insertion and allows to easily translate unique attribute
 * value to the entity that occupies it.
 *
 * The value to entity tuple relation is kept in a {@link PersistentTransactionalMap} (backed by a persistent
 * immutable {@link io.evitadb.dataType.champ.ChampMap}), so look-ups run in `O(log₃₂ N)` and commits derive the
 * next snapshot by path-copying only the mutated keys instead of rebuilding the whole map.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class GlobalUniqueIndex implements VoidTransactionMemoryProducer<GlobalUniqueIndex>, IndexDataStructure, CatalogRelatedDataStructure<GlobalUniqueIndex> {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Constant representing the attribute has no locale assigned.
	 */
	private static final int NO_LOCALE = -1;
	/**
	 * Scope of the {@link CatalogIndex} this unique index belongs to.
	 */
	@Getter private final Scope scope;
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
	 * Keeps the unique value to entity tuple mappings. A fairly large map is expected here, so it is backed by a
	 * persistent immutable {@link io.evitadb.dataType.champ.ChampMap} via {@link PersistentTransactionalMap}: the
	 * values are plain {@link EntityWithTypeTuple} records (no nested transactional state), so commit derives the
	 * next snapshot in `O(Δ·log N)` instead of rebuilding the whole map. Ordering is irrelevant here — per-type
	 * record ordering is carried by {@link #entitiesPerType}.
	 */
	@Nonnull private final PersistentTransactionalMap<Serializable, EntityWithTypeTuple> uniqueValueToEntityTuple;
	/**
	 * Keeps the lists of primary keys per entity type.
	 */
	@Nonnull private final TransactionalMap<Integer, TransactionalBitmap> entitiesPerType;
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
	/**
	 * Maps entity type primary key to entity type name.
	 */
	private final Map<Integer, String> primaryKeyToEntityType = new ConcurrentHashMap<>();
	/**
	 * Maps entity type name to entity type primary key.
	 */
	private final Map<String, Integer> entityTypeToPk = new ConcurrentHashMap<>();

	/**
	 * Creates an empty index for the given attribute. Used when a brand-new unique attribute starts being indexed
	 * and there is no previously persisted state to restore from.
	 *
	 * @param scope         scope of the owning {@link CatalogIndex}
	 * @param attributeKey  identifies the indexed attribute (name and optional locale)
	 * @param attributeType runtime type of the indexed attribute value
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.uniqueValueToEntityTuple = new PersistentTransactionalMap<>(new HashMap<>());
		this.entitiesPerType = new TransactionalMap<>(new HashMap<>(), TransactionalBitmap.class, TransactionalBitmap::new);
		this.localeToIdIndex = new TransactionalMap<>(new HashMap<>());
		this.idToLocaleIndex = new TransactionalMap<>(new HashMap<>());
	}

	/**
	 * Restores the index from persisted state when the per-type record bitmaps are not stored separately. The
	 * {@link #entitiesPerType} index and the reverse {@link #localeToIdIndex} are rebuilt from the supplied data,
	 * and {@link #localePkSequence} is primed past the highest locale id already in use so new locales receive
	 * fresh ids.
	 *
	 * @param scope                    scope of the owning {@link CatalogIndex}
	 * @param attributeKey             identifies the indexed attribute (name and optional locale)
	 * @param attributeType            runtime type of the indexed attribute value
	 * @param uniqueValueToEntityTuple restored unique value to entity tuple mappings
	 * @param localeIndex              restored mapping of internal locale id to {@link Locale}
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull Map<Serializable, EntityWithTypeTuple> uniqueValueToEntityTuple,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.uniqueValueToEntityTuple = new PersistentTransactionalMap<>(uniqueValueToEntityTuple);
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
		// construct the index from scratch
		Map<Integer, TransactionalBitmap> entitiesPerTypeBase = CollectionUtils.createHashMap(8);
		for (EntityWithTypeTuple value : uniqueValueToEntityTuple.values()) {
			entitiesPerTypeBase.computeIfAbsent(value.entityType(), entityType -> new TransactionalBitmap())
				.add(value.entityPrimaryKey());
		}
		this.entitiesPerType = new TransactionalMap<>(entitiesPerTypeBase, TransactionalBitmap.class, TransactionalBitmap::new);
	}

	/**
	 * Restores the index from persisted state including the already-computed per-type record bitmaps, avoiding the
	 * rebuild done by the shorter restore constructor. The reverse {@link #localeToIdIndex} is derived from
	 * `localeIndex` and {@link #localePkSequence} is primed past the highest locale id already in use.
	 *
	 * @param scope                    scope of the owning {@link CatalogIndex}
	 * @param attributeKey             identifies the indexed attribute (name and optional locale)
	 * @param attributeType            runtime type of the indexed attribute value
	 * @param uniqueValueToEntityTuple restored unique value to entity tuple mappings
	 * @param entitiesPerType          restored per-entity-type record id bitmaps
	 * @param localeIndex              restored mapping of internal locale id to {@link Locale}
	 */
	public GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull Map<Serializable, EntityWithTypeTuple> uniqueValueToEntityTuple,
		@Nonnull Map<Integer, TransactionalBitmap> entitiesPerType,
		@Nonnull Map<Integer, Locale> localeIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.scope = scope;
		this.attributeKey = attributeKey;
		this.type = attributeType;
		this.uniqueValueToEntityTuple = new PersistentTransactionalMap<>(uniqueValueToEntityTuple);
		this.entitiesPerType = new TransactionalMap<>(entitiesPerType, TransactionalBitmap.class, TransactionalBitmap::new);
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
	 * Adopts already-wrapped transactional maps directly instead of re-wrapping plain maps. Used by
	 * {@link #createCopyForNewCatalogAttachment(CatalogState)} to produce a detached copy that shares the same
	 * transactional backing structures while resetting the catalog reference.
	 *
	 * @param scope                    scope of the owning {@link CatalogIndex}
	 * @param attributeKey             identifies the indexed attribute (name and optional locale)
	 * @param attributeType            runtime type of the indexed attribute value
	 * @param uniqueValueToEntityTuple unique value to entity tuple mappings to adopt
	 * @param entitiesPerType          per-entity-type record id bitmaps to adopt
	 * @param localeToIdIndex          {@link Locale} to internal locale id mapping to adopt
	 * @param idToLocaleIndex          reverse internal locale id to {@link Locale} mapping to adopt
	 */
	private GlobalUniqueIndex(
		@Nonnull Scope scope,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nonnull PersistentTransactionalMap<Serializable, EntityWithTypeTuple> uniqueValueToEntityTuple,
		@Nonnull TransactionalMap<Integer, TransactionalBitmap> entitiesPerType,
		@Nonnull TransactionalMap<Locale, Integer> localeToIdIndex,
		@Nonnull TransactionalMap<Integer, Locale> idToLocaleIndex
	) {
		this.attributeKey = attributeKey;
		this.scope = scope;
		this.type = attributeType;
		this.dirty = new TransactionalBoolean();
		this.uniqueValueToEntityTuple = uniqueValueToEntityTuple;
		this.entitiesPerType = entitiesPerType;
		this.localeToIdIndex = localeToIdIndex;
		this.idToLocaleIndex = idToLocaleIndex;
	}

	/**
	 * Captures the catalog reference needed to translate between entity type names and their short int primary keys
	 * (see {@link #catalog}). Enforces single attachment: the index must not already be bound to a catalog.
	 */
	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		Assert.isPremiseValid(this.catalog == null, "Catalog was already attached to this index!");
		this.catalog = catalog;
	}

	/**
	 * Produces a detached copy that shares the same transactional backing maps but carries no catalog reference, so
	 * it can be reattached to a new catalog version while the original stays bound to the previous one. The cached
	 * entity-type-to-pk lookups ({@link #primaryKeyToEntityType}, {@link #entityTypeToPk}) are intentionally not
	 * carried over — they are rebuilt lazily against the freshly attached catalog.
	 */
	@Nonnull
	@Override
	public GlobalUniqueIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new GlobalUniqueIndex(
			this.scope,
			this.attributeKey,
			this.type,
			this.uniqueValueToEntityTuple,
			this.entitiesPerType,
			this.localeToIdIndex,
			this.idToLocaleIndex
		);
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
	@Nonnull
	public Optional<EntityReferenceWithLocale> getEntityReferenceByUniqueValue(@Nonnull Serializable value, @Nullable Locale locale) {
		return ofNullable(this.uniqueValueToEntityTuple.get(value))
			.filter(it -> locale == null || it.locale() == NO_LOCALE || fromLocale(locale) == it.locale())
			.map(it -> new EntityReferenceWithLocale(toClassifier(it.entityType()), it.entityPrimaryKey(), toLocale(it.locale())));
	}

	/**
	 * Generates a {@link Formula} instance that provides the record IDs associated with the specified entity type.
	 *
	 * @param entityType the type of the entity for which to generate the record IDs formula
	 * @return a {@link Formula} instance that computes the record IDs for the given entity type
	 */
	@Nonnull
	public Formula getRecordIdsFormula(@Nonnull String entityType) {
		final Bitmap recordIds = getRecordIds(entityType);
		return recordIds instanceof EmptyBitmap ? EmptyFormula.INSTANCE : new ConstantFormula(recordIds);
	}

	/**
	 * Retrieves the record IDs associated with a specific entity type.
	 *
	 * @param entityType the type of the entity for which record IDs are being retrieved
	 * @return a Bitmap containing the record IDs for the specified entity type
	 */
	@Nonnull
	public Bitmap getRecordIds(@Nonnull String entityType) {
		final int entityTypePk = fromClassifier(entityType);
		return ofNullable(this.entitiesPerType.get(entityTypePk))
			.map(Bitmap.class::cast)
			.orElse(EmptyBitmap.INSTANCE);
	}

	/**
	 * Returns number of unique keys in this index.
	 */
	public int size() {
		return this.uniqueValueToEntityTuple.size();
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
			return new GlobalUniqueIndexStoragePart(this.scope, attribute, this.type, this.uniqueValueToEntityTuple, this.idToLocaleIndex);
		} else {
			return null;
		}
	}

	/*
		TransactionalLayerCreator implementation
	 */

	/**
	 * Clears the dirty flag once the index contents have been persisted, so subsequent
	 * {@link #createStoragePart(AttributeKey)} calls skip an unchanged index.
	 */
	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/**
	 * Materializes a new index instance with all transactional changes committed into its backing maps. This is the
	 * commit-time merge step of the STM protocol: each transactional child is collapsed to its committed snapshot.
	 *
	 * The {@link #localeToIdIndex} is not merged directly; the constructor reconstructs it from the committed
	 * {@link #idToLocaleIndex}, so its transactional layer is simply discarded here to avoid a stale orphaned diff.
	 */
	@Nonnull
	@Override
	public GlobalUniqueIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final GlobalUniqueIndex uniqueKeyIndex = new GlobalUniqueIndex(
			this.scope, this.attributeKey, this.type,
			transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueValueToEntityTuple),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entitiesPerType),
			transactionalLayer.getStateCopyWithCommittedChanges(this.idToLocaleIndex)
		);
		transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.localeToIdIndex);
		return uniqueKeyIndex;
	}

	/**
	 * Discards the transactional memory layer of this index and all its transactional children, rolling back any
	 * uncommitted changes. Invoked when a transaction is abandoned rather than committed.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.uniqueValueToEntityTuple.removeLayer(transactionalLayer);
		this.entitiesPerType.removeLayer(transactionalLayer);
		this.localeToIdIndex.removeLayer(transactionalLayer);
		this.idToLocaleIndex.removeLayer(transactionalLayer);
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
		return this.uniqueValueToEntityTuple
			.values()
			.stream()
			.map(it -> new EntityReference(toClassifier(it.entityType()), it.entityPrimaryKey()))
			.sorted()
			.toArray(EntityReference[]::new);
	}

	/**
	 * Registers a record under a unique key that may be either a single value or an array of values (array-typed
	 * attributes occupy every contained value). For arrays, uniqueness of all elements is verified up front before
	 * any element is inserted, so a violation leaves the index unchanged (all-or-nothing).
	 *
	 * @param key    the unique value, or array of unique values, to claim
	 * @param record the entity tuple claiming the value(s)
	 * @throws UniqueValueViolationException when any value is already owned by a different record
	 */
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

	/**
	 * Claims a single scalar unique value for the given record and adds the record's primary key to the matching
	 * per-entity-type bitmap, keeping {@link #uniqueValueToEntityTuple} and {@link #entitiesPerType} in lockstep.
	 *
	 * @param key    the scalar unique value to claim
	 * @param record the entity tuple claiming the value
	 * @throws UniqueValueViolationException when the value is already owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull T key, @Nonnull EntityWithTypeTuple record) {
		final EntityWithTypeTuple existingRecordId = this.uniqueValueToEntityTuple.get(key);
		assertUniqueKeyIsFree(key, record, existingRecordId);
		this.uniqueValueToEntityTuple.put(key, record);
		this.entitiesPerType
			.computeIfAbsent(record.entityType(), entityType -> new TransactionalBitmap())
			.add(record.entityPrimaryKey());
	}

	/**
	 * Releases a unique key that may be either a single value or an array of values, the inverse of
	 * {@link #registerUniqueKeyValue(Object, EntityWithTypeTuple)}. Ownership of every element is verified up front
	 * so a mismatch leaves the index unchanged (all-or-nothing).
	 *
	 * @param key            the unique value, or array of unique values, to release
	 * @param expectedRecord the record expected to currently own the value(s)
	 * @return the released tuple for a scalar key, or `null` for an array key (per-element results are not aggregated)
	 */
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

	/**
	 * Releases a single scalar unique value and removes the record's primary key from the matching per-entity-type
	 * bitmap, then asserts the value was actually owned by the expected record.
	 *
	 * @param key             the scalar unique value to release
	 * @param expectedRecordId the record expected to currently own the value
	 * @return the tuple that previously owned the value, or `null` if the value was not present
	 */
	@Nullable
	private <T extends Serializable & Comparable<T>> EntityWithTypeTuple unregisterUniqueKeyValue(@Nonnull T key, EntityWithTypeTuple expectedRecordId) {
		final EntityWithTypeTuple existingRecordId = this.uniqueValueToEntityTuple.remove(key);
		if (existingRecordId != null) {
			// the per-type bitmap is maintained in lockstep with uniqueValueToEntityTuple in
			// registerUniqueKeyValue, so a present value tuple guarantees a present bitmap here
			final TransactionalBitmap entityTypeRecords = this.entitiesPerType.get(existingRecordId.entityType());
			Assert.isPremiseValid(
				entityTypeRecords != null,
				() -> "Entity type `" + existingRecordId.entityType() + "` unexpectedly missing from the per-type index!"
			);
			entityTypeRecords.remove(existingRecordId.entityPrimaryKey());
		}
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		return existingRecordId;
	}

	/**
	 * Verifies the value can be claimed by `record`: it must be unowned, or already owned by the very same record.
	 * For a localized attribute the same value is allowed to coexist across different locales, so a clash only
	 * counts as a violation when the two records share the locale.
	 *
	 * @param key            the unique value being claimed (for error reporting)
	 * @param record         the record attempting to claim the value
	 * @param existingRecord the record currently owning the value, or `null` if unowned
	 * @throws UniqueValueViolationException when the value is already owned by a different record in the same locale
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, EntityWithTypeTuple record, @Nullable EntityWithTypeTuple existingRecord) {
		if (!(existingRecord == null || existingRecord.equals(record))) {
			if (!this.attributeKey.localized() || existingRecord.locale() == record.locale()) {
				throw new UniqueValueViolationException(
					this.attributeKey.attributeName(), this.attributeKey.locale(), key,
					toClassifier(existingRecord.entityType()), existingRecord.entityPrimaryKey(),
					toClassifier(record.entityType()), record.entityPrimaryKey()
				);
			}
		}
	}

	/**
	 * Resolves the compact entity-type primary key stored in tuples back to the entity type name, caching the
	 * result. Requires the index to be attached to a catalog.
	 */
	@Nonnull
	private String toClassifier(int entityType) {
		return this.primaryKeyToEntityType.computeIfAbsent(
			entityType,
			epk -> {
				final EntityCollection entityCollection = this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(epk);
				return entityCollection.getEntityType();
			}
		);
	}

	/**
	 * Resolves an entity type name to the compact primary key stored in tuples, caching the result. The compact id
	 * keeps {@link EntityWithTypeTuple} small. Requires the index to be attached to a catalog.
	 */
	private int fromClassifier(@Nonnull String entityType) {
		return this.entityTypeToPk.computeIfAbsent(
			entityType,
			et -> this.catalog.getCollectionForEntityOrThrowException(et).getEntityTypePrimaryKey()
		);
	}

	/**
	 * Resolves an internal locale id stored in tuples back to its {@link Locale}, returning `null` for the
	 * {@link #NO_LOCALE} sentinel (attribute value with no locale).
	 */
	@Nullable
	private Locale toLocale(int locale) {
		return locale == NO_LOCALE ? null : Objects.requireNonNull(this.idToLocaleIndex.get(locale));
	}

	/**
	 * Resolves a {@link Locale} to its internal locale id, lazily assigning a fresh id (and registering it in both
	 * locale indexes) when the locale is seen for the first time. Returns {@link #NO_LOCALE} for a `null` locale.
	 */
	private int fromLocale(@Nullable Locale locale) {
		return locale == null ? NO_LOCALE : this.localeToIdIndex.computeIfAbsent(
			locale,
			theLocale -> {
				final int assignedId = this.localePkSequence.incrementAndGet();
				this.idToLocaleIndex.put(assignedId, theLocale);
				return assignedId;
			}
		);
	}

	/**
	 * Ensures that the unique key is owned by the expected record.
	 *
	 * @param key             the unique key to check
	 * @param expectedRecordId the expected record that should own the key
	 * @param existingRecordId the existing record that currently owns the key, can be null
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(
		@Nonnull T key,
		@Nonnull EntityWithTypeTuple expectedRecordId,
		@Nullable EntityWithTypeTuple existingRecordId
	) {
		isTrue(
			Objects.equals(existingRecordId, expectedRecordId),
			() -> existingRecordId == null ?
				"No unique key exists for `" + this.attributeKey.attributeName() + "` key: `" + key + "`!" :
				"Unique key exists for `" + this.attributeKey.attributeName() + "` key: `" + key + "` belongs to record with id `" + existingRecordId + "` and not `" + expectedRecordId + "` as expected!"
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
