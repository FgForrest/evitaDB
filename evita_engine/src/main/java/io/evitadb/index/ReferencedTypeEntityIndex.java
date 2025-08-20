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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.Transaction;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.ReferencedTypeEntityIndex.ReferencedTypeEntityIndexChanges;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.util.ReflectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
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
 * This indes doesn't maintain the prices of entities - only the attributes present on relations.
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
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateContract> OBJECT_METHODS_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateContract> GET_ID_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getId",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReducedEntityIndex.class, "getId"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> 0L
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getIndexKey()} method that delegates to the super implementation
	 * returning the index key passed in constructor.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateContract> GET_INDEX_KEY_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	 * Matcher for {@link ReferencedTypeEntityIndex#getAllPrimaryKeys()} method that returns the super set of primary keys
	 * from the proxy state object.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateContract> GET_ALL_PRIMARY_KEYS_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getAllPrimaryKeys",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getAllPrimaryKeys"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getSuperSetOfPrimaryKeysBitmap(proxy)
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getAllPrimaryKeysFormula()} method that returns the super set of primary keys
	 * from the proxy state object.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateContract> GET_ALL_PRIMARY_KEYS_FORMULA_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getAllPrimaryKeysFormula",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getAllPrimaryKeysFormula"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getSuperSetOfPrimaryKeysFormula(proxy)
	);
	/**
	 * Matcher for all other methods that throws a {@link ReferenceNotIndexedException} exception.
	 */
	private static final PredicateMethodClassification<ReferencedTypeEntityIndex, Void, ReferencedTypeEntityIndexProxyStateContract> THROW_REFERENCE_NOT_FOUND_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	 * This index keeps information about cardinality of referenced primary keys for each owner entity primary key.
	 * The referenced primary keys are indexed into {@link #entityIds} but they may be added to this index multiple times.
	 * In order to know when they could be removed from {@link #entityIds} we need to know how many times they were added
	 * and this is being tracked in this data structure.
	 *
	 * In order to optimize storage we keep only cardinalities that are greater than 1. The cardinality = 1 can be
	 * determined by the presence of the referenced primary key in {@link #entityIds}.
	 */
	@Nonnull
	private final CardinalityIndex primaryKeyCardinality;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link FilterIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, CardinalityIndex> cardinalityIndexes;

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
				// this is used to retrieve superset of primary keys in missing index - let's return empty bitmap
				GET_ALL_PRIMARY_KEYS_IMPLEMENTATION,
				// this is used to retrieve superset of primary keys in missing index - let's return empty formula
				GET_ALL_PRIMARY_KEYS_FORMULA_IMPLEMENTATION,
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
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Bitmap superSetOfPrimaryKeys
	) {
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				new ReferencedTypeEntityIndexProxyStateWithSuperSet(entitySchema, superSetOfPrimaryKeys),
				// objects method must pass through
				OBJECT_METHODS_IMPLEMENTATION,
				// index id will be provided as 0, because this id cannot be generated for the index
				GET_ID_IMPLEMENTATION,
				// index key is known and will be used in additional code
				GET_INDEX_KEY_IMPLEMENTATION,
				// this is used to retrieve superset of primary keys in missing index - let's return empty bitmap
				GET_ALL_PRIMARY_KEYS_IMPLEMENTATION,
				// this is used to retrieve superset of primary keys in missing index - let's return empty formula
				GET_ALL_PRIMARY_KEYS_FORMULA_IMPLEMENTATION,
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
		this.primaryKeyCardinality = new CardinalityIndex(Integer.class);
		this.cardinalityIndexes = new TransactionalMap<>(new HashMap<>(), CardinalityIndex.class, Function.identity());
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
		@Nonnull CardinalityIndex primaryKeyCardinality,
		@Nonnull Map<AttributeKey, CardinalityIndex> cardinalityIndexes
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, VoidPriceIndex.INSTANCE
		);
		this.primaryKeyCardinality = primaryKeyCardinality;
		this.cardinalityIndexes = new TransactionalMap<>(cardinalityIndexes, CardinalityIndex.class, Function.identity());
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
			this.primaryKeyCardinality.isEmpty() &&
			this.cardinalityIndexes.isEmpty();
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
			this.primaryKeyCardinality
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

		for (Entry<AttributeKey, CardinalityIndex> entry : this.cardinalityIndexes.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(this.primaryKey, entry.getKey()))
				.ifPresent(trappedChanges::addChangeToStore);
		}
	}

	/**
	 * This method delegates call to {@link super#insertPrimaryKeyIfMissing(int)}
	 * but tracks the cardinality of the referenced primary key in {@link #primaryKeyCardinality}.
	 *
	 * @see #primaryKeyCardinality
	 */
	@Override
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey) {
		this.dirty.setToTrue();
		if (this.primaryKeyCardinality.addRecord(entityPrimaryKey, entityPrimaryKey)) {
			return super.insertPrimaryKeyIfMissing(entityPrimaryKey);
		}
		return false;
	}

	/**
	 * This method delegates call to {@link super#removePrimaryKey(int)} but tracks
	 * the cardinality of the referenced primary key in {@link #primaryKeyCardinality} and removes the referenced
	 * primary key from {@link #entityIds} only when the cardinality reaches 0.
	 *
	 * @see #primaryKeyCardinality
	 */
	@Override
	public boolean removePrimaryKey(int entityPrimaryKey) {
		this.dirty.setToTrue();
		if (this.primaryKeyCardinality.removeRecord(entityPrimaryKey, entityPrimaryKey)) {
			return super.removePrimaryKey(entityPrimaryKey);
		}
		return false;
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
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
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
		final AttributeKey attributeKey = createAttributeKey(attributeSchema, allowedLocales, locale, value);
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
			transactionalLayer.getStateCopyWithCommittedChanges(this.primaryKeyCardinality),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityIndexes)
		);

		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return referencedTypeEntityIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.primaryKeyCardinality.removeLayer(transactionalLayer);
		this.cardinalityIndexes.removeLayer(transactionalLayer);

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

	/**
	 * Shared contract for different implementations of the proxy state for {@link ReferencedTypeEntityIndex}.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
	 */
	public interface ReferencedTypeEntityIndexProxyStateContract extends Serializable {
		/**
		 * Retrieves the bitmap representation of the super set of primary keys.
		 * This method ensures the bitmap is initialized and cached for subsequent calls.
		 *
		 * @return a {@link Bitmap} containing the super set of primary keys
		 */
		@Nonnull
		Bitmap getSuperSetOfPrimaryKeysBitmap(@Nonnull ReferencedTypeEntityIndex entityIndex);

		/**
		 * Retrieves the formula representation of the super set of primary keys.
		 * This method ensures the formula is initialized and cached for subsequent calls.
		 *
		 * @return a {@link Formula} containing the super set of primary keys
		 */
		@Nonnull
		Formula getSuperSetOfPrimaryKeysFormula(@Nonnull ReferencedTypeEntityIndex entityIndex);

		/**
		 * Retrieves the entity schema associated with this proxy state.
		 *
		 * @return the {@link EntitySchemaContract} representing the schema of the entity
		 */
		@Nonnull
		EntitySchemaContract getEntitySchema();

	}

	/**
	 * ReducedIndexProxyState is a private static class that acts as a proxy state,
	 * holding a super set of primary keys and providing cached access to their representations
	 * as a Bitmap and a Formula.
	 *
	 * The class lazily initializes these representations to optimize performance
	 * and reduce unnecessary computation.
	 */
	@RequiredArgsConstructor
	private static class ReferencedTypeEntityIndexProxyStateWithSuperSet implements ReferencedTypeEntityIndexProxyStateContract {
		@Serial private static final long serialVersionUID = 5964561548578664820L;
		@Getter @Nonnull private final EntitySchemaContract entitySchema;
		@Nullable private final int[] superSetOfPrimaryKeys;
		@Nullable private Bitmap superSetOfPrimaryKeysBitmap;
		@Nullable private Formula superSetOfPrimaryKeysFormula;

		public ReferencedTypeEntityIndexProxyStateWithSuperSet(@Nonnull EntitySchemaContract entitySchema, @Nullable Bitmap superSetOfPrimaryKeys) {
			this.entitySchema = entitySchema;
			this.superSetOfPrimaryKeys = null;
			this.superSetOfPrimaryKeysBitmap = superSetOfPrimaryKeys;
		}

		@Nonnull
		@Override
		public Bitmap getSuperSetOfPrimaryKeysBitmap(@Nonnull ReferencedTypeEntityIndex entityIndex) {
			if (this.superSetOfPrimaryKeysBitmap == null) {
				this.superSetOfPrimaryKeysBitmap = ArrayUtils.isEmpty(this.superSetOfPrimaryKeys) ?
					EmptyBitmap.INSTANCE : new ArrayBitmap(this.superSetOfPrimaryKeys);
			}
			return this.superSetOfPrimaryKeysBitmap;
		}

		@Nonnull
		@Override
		public Formula getSuperSetOfPrimaryKeysFormula(@Nonnull ReferencedTypeEntityIndex entityIndex) {
			if (this.superSetOfPrimaryKeysFormula == null) {
				final Bitmap theBitmap = getSuperSetOfPrimaryKeysBitmap(entityIndex);
				this.superSetOfPrimaryKeysFormula = theBitmap instanceof EmptyBitmap ?
					EmptyFormula.INSTANCE : new ConstantFormula(theBitmap);
			}
			return this.superSetOfPrimaryKeysFormula;
		}

	}

	@RequiredArgsConstructor
	private static class ReferencedTypeEntityIndexProxyStateThrowing implements ReferencedTypeEntityIndexProxyStateContract {
		@Serial private static final long serialVersionUID = 5594003658214725555L;
		@Getter @Nonnull private final EntitySchemaContract entitySchema;

		@Nonnull
		@Override
		public Bitmap getSuperSetOfPrimaryKeysBitmap(@Nonnull ReferencedTypeEntityIndex entityIndex) {
			final EntityIndexKey theIndexKey = entityIndex.getIndexKey();
			final String referenceName = Objects.requireNonNull((String) theIndexKey.discriminator());
			throw new ReferenceNotIndexedException(referenceName, this.entitySchema, theIndexKey.scope());
		}

		@Nonnull
		@Override
		public Formula getSuperSetOfPrimaryKeysFormula(@Nonnull ReferencedTypeEntityIndex entityIndex) {
			final EntityIndexKey theIndexKey = entityIndex.getIndexKey();
			final String referenceName = Objects.requireNonNull((String) theIndexKey.discriminator());
			throw new ReferenceNotIndexedException(referenceName, this.entitySchema, theIndexKey.scope());
		}

	}

}
