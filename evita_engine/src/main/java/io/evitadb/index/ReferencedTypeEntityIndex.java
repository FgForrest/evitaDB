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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.Transaction;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencedTypeEntityIndex.ReferencedTypeEntityIndexChanges;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.util.ReflectionUtils;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.index.attribute.AttributeIndex.createAttributeKey;
import static java.util.Optional.ofNullable;

/**
 * Referenced type entity index exists once per {@link EntitySchemaContract#getReference(String)} and indexes not
 * the owner entity primary key, but the referenced entity primary key with attributes that lay on the reference
 * relation. We need this index to be able to navigate to {@link ReducedEntityIndex} that were specially created to
 * speed up queries that involve the references.
 *
 * This index doesn't maintain the prices of entities - only the attributes present on relations.
 * TODO JNO - write migration logic for old data that index referenced entity ids
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedTypeEntityIndex extends EntityIndex implements
	TransactionalLayerProducer<ReferencedTypeEntityIndexChanges, ReferencedTypeEntityIndex>,
	IndexDataStructure
{
	/**
	 * Matcher for all Object.class methods that just delegates calls to super implementation.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateThrowing> OBJECT_METHODS_IMPLEMENTATION = new PredicateMethodClassification<>(
		"Object methods",
		(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, Object.class),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			try {
				return invokeSuper.call();
			} catch (Exception e) {
				throw new InvocationTargetException(e);
			}
		}
	);
	/**
	 * Matcher for {@link EntityIndex#getId()} method that returns 0 as the index id cannot be generated for the index.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateThrowing> GET_ID_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getId",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReducedEntityIndex.class, "getId"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> 0L
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getIndexKey()} method that delegates to the super implementation
	 * returning the index key passed in constructor.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateThrowing> GET_INDEX_KEY_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getIndexKey",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getIndexKey"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			try {
				return invokeSuper.call();
			} catch (Exception e) {
				throw new InvocationTargetException(e);
			}
		}
	);
	/**
	 * Matcher for all other methods that throws a {@link ReferenceNotIndexedException} exception.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateThrowing> THROW_REFERENCE_NOT_FOUND_IMPLEMENTATION = new PredicateMethodClassification<>(
		"All other methods",
		(method, proxyState) -> true,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			final EntityIndexKey theIndexKey = proxy.getIndexKey();
			final Serializable discriminator = Objects.requireNonNull(theIndexKey.discriminator());
			throw new ReferenceNotIndexedException(
				(String) discriminator,
				proxyState.getEntitySchema(),
				theIndexKey.scope()
			);
		}
	);

	/**
	 * No prices are maintained in this index.
	 */
	@Delegate(types = PriceIndexContract.class)
	private final PriceIndexContract priceIndex = VoidPriceIndex.INSTANCE;
	/**
	 * This index keeps information about cardinality of index primary keys for each owner entity primary key.
	 * The referenced primary keys are indexed into {@link #entityIds} but they may be added to this index multiple times.
	 * In order to know when they could be removed from {@link #entityIds} we need to know how many times they were added
	 * and this is being tracked in this data structure.
	 *
	 * In order to optimize storage we keep only cardinalities that are greater than 1. The cardinality = 1 can be
	 * determined by the presence of the referenced primary key in {@link #entityIds}.
	 */
	@Nonnull
	private final CardinalityIndex indexPrimaryKeyCardinality;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link FilterIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, CardinalityIndex> cardinalityIndexes;
	/**
	 * Index that for each referenced entity primary key keeps the bitmap of all reduced entity index primary keys that
	 * contains entity primary keys referencing this entity.
	 */
	@Nonnull private final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * Helper bitmap that contains all referenced entity primary keys that are present in keys of
	 * {@link #referencedPrimaryKeysIndex}.
	 */
	private RoaringBitmap memoizedAllReferencedPrimaryKeys;

	/**
	 * Creates a proxy instance of {@link ReferencedTypeEntityIndex} that throws a {@link ReferenceNotIndexedException}
	 * for any methods not explicitly handled within the proxy.
	 *
	 * @param entitySchema The schema contract for the entity associated with the index.
	 * @param entityIndexKey The key for the entity index.
	 * @return A proxy instance of {@link ReferencedTypeEntityIndex} that conditionally throws exceptions.
	 */
	@Nonnull
	public static ReferencedTypeEntityIndex createThrowingStub(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				new ReferencedTypeEntityIndexProxyStateThrowing(entitySchema),
				// objects method must pass through
				OBJECT_METHODS_IMPLEMENTATION,
				// index id will be provided as 0, because this id cannot be generated for the index
				GET_ID_IMPLEMENTATION,
				// index key is known and will be used in additional code
				GET_INDEX_KEY_IMPLEMENTATION,
				// for all other methods we will throw the exception that the reference is not indexed
				THROW_REFERENCE_NOT_FOUND_IMPLEMENTATION
			),
			new Class<?>[]{
				ReferencedTypeEntityIndex.class
			},
			new Class<?>[]{
				int.class,
				String.class,
				EntityIndexKey.class
			},
			new Object[]{
				-1, entitySchema.getName(), entityIndexKey
			}
		);
	}

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		this.indexPrimaryKeyCardinality = new CardinalityIndex(Integer.class);
		this.cardinalityIndexes = new TransactionalMap<>(CollectionUtils.createHashMap(16), CardinalityIndex.class, Function.identity());
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(CollectionUtils.createHashMap(16), TransactionalBitmap.class, TransactionalBitmap::new);
	}

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull CardinalityIndex indexPrimaryKeyCardinality,
		@Nonnull Map<AttributeIndexKey, CardinalityIndex> cardinalityIndexes,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, VoidPriceIndex.INSTANCE
		);
		this.indexPrimaryKeyCardinality = indexPrimaryKeyCardinality;
		this.cardinalityIndexes = new TransactionalMap<>(cardinalityIndexes, CardinalityIndex.class, Function.identity());
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(referencedPrimaryKeysIndex, TransactionalBitmap.class, TransactionalBitmap::new);
	}

	@Nonnull
	@Override
	public <S extends PriceIndexContract> S getPriceIndex() {
		//noinspection unchecked
		return (S) this.priceIndex;
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() &&
			this.referencedPrimaryKeysIndex.isEmpty();
	}

	/**
	 * Retrieves all reference indexes associated with the given referenced entity primary key.
	 *
	 * @param referencedEntityPrimaryKey the primary key of the referenced entity for which the indexes are to be retrieved
	 * @return an array of all reference indexes primary keys associated with the specified referenced entity primary key
	 */
	@Nonnull
	public int[] getAllReferenceIndexes(int referencedEntityPrimaryKey) {
		return ofNullable(this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey))
			.map(TransactionalBitmap::getArray)
			.orElse(ArrayUtils.EMPTY_INT_ARRAY);
	}

	@Override
	protected StoragePart createStoragePart(
		boolean hierarchyIndexEmpty,
		@Nonnull Set<AttributeIndexStorageKey> attributeIndexStorageKeys,
		@Nonnull Set<PriceIndexKey> priceIndexKeys,
		@Nonnull Set<String> facetIndexReferencedEntities
	) {
		return new EntityIndexStoragePart(
			this.primaryKey, this.version, this.indexKey,
			this.entityIds, this.entityIdsByLanguage,
			attributeIndexStorageKeys,
			priceIndexKeys,
			!hierarchyIndexEmpty,
			facetIndexReferencedEntities,
			this.indexPrimaryKeyCardinality,
			this.referencedPrimaryKeysIndex
		);
	}

	@Nonnull
	@Override
	protected Stream<AttributeIndexStorageKey> getAttributeIndexStorageKeyStream() {
		return Stream.concat(
			super.getAttributeIndexStorageKeyStream(),
			ofNullable(this.cardinalityIndexes)
				.map(TransactionalMap::keySet)
				.stream()
				.flatMap(
					set -> set.stream()
						.map(attributeKey -> new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.CARDINALITY, attributeKey))
				)
		);
	}

	@Override
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		super.getModifiedStorageParts(trappedChanges);

		for (Entry<AttributeIndexKey, CardinalityIndex> entry : this.cardinalityIndexes.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(this.primaryKey, entry.getKey()))
				.ifPresent(trappedChanges::addChangeToStore);
		}
	}

	/**
	 * This method delegates call to {@link super#insertPrimaryKeyIfMissing(int)}
	 * but tracks the cardinality of the referenced primary key in {@link #indexPrimaryKeyCardinality}.
	 *
	 * @see #indexPrimaryKeyCardinality
	 */
	@Override
	public boolean insertPrimaryKeyIfMissing(int indexPrimaryKey) {
		throw new UnsupportedOperationException(
			"Use insertPrimaryKeyIfMissing(int indexPrimaryKey, int referencedEntityPrimaryKey) instead!"
		);
	}

	/**
	 * This method should be called instead of {@link #insertPrimaryKeyIfMissing(int)} because it tracks the cardinality
	 * both of the indexed primary key and the referenced primary key.
	 */
	public boolean insertPrimaryKeyIfMissing(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		this.dirty.setToTrue();
		if (this.indexPrimaryKeyCardinality.addRecord(NumberUtils.join(0, indexPrimaryKey), indexPrimaryKey)) {
			super.insertPrimaryKeyIfMissing(indexPrimaryKey);
		}
		if (this.indexPrimaryKeyCardinality.addRecord(NumberUtils.join(indexPrimaryKey, referencedEntityPrimaryKey), indexPrimaryKey)) {
			TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			if (indexIdBitmap == null) {
				indexIdBitmap = new TransactionalBitmap();
				this.referencedPrimaryKeysIndex.put(referencedEntityPrimaryKey, indexIdBitmap);
			}
			indexIdBitmap.add(indexPrimaryKey);
		}
		return true;
	}

	/**
	 * This method delegates call to {@link super#removePrimaryKey(int)} but tracks
	 * the cardinality of the referenced primary key in {@link #indexPrimaryKeyCardinality} and removes the referenced
	 * primary key from {@link #entityIds} only when the cardinality reaches 0.
	 *
	 * @see #indexPrimaryKeyCardinality
	 */
	@Override
	public boolean removePrimaryKey(int indexPrimaryKey) {
		throw new UnsupportedOperationException(
			"Use removePrimaryKey(int, int) instead!"
		);
	}

	/**
	 * This method should be called instead of {@link #insertPrimaryKeyIfMissing(int)} because it tracks the cardinality
	 * both of the indexed primary key and the referenced primary key.
	 */
	public boolean removePrimaryKey(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		this.dirty.setToTrue();
		if (this.indexPrimaryKeyCardinality.removeRecord(NumberUtils.join(0, indexPrimaryKey), indexPrimaryKey)) {
			super.removePrimaryKey(indexPrimaryKey);
		}
		if (this.indexPrimaryKeyCardinality.removeRecord(NumberUtils.join(indexPrimaryKey, referencedEntityPrimaryKey), indexPrimaryKey)) {
			final TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			Assert.isPremiseValid(
				indexIdBitmap != null,
				() -> new GenericEvitaInternalError(
					"Referenced entity primary key " + referencedEntityPrimaryKey + " is unexpectedly not found in the index!")
			);
			// remove the index primary key from the bitmap
			indexIdBitmap.remove(indexPrimaryKey);
		}
		return true;
	}

	/**
	 * Constructs a Formula representing the intersection of the primary keys managed by this index
	 * and the referenced entity primary keys provided as input.
	 *
	 * @param referencedEntityPrimaryKeys an array of referenced entity primary keys to be intersected with
	 *                                    the primary keys managed by this index
	 * @return a Formula representing the intersection of the primary keys; returns an empty formula if
	 *         the input array is empty
	 */
	@Nonnull
	public Bitmap getIndexPrimaryKeys(
		@Nonnull RoaringBitmap referencedEntityPrimaryKeys
	) {
		if (referencedEntityPrimaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		} else {
			final RoaringBitmap allReferencedPrimaryKeys;
			if (Transaction.isTransactionAvailable()) {
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
					writer.add(referencedEntityId);
				}
				allReferencedPrimaryKeys = writer.get();
			} else {
				if (this.memoizedAllReferencedPrimaryKeys == null) {
					final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
					for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
						writer.add(referencedEntityId);
					}
					this.memoizedAllReferencedPrimaryKeys = writer.get();
				}
				allReferencedPrimaryKeys = this.memoizedAllReferencedPrimaryKeys;
			}
			final RoaringBitmap matchingReferencedEntityPks = RoaringBitmap.and(
				allReferencedPrimaryKeys,
				referencedEntityPrimaryKeys
			);
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (Integer matchingReferencedEntityPk : matchingReferencedEntityPks) {
				final TransactionalBitmap indexIds = Objects.requireNonNull(
					this.referencedPrimaryKeysIndex.get(matchingReferencedEntityPk)
				);
				indexIds.forEach(writer::add);
			}
			return new BaseBitmap(writer.get());
		}
	}

	/**
	 * This method delegates call to {@link EntityIndex#insertFilterAttribute(ReferenceSchemaContract, AttributeSchemaContract, Set, Locale, Serializable, int)}
	 * but tracks the cardinality of the referenced primary key in {@link #cardinalityIndexes}.
	 */
	@Override
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// first retrieve or create the cardinality index for given attribute
		final CardinalityIndex theCardinalityIndex = this.cardinalityIndexes.computeIfAbsent(
			createAttributeKey(referenceSchema, attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final CardinalityIndex newCardinalityIndex = new CardinalityIndex(attributeSchema.getPlainType());
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newCardinalityIndex));
				return newCardinalityIndex;
			}
		);
		if (value instanceof Serializable[] valueArray) {
			// for array values we need to add only new items to the index (their former cardinality was zero)
			final Serializable[] onlyNewItemsValueArray = (Serializable[]) Array.newInstance(valueArray.getClass().getComponentType(), valueArray.length);
			int onlyNewItemsValueArrayIndex = 0;
			for (Serializable valueItem : valueArray) {
				if (theCardinalityIndex.addRecord(valueItem, recordId)) {
					onlyNewItemsValueArray[onlyNewItemsValueArrayIndex++] = valueItem;
				}
			}
			if (onlyNewItemsValueArrayIndex > 0) {
				final Serializable[] delta = Arrays.copyOfRange(onlyNewItemsValueArray, 0, onlyNewItemsValueArrayIndex);
				super.addDeltaFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale,
					delta, recordId
				);
			}
		} else {
			// for non-array values we need to call super method only if cardinality was zero
			if (theCardinalityIndex.addRecord(value, recordId)) {
				super.insertFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale,
					value, recordId
				);
			}
		}
	}

	@Override
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// first retrieve or create the cardinality index for given attribute
		final AttributeIndexKey attributeKey = createAttributeKey(referenceSchema, attributeSchema, allowedLocales, locale, value);
		final CardinalityIndex theCardinalityIndex = this.cardinalityIndexes.get(attributeKey);

		Assert.isPremiseValid(
			theCardinalityIndex != null,
			() -> "Cardinality index for attribute " + attributeSchema.getName() + " not found."
		);
		if (value instanceof Serializable[] valueArray) {
			// for array values we need to remove only items which cardinality reaches zero
			final Serializable[] onlyRemovedItemsValueArray = (Serializable[]) Array.newInstance(valueArray.getClass().getComponentType(), valueArray.length);
			int onlyRemovedItemsValueArrayIndex = 0;
			for (Serializable valueItem : valueArray) {
				if (theCardinalityIndex.removeRecord(valueItem, recordId)) {
					onlyRemovedItemsValueArray[onlyRemovedItemsValueArrayIndex++] = valueItem;
				}
			}
			if (onlyRemovedItemsValueArrayIndex > 0) {
				final Serializable[] delta = Arrays.copyOfRange(onlyRemovedItemsValueArray, 0, onlyRemovedItemsValueArrayIndex);
				super.removeDeltaFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale,
					delta, recordId
				);
			}
		} else {
			// for non-array values we need to call super method only if cardinality reaches zero
			if (theCardinalityIndex.removeRecord(value, recordId)) {
				super.removeFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale,
					value, recordId
				);
			}
		}

		if (theCardinalityIndex.isEmpty()) {
			final CardinalityIndex removedIndex = this.cardinalityIndexes.remove(attributeKey);
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
				.ifPresent(it -> it.addRemovedItem(removedIndex));
		}
	}

	@Override
	public void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public void removeSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public void insertSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public void removeSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public String toString() {
		return "ReducedEntityTypeIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) + ")";
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nullable
	@Override
	public ReferencedTypeEntityIndexChanges createLayer() {
		return isTransactionAvailable() ? new ReferencedTypeEntityIndexChanges() : null;
	}

	@Nonnull
	@Override
	public ReferencedTypeEntityIndex createCopyWithMergedTransactionalMemory(ReferencedTypeEntityIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		final ReferencedTypeEntityIndex referencedTypeEntityIndex = new ReferencedTypeEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexPrimaryKeyCardinality),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityIndexes),
			transactionalLayer.getStateCopyWithCommittedChanges(this.referencedPrimaryKeysIndex)
		);

		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return referencedTypeEntityIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.indexPrimaryKeyCardinality.removeLayer(transactionalLayer);
		this.cardinalityIndexes.removeLayer(transactionalLayer);
		this.referencedPrimaryKeysIndex.removeLayer(transactionalLayer);

		final ReferencedTypeEntityIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #cardinalityIndexes} transactional maps.
	 */
	public static class ReferencedTypeEntityIndexChanges {
		private final TransactionalContainerChanges<Void, CardinalityIndex, CardinalityIndex> cardinalityIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull CardinalityIndex cardinalityIndex) {
			this.cardinalityIndexChanges.addCreatedItem(cardinalityIndex);
		}

		public void addRemovedItem(@Nonnull CardinalityIndex cardinalityIndex) {
			this.cardinalityIndexChanges.addRemovedItem(cardinalityIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.cardinalityIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.cardinalityIndexChanges.cleanAll(transactionalLayer);
		}

	}

	@RequiredArgsConstructor
	private static class ReferencedTypeEntityIndexProxyStateThrowing implements Serializable {
		@Serial private static final long serialVersionUID = 5594003658214725555L;
		@Getter @Nonnull private final EntitySchemaContract entitySchema;

	}

}
