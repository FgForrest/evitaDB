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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogRelatedDataStructure;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexReadContract;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
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
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Reduced entity index is a "helper" index that maintains primarily bitmaps of primary keys that are connected to
 * a limited scope view of the data. All memory expensive objects are referred and maintained in {@link GlobalEntityIndex}
 * so that it's ensured they exist solely on the heap.
 *
 * Reduced indexes are used for handling queries that target {@link ReferenceContract}
 * of the entities. In such case we may prefer using data from reduced entity index because it may substantially limit
 * the amount of operations to answer the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReducedEntityIndex extends EntityIndex
	implements VoidTransactionMemoryProducer<ReducedEntityIndex>, CatalogRelatedDataStructure<ReducedEntityIndex>
{
	/**
	 * Matcher for all Object.class methods that just delegates calls to super implementation.
	 */
	private static final PredicateMethodClassification<ReducedEntityIndex, Void, ReducedIndexProxyState> OBJECT_METHODS_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	private static final PredicateMethodClassification<ReducedEntityIndex, Void, ReducedIndexProxyState> GET_ID_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getId",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReducedEntityIndex.class, "getId"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> 0L
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getIndexKey()} method that delegates to the super implementation
	 * returning the index key passed in constructor.
	 */
	private static final PredicateMethodClassification<ReducedEntityIndex, Void, ReducedIndexProxyState> GET_INDEX_KEY_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	private static final PredicateMethodClassification<ReducedEntityIndex, Void, ReducedIndexProxyState> GET_ALL_PRIMARY_KEYS_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getAllPrimaryKeys",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getAllPrimaryKeys"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getSuperSetOfPrimaryKeysBitmap()
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getAllPrimaryKeysFormula()} method that returns the super set of primary keys
	 * from the proxy state object.
	 */
	private static final PredicateMethodClassification<ReducedEntityIndex, Void, ReducedIndexProxyState> GET_ALL_PRIMARY_KEYS_FORMULA_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getAllPrimaryKeysFormula",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getAllPrimaryKeysFormula"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getSuperSetOfPrimaryKeysFormula()
	);
	/**
	 * Matcher for all other methods that throws a {@link ReferenceNotIndexedException} exception.
	 */
	private static final PredicateMethodClassification<ReducedEntityIndex, Void, ReducedIndexProxyState> THROW_REFERENCE_NOT_FOUND_IMPLEMENTATION = new PredicateMethodClassification<>(
		"All other methods",
		(method, proxyState) -> true,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			final EntityIndexKey theIndexKey = proxy.getIndexKey();
			throw new ReferenceNotIndexedException(
				((ReferenceKey) theIndexKey.discriminator()).referenceName(),
				proxyState.getEntitySchema(),
				theIndexKey.scope()
			);
		}
	);

	/**
	 * This part of index collects information about prices of the entities. It provides data that are necessary for
	 * constructing {@link Formula} tree for the constraints related to the prices.
	 */
	@Delegate(types = PriceIndexReadContract.class)
	@Getter private final PriceRefIndex priceIndex;

	/**
	 * Creates a proxy instance of {@link ReducedEntityIndex} that throws a {@link ReferenceNotIndexedException}
	 * for any methods not explicitly handled within the proxy.
	 *
	 * @param entitySchema The schema contract for the entity associated with the index.
	 * @param entityIndexKey The key for the entity index.
	 * @return A proxy instance of {@link ReferencedTypeEntityIndex} that conditionally throws exceptions.
	 */
	@Nonnull
	public static ReducedEntityIndex createThrowingStub(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull int[] superSetOfPrimaryKeys
	) {
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				new ReducedIndexProxyState(entitySchema, superSetOfPrimaryKeys),
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
				ReducedEntityIndex.class
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

	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		this.priceIndex = new PriceRefIndex(this.getIndexKey().scope());
	}

	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull PriceRefIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, priceIndex
		);
		this.priceIndex = priceIndex;
	}

	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull TransactionalBitmap entityIds,
		@Nonnull TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		boolean originalHierarchyIndexEmpty,
		@Nonnull Set<AttributeIndexStorageKey> originalAttributeIndexes,
		@Nonnull Set<PriceIndexKey> originalPriceIndexes,
		@Nonnull Set<String> originalFacetIndexes,
		@Nonnull PriceRefIndex priceIndex
	) {
		super(
			primaryKey, indexKey, version, entityIds,
			entityIdsByLanguage, attributeIndex, hierarchyIndex, facetIndex,
			originalHierarchyIndexEmpty,
			originalAttributeIndexes, originalPriceIndexes, originalFacetIndexes
		);
		this.priceIndex = priceIndex;
	}

	/**
	 * Retrieves the reference key associated with the current entity index.
	 * The reference key is derived from the discriminator of the index key.
	 *
	 * @return the non-null {@link ReferenceKey} uniquely identifying a reference within the entity index.
	 * @throws NullPointerException if the resolved reference key is null.
	 */
	@Nonnull
	public ReferenceKey getReferenceKey() {
		return Objects.requireNonNull((ReferenceKey) this.indexKey.discriminator());
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		this.priceIndex.attachToCatalog(entityType, catalog);
	}

	@Nonnull
	@Override
	public ReducedEntityIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new ReducedEntityIndex(
			this.primaryKey, this.indexKey, this.version,
			this.entityIds, this.entityIdsByLanguage,
			this.attributeIndex,
			this.hierarchyIndex,
			this.facetIndex,
			this.originalHierarchyIndexEmpty,
			this.originalAttributeIndexes,
			this.originalPriceIndexes,
			this.originalFacetIndexes,
			this.priceIndex.createCopyForNewCatalogAttachment(catalogState)
		);
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() && this.priceIndex.isPriceIndexEmpty();
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
			null
		);
	}

	@Override
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		super.getModifiedStorageParts(trappedChanges);
		this.priceIndex.getModifiedStorageParts(this.primaryKey, trappedChanges);
	}

	@Override
	public void removeTransactionalMemoryOfReferencedProducers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
		this.priceIndex.resetDirty();
	}

	@Override
	public void removeSortAttributeCompound(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema);
		super.removeSortAttributeCompound(referenceSchema, compoundSchemaContract, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema);
		super.insertSortAttributeCompound(referenceSchema, compoundSchemaContract, attributeTypeProvider, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema);
		super.removeSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema);
		super.insertSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema);
		super.removeDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema);
		super.addDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema);
		super.removeFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema);
		super.insertFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void removeUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema);
		super.removeUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	@Override
	public void insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema);
		super.insertUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	@Override
	public void addNode(int entityPrimaryKey, Integer parentPrimaryKey) {
		throw new GenericEvitaInternalError(
			"ReducedEntityIndex is not expected to maintain hierarchical nodes!"
		);
	}

	@Override
	public Integer removeNode(int entityPrimaryKey) {
		throw new GenericEvitaInternalError(
			"ReducedEntityIndex is not expected to maintain hierarchical nodes!"
		);
	}

	@Override
	public int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		assertPartitioningIndex(referenceSchema);
		return this.priceIndex.addPrice(
			referenceSchema, entityPrimaryKey, internalPriceId, priceKey, innerRecordHandling, innerRecordId, validity,
			priceWithoutTax, priceWithTax
		);
	}

	@Override
	public void priceRemove(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		assertPartitioningIndex(referenceSchema);
		this.priceIndex.priceRemove(
			referenceSchema, entityPrimaryKey, internalPriceId, priceKey, innerRecordHandling, innerRecordId, validity,
			priceWithoutTax, priceWithTax
		);
	}

	@Nonnull
	@Override
	public ReducedEntityIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		return new ReducedEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.priceIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "ReducedEntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) + ")";
	}

	@Override
	protected boolean isRequireLocaleRemoval() {
		// reduced indexes may not have all the entity locales indexed, because they may have contain only reference attributes
		// when index type is set to FOR_FILTERING, so we cannot guarantee removing locale for particular entity primary key
		return false;
	}

	/**
	 * Ensures that the current index can be used for filtering and partitioning operations.
	 * This method validates if the `indexType` of the current instance is set to
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}. If the validation fails,
	 * an {@link IllegalArgumentException} is thrown with a descriptive error message.
	 *
	 * Throws an exception if this operation is invoked on indexes of a type other than
	 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 *
	 * @throws IllegalArgumentException if the `indexType` is not {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}.
	 */
	private void assertPartitioningIndex(@Nullable ReferenceSchemaContract referenceSchema) {
		Assert.isPremiseValid(
			referenceSchema == null || referenceSchema.getReferenceIndexType(this.indexKey.scope()) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			() -> "This operation is allowed only for indexes that are used for filtering and partitioning! Current index type is: " + Objects.requireNonNull(referenceSchema).getReferenceIndexType(this.indexKey.scope())
		);
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
	private static class ReducedIndexProxyState implements Serializable {
		@Serial private static final long serialVersionUID = -3552741023659721189L;
		@Getter private final @Nonnull EntitySchemaContract entitySchema;
		private final @Nonnull int[] superSetOfPrimaryKeys;
		private Bitmap superSetOfPrimaryKeysBitmap;
		private Formula superSetOfPrimaryKeysFormula;

		/**
		 * Retrieves the bitmap representation of the super set of primary keys.
		 * This method ensures the bitmap is initialized and cached for subsequent calls.
		 *
		 * @return a {@link Bitmap} containing the super set of primary keys
		 */
		@Nonnull
		public Bitmap getSuperSetOfPrimaryKeysBitmap() {
			if (this.superSetOfPrimaryKeysBitmap == null) {
				this.superSetOfPrimaryKeysBitmap = ArrayUtils.isEmpty(this.superSetOfPrimaryKeys) ?
					EmptyBitmap.INSTANCE : new ArrayBitmap(this.superSetOfPrimaryKeys);
			}
			return this.superSetOfPrimaryKeysBitmap;
		}

		/**
		 * Retrieves the formula representation of the super set of primary keys.
		 * This method ensures the formula is initialized and cached for subsequent calls.
		 *
		 * @return a {@link Formula} containing the super set of primary keys
		 */
		@Nonnull
		public Formula getSuperSetOfPrimaryKeysFormula() {
			if (this.superSetOfPrimaryKeysFormula == null) {
				this.superSetOfPrimaryKeysFormula = ArrayUtils.isEmpty(this.superSetOfPrimaryKeys) ?
					EmptyFormula.INSTANCE : new ConstantFormula(getSuperSetOfPrimaryKeysBitmap());
			}
			return this.superSetOfPrimaryKeysFormula;
		}

	}
}
