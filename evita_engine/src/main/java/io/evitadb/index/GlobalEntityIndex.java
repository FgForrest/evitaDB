/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.store.model.StoragePart;
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
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Global entity index contains complete set of indexed data including their bodies. It contains data for all entities
 * in the {@link EntityCollection} and it's the broadest index available. The global index is always
 * available if there is single entity in the collection and is always only one. There might be several dozens of
 * {@link ReducedEntityIndex reduced indexes} that maintain subsets, primarily of bitmap information and references
 * to object that are primarily held in this GlobalEntityIndex. We try to avoid duplicate memory allocations for same
 * object such as price records and expensive attribute values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GlobalEntityIndex extends EntityIndex
	implements VoidTransactionMemoryProducer<GlobalEntityIndex>
{

	/**
	 * Matcher for all Object.class methods that just delegates calls to super implementation.
	 */
	private static final PredicateMethodClassification<GlobalEntityIndex, Void, GlobalIndexProxyState> OBJECT_METHODS_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	private static final PredicateMethodClassification<GlobalEntityIndex, Void, GlobalIndexProxyState> GET_ID_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getId",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, GlobalEntityIndex.class, "getId"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> 0L
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getIndexKey()} method that delegates to the super implementation
	 * returning the index key passed in constructor.
	 */
	private static final PredicateMethodClassification<GlobalEntityIndex, Void, GlobalIndexProxyState> GET_INDEX_KEY_IMPLEMENTATION = new PredicateMethodClassification<>(
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
	private static final PredicateMethodClassification<GlobalEntityIndex, Void, GlobalIndexProxyState> GET_ALL_PRIMARY_KEYS_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getAllPrimaryKeys",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getAllPrimaryKeys"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getSuperSetOfPrimaryKeysBitmap()
	);
	/**
	 * Matcher for {@link ReferencedTypeEntityIndex#getAllPrimaryKeysFormula()} method that returns the super set of primary keys
	 * from the proxy state object.
	 */
	private static final PredicateMethodClassification<GlobalEntityIndex, Void, GlobalIndexProxyState> GET_ALL_PRIMARY_KEYS_FORMULA_IMPLEMENTATION = new PredicateMethodClassification<>(
		"getAllPrimaryKeysFormula",
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, ReferencedTypeEntityIndex.class, "getAllPrimaryKeysFormula"),
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getSuperSetOfPrimaryKeysFormula()
	);
	/**
	 * Matcher for all other methods that throws a {@link ReferenceNotIndexedException} exception.
	 */
	private static final PredicateMethodClassification<GlobalEntityIndex, Void, GlobalIndexProxyState> THROW_ENTITY_NOT_MANAGED_EXCEPTION = new PredicateMethodClassification<>(
		"All other methods",
		(method, proxyState) -> true,
		(method, state) -> null,
		(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
			throw new EntityNotManagedException(proxyState.getEntityType());
		}
	);

	/**
	 * This part of index collects information about prices of the entities. It provides data that are necessary for
	 * constructing {@link Formula} tree for the constraints related to the prices.
	 */
	@Delegate(types = PriceIndexContract.class)
	@Getter private final PriceSuperIndex priceIndex;

	/**
	 * Creates a proxy instance of {@link GlobalEntityIndex} that throws a {@link EntityNotManagedException}
	 * for any methods not explicitly handled within the proxy.
	 *
	 * @param entityType The name of the entity type.
	 * @param entityIndexKey The key for the entity index.
	 * @return A proxy instance of {@link GlobalEntityIndex} that conditionally throws exceptions.
	 */
	@Nonnull
	public static GlobalEntityIndex createThrowingStub(
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Collection<Integer> superSetOfPrimaryKeys
	) {
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(
				new GlobalIndexProxyState(entityType, superSetOfPrimaryKeys),
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
				// for all other methods we will throw the exception that the entity is not managed
				THROW_ENTITY_NOT_MANAGED_EXCEPTION
			),
			new Class<?>[]{
				GlobalEntityIndex.class
			},
			new Class<?>[]{
				int.class,
				String.class,
				EntityIndexKey.class
			},
			new Object[]{
				-1, entityType, entityIndexKey
			}
		);
	}

	public GlobalEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		this.priceIndex = new PriceSuperIndex();
	}

	public GlobalEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull PriceSuperIndex priceIndex,
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

	@Override
	public void removeTransactionalMemoryOfReferencedProducers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.priceIndex.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public Collection<StoragePart> getModifiedStorageParts() {
		final Collection<StoragePart> dirtyList = super.getModifiedStorageParts();
		dirtyList.addAll(this.priceIndex.getModifiedStorageParts(this.primaryKey));
		return dirtyList;
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
		this.priceIndex.resetDirty();
	}

	/*
		TRANSACTIONAL MEMORY IMPLEMENTATION
	 */

	@Nonnull
	@Override
	public GlobalEntityIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		return new GlobalEntityIndex(
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
	public boolean isEmpty() {
		return super.isEmpty() && this.priceIndex.isPriceIndexEmpty();
	}

	/**
	 * GlobalIndexProxyState is a private static class that acts as a proxy state,
	 * holding a super set of primary keys and providing cached access to their representations
	 * as a Bitmap and a Formula.
	 *
	 * The class lazily initializes these representations to optimize performance
	 * and reduce unnecessary computation.
	 */
	@RequiredArgsConstructor
	private static class GlobalIndexProxyState implements Serializable {
		@Serial private static final long serialVersionUID = -3552741023659721189L;
		@Getter private final @Nonnull String entityType;
		private final @Nonnull Collection<Integer> superSetOfPrimaryKeys;
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
				this.superSetOfPrimaryKeysBitmap = this.superSetOfPrimaryKeys.isEmpty() ?
					EmptyBitmap.INSTANCE : new ArrayBitmap(this.superSetOfPrimaryKeys.stream().mapToInt(i -> i).toArray());
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
				this.superSetOfPrimaryKeysFormula = this.superSetOfPrimaryKeys.isEmpty() ?
					EmptyFormula.INSTANCE : new ConstantFormula(getSuperSetOfPrimaryKeysBitmap());
			}
			return this.superSetOfPrimaryKeysFormula;
		}

	}

}
