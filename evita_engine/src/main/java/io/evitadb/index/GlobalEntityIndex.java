/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.EntityNotManagedException;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.component.PriceIndexComponent;
import io.evitadb.index.component.TrigramIndexMapComponent;
import io.evitadb.index.component.loader.AttributeIndexLoader;
import io.evitadb.index.component.loader.FacetIndexLoader;
import io.evitadb.index.component.loader.HierarchyIndexLoader;
import io.evitadb.index.component.loader.IndexReloadPlan;
import io.evitadb.index.component.loader.LoadedComponentBundle;
import io.evitadb.index.component.loader.PriceSuperIndexLoader;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.VMLayout;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Global entity index contains complete set of indexed data including their bodies. It contains data for all entities
 * in the {@link EntityCollection} and it's the broadest index available. The global index is always
 * available if there is single entity in the collection and is always only one. There might be several dozens of
 * {@link AbstractReducedEntityIndex reduced indexes} that maintain subsets, primarily of bitmap information and references
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
	 * The substring-search accelerators of this index, one per `(attribute, locale)` whose attribute declares
	 * {@link AttributeFilterAccelerator#SUBSTRING_SEARCH} in this index's scope. Empty - and costing a bare `HashMap`
	 * object - for every collection that declares the accelerator nowhere, which is the overwhelming majority.
	 *
	 * Hosted here and nowhere else: a reduced index composes its answer out of THIS map's value ids rather than
	 * keeping trigram postings of its own, so a catalog pays for the postings once instead of once per reduced index.
	 *
	 * An entry is created by the first write to its attribute ({@link #obtainTrigramIndex}) and dropped when the
	 * shared value tree it indexes is dropped, which is the moment its value ids stop meaning anything.
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, TrigramIndex> trigramIndex;

	@Nonnull
	@Override
	protected Class<? extends StoragePart> getPriceRootStoragePartType() {
		return PriceListAndCurrencySuperIndexStoragePart.class;
	}

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
		this(primaryKey, entityType, entityIndexKey, ServerOptions.DEFAULT_USAGE_STATISTICS_TRACKING);
	}

	/**
	 * Creates a fresh index, stating whether it counts its own usage.
	 *
	 * @param primaryKey              the primary key of this index
	 * @param entityType              the type of entity being indexed
	 * @param entityIndexKey          the key identifying this index
	 * @param usageStatisticsTracking whether to allocate an {@link io.evitadb.index.IndexActivity} holder for it
	 */
	public GlobalEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey,
		boolean usageStatisticsTracking
	) {
		super(primaryKey, entityType, entityIndexKey, usageStatisticsTracking);
		this.priceIndex = new PriceSuperIndex();
		addComponent(new PriceIndexComponent(this.priceIndex));
		// a HashMap allocates its table on the first put, so an index whose collection declares the accelerator
		// nowhere is charged the map object alone
		this.trigramIndex = new TransactionalMap<>(new HashMap<>(), TrigramIndex.class, Function.identity());
		addComponent(new TrigramIndexMapComponent(this.trigramIndex));
		// fresh empty index — every component contributes an empty manifest, so the baseline
		// captured here is the immutable empty set, preventing spurious manifest emits
		captureOriginalsFromComponents();
	}

	/**
	 * Reconstructs a global entity index from persisted or committed state.
	 *
	 * @param activity the activity holder to keep counting into — the copied index's own instance on the commit-time
	 *                 merge copy, a fresh one when loading from disk; see {@link io.evitadb.index.IndexActivity}
	 */
	public GlobalEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull EntityAttributeIndex attributeIndex,
		@Nonnull PriceSuperIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nullable IndexActivity activity
	) {
		this(
			primaryKey, entityIndexKey, version, entityIds, entityIdsByLanguage,
			attributeIndex, priceIndex, hierarchyIndex, facetIndex, Map.of(), activity
		);
	}

	/**
	 * Reconstructs a global entity index from persisted or committed state, together with its substring-search
	 * accelerators.
	 *
	 * @param trigramIndexes the per-`(attribute, locale)` trigram indexes — the committed ones on the merge copy, the
	 *                       ones {@link TrigramIndex#rebuildAll} derived from the reloaded shared value trees on a cold
	 *                       load, and empty for a caller that maintains none
	 * @param activity       the activity holder to keep counting into — the copied index's own instance on the
	 *                       commit-time merge copy, a fresh one when loading from disk; see
	 *                       {@link io.evitadb.index.IndexActivity}
	 */
	public GlobalEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull EntityAttributeIndex attributeIndex,
		@Nonnull PriceSuperIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull Map<AttributeIndexKey, TrigramIndex> trigramIndexes,
		@Nullable IndexActivity activity
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, activity
		);
		this.priceIndex = priceIndex;
		addComponent(new PriceIndexComponent(this.priceIndex));
		this.trigramIndex = new TransactionalMap<>(
			new HashMap<>(trigramIndexes), TrigramIndex.class, Function.identity()
		);
		addComponent(new TrigramIndexMapComponent(this.trigramIndex));
		// re-capture the change-detection baseline from the components now that the price super
		// index is registered, so the baseline includes every persisted sub-index
		captureOriginalsFromComponents();
	}

	/**
	 * Returns the read-side reload plan for `GlobalEntityIndex`. The plan is cached per JVM. The
	 * finalizer reads the previously-persisted `EntityIndexStoragePart` and the entity-schema name
	 * out of the supplied {@link io.evitadb.index.component.loader.LoadContext} and builds the
	 * index via the standard data-loading constructor.
	 *
	 * @return the immutable reload plan for this subclass
	 */
	@Nonnull
	public static IndexReloadPlan reloadPlan() {
		return GLOBAL_RELOAD_PLAN;
	}

	private static final IndexReloadPlan GLOBAL_RELOAD_PLAN = IndexReloadPlan.builder()
		.add(new AttributeIndexLoader())
		.add(new PriceSuperIndexLoader())
		.add(new HierarchyIndexLoader())
		.add(new FacetIndexLoader())
		.build((bundles, context) -> {
			final LoadedComponentBundle.AttributeIndexes attributes =
				(LoadedComponentBundle.AttributeIndexes) bundles.get(LoadedComponentBundle.AttributeIndexes.class);
			final LoadedComponentBundle.PriceSuper prices =
				(LoadedComponentBundle.PriceSuper) bundles.get(LoadedComponentBundle.PriceSuper.class);
			final LoadedComponentBundle.Hierarchy hierarchy =
				(LoadedComponentBundle.Hierarchy) bundles.get(LoadedComponentBundle.Hierarchy.class);
			final LoadedComponentBundle.Facet facet =
				(LoadedComponentBundle.Facet) bundles.get(LoadedComponentBundle.Facet.class);
			final io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart manifest =
				context.entityIndexStoragePart();
			return new GlobalEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				context.version(),
				context.entityIds(),
				context.entityIdsByLanguage(),
				new EntityAttributeIndex(
					context.entitySchema().getName(),
					attributes.uniqueIndexes(),
					attributes.filterIndexes(),
					attributes.uniqueViewIndexes(),
					attributes.sortIndexes(),
					attributes.chainIndexes(),
					attributes.sharedValueIndexes(),
					attributes.sharedRangeIndexes()
				),
				new PriceSuperIndex(prices.priceIndexes()),
				hierarchy.hierarchyIndex(),
				facet.facetIndex(),
				// the trigram indexes are derived state with no on-disk footprint of their own, so the "load" is a
				// rebuild from the shared value trees that have just come back with their value ids
				TrigramIndex.rebuildAll(
					context.entitySchema(),
					manifest.getEntityIndexKey().scope(),
					attributes.sharedValueIndexes()
				),
				// loaded from disk — the counters start over, which is what "since catalog load" means, and are
				// not opened at all when the server does not track usage statistics
				context.createActivity()
			);
		});

	/*
		TRANSACTIONAL MEMORY IMPLEMENTATION
	 */

	@Nonnull
	@Override
	public GlobalEntityIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// safe: AttributeIndex#createCopy preserves the subclass identity established by EntityIndex#isReferenceScoped
		return new GlobalEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			(EntityAttributeIndex) transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.priceIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.trigramIndex),
			// the very same holder, not a copy: this is one logical index carried into the next catalog version
			getActivity()
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// the price index is removed by the component-loop inside the super call — no extra hop
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() && this.priceIndex.isPriceIndexEmpty();
	}

	/*
		SUBSTRING INDEX MAINTENANCE

		The four filter-attribute primitives are intercepted here rather than in `AttributeIndex` because the decision
		is per ENTITY INDEX: only the global index hosts trigram postings, and the reduced indexes must keep reaching
		the untouched base implementation. Each override forwards the very same call the base makes, plus the sink
		that learns which distinct values the write brought into or took out of existence.
	 */

	/**
	 * {@inheritDoc}
	 *
	 * The global index additionally reports the distinct values this write brings into existence to the attribute's
	 * {@link TrigramIndex}, creating that index — and switching the shared value tree's value id column on — on the
	 * first write to an attribute declaring {@link AttributeFilterAccelerator#SUBSTRING_SEARCH}. A write to an
	 * attribute that declares none drops the accelerator a withdrawal left behind before delegating to the base
	 * implementation.
	 */
	@Override
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId,
		boolean foldedUnique
	) {
		if (maintainsNoTrigramIndex(referenceSchema, attributeSchema)) {
			reconcileTrigramIndexAbsence(referenceSchema, attributeSchema, locale);
			super.insertFilterAttribute(
				referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
			return;
		}
		this.attributeIndex.insertFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique,
			obtainTrigramIndex(referenceSchema, attributeSchema, allowedLocales, locale, value)
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * The global index additionally reports the distinct values this write takes out of existence to the attribute's
	 * {@link TrigramIndex}, and drops that index when the removal emptied — and therefore dropped — the shared value
	 * tree its postings are keyed by. A write to an attribute declaring no
	 * {@link AttributeFilterAccelerator#SUBSTRING_SEARCH} drops the accelerator a withdrawal left behind before
	 * delegating to the base implementation.
	 */
	@Override
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		if (maintainsNoTrigramIndex(referenceSchema, attributeSchema)) {
			reconcileTrigramIndexAbsence(referenceSchema, attributeSchema, locale);
			super.removeFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
			return;
		}
		final AttributeIndexKey lookupKey = AttributeIndex.createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		this.attributeIndex.removeFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId,
			this.trigramIndex.get(lookupKey)
		);
		dropTrigramIndexWithItsSharedValueTree(lookupKey);
	}

	/**
	 * {@inheritDoc}
	 *
	 * The global index additionally reports the distinct values this write brings into existence to the attribute's
	 * {@link TrigramIndex}, creating that index — and switching the shared value tree's value id column on — on the
	 * first write to an attribute declaring {@link AttributeFilterAccelerator#SUBSTRING_SEARCH}. A write to an
	 * attribute that declares none drops the accelerator a withdrawal left behind before delegating to the base
	 * implementation.
	 */
	@Override
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId,
		boolean foldedUnique
	) {
		if (maintainsNoTrigramIndex(referenceSchema, attributeSchema)) {
			reconcileTrigramIndexAbsence(referenceSchema, attributeSchema, locale);
			super.addDeltaFilterAttribute(
				referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
			return;
		}
		this.attributeIndex.addDeltaFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique,
			obtainTrigramIndex(referenceSchema, attributeSchema, allowedLocales, locale, value)
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * The global index additionally reports the distinct values this write takes out of existence to the attribute's
	 * {@link TrigramIndex}, and drops that index when the removal emptied — and therefore dropped — the shared value
	 * tree its postings are keyed by. A write to an attribute declaring no
	 * {@link AttributeFilterAccelerator#SUBSTRING_SEARCH} drops the accelerator a withdrawal left behind before
	 * delegating to the base implementation.
	 */
	@Override
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		if (maintainsNoTrigramIndex(referenceSchema, attributeSchema)) {
			reconcileTrigramIndexAbsence(referenceSchema, attributeSchema, locale);
			super.removeDeltaFilterAttribute(
				referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
			return;
		}
		final AttributeIndexKey lookupKey = AttributeIndex.createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value);
		this.attributeIndex.removeDeltaFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId,
			this.trigramIndex.get(lookupKey)
		);
		dropTrigramIndexWithItsSharedValueTree(lookupKey);
	}

	/**
	 * Returns the substring-search accelerator of one attribute and locale, when this index maintains one.
	 *
	 * The accelerator is read at WRITE time, not here: this accessor answers from the map alone, and the map is
	 * reconciled with the schema by the next write to that attribute
	 * ({@link #reconcileTrigramIndexAbsence(ReferenceSchemaContract, AttributeSchemaContract, Locale)}). An index whose
	 * accelerator was withdrawn is therefore handed out until that write happens — never afterwards, and never with
	 * postings that drifted from the tree in the meantime, because the write that ends the drift is the same one that
	 * drops the entry.
	 *
	 * @param attributeIndexKey the attribute and locale to look up
	 * @return the trigram index, or `null` when the attribute has never been written to, when its
	 * {@link AttributeFilterAccelerator#SUBSTRING_SEARCH} accelerator was withdrawn and written to since, or when its
	 * shared value
	 * tree has been dropped
	 */
	@Nullable
	public TrigramIndex getTrigramIndex(@Nonnull AttributeIndexKey attributeIndexKey) {
		return this.trigramIndex.get(attributeIndexKey);
	}

	/**
	 * @return a snapshot of the attribute and locale combinations this index currently keeps a substring-search
	 * accelerator for — a copy rather than a live view, so a caller holding it cannot observe a later write
	 */
	@Nonnull
	public Set<AttributeIndexKey> getTrigramIndexKeys() {
		return Set.copyOf(this.trigramIndex.keySet());
	}

	/**
	 * Decides whether the write about to happen can leave the trigram index alone, and refuses the one shape this
	 * index could accept but the load path could not give back.
	 *
	 * Stated in the negative because every caller is a guard clause taking the do-nothing branch, and `maintains` is
	 * kept in the name deliberately: this reports what the SCHEMA asks this index to maintain, never whether an entry
	 * happens to be in the map - a stale entry for an accelerator since withdrawn is exactly what
	 * {@link #reconcileTrigramIndexAbsence} then clears.
	 *
	 * The load path rebuilds only entity-level accelerators: {@link TrigramIndex#rebuildAll} skips every
	 * reference-scoped key, because it resolves attribute names against the entity schema alone and a reference
	 * attribute may legitimately share a name with an entity one. Nothing here would skip such a key, so a
	 * reference-scoped write would build postings the next catalog open silently discards — substring queries would
	 * under-report after a restart with nothing saying why. The schema layer makes that unreachable today
	 * (`AbstractAttributeSchemaMutation#verifyAcceleratorNotOnReferenceAttribute` refuses a filter accelerator on any
	 * reference attribute), and it documents the refusal as a restriction liftable once the index learns to host
	 * reference attribute values. The premise below is what makes the day it is lifted loud rather than silent: the
	 * two guards are one decision expressed twice, and this is the half that fails fast instead of dropping data.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the attribute being written
	 * @return `true` when this index keeps NO trigram index for that attribute in its own scope
	 */
	private boolean maintainsNoTrigramIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema
	) {
		if (!attributeSchema.getAcceleratorsInScope(this.indexKey.scope())
			.contains(AttributeFilterAccelerator.SUBSTRING_SEARCH)) {
			return true;
		}
		// an explicit throw rather than a premise with a message supplier: the message names the reference, and only
		// inside this branch is `referenceSchema` provably non-null. A supplier handed to Assert runs exactly when the
		// premise fails - i.e. when it is non-null - but nothing states that at the dereference itself
		if (referenceSchema != null) {
			throw new GenericEvitaInternalError(
				"Attribute `" + attributeSchema.getName() + "` of reference `" + referenceSchema.getName() +
					"` declares the SUBSTRING filter accelerator, which the schema layer refuses on a reference " +
					"attribute. Maintaining it here would build postings that the load path discards, so lifting " +
					"that schema restriction means teaching TrigramIndex#rebuildAll about reference-scoped keys first."
			);
		}
		return false;
	}

	/**
	 * Drops the trigram index of an attribute this index no longer maintains one for — the reconciliation every write
	 * that takes the non-maintaining branch performs before delegating.
	 *
	 * Withdrawing a filter accelerator from a POPULATED collection is deliberately legal (the schema boundary refuses
	 * additions only, see {@link EntityCollection}), and the accelerator is read on every write, so the withdrawal takes
	 * effect at the very next one. Without this the entry would survive a gate that can never open again: nothing
	 * would maintain it, {@link #dropTrigramIndexWithItsSharedValueTree} sits on the branch the write no longer takes,
	 * and the index would keep its heap, keep answering {@link #getTrigramIndex} with postings drifting further from
	 * the tree with every write, and — once the tree emptied out and the accelerator came back — be found by
	 * {@link #obtainTrigramIndex} as an entry whose tree no longer mints ids, failing an ordinary entity upsert on the
	 * tree's own premise.
	 *
	 * The map is empty for every collection that declares the accelerator nowhere, which is the overwhelming majority:
	 * those pay one boolean read here and never compute a key at all. The membership test before the removal keeps a
	 * write to a plain attribute of a capable collection from opening a transactional layer over a map it does not
	 * change.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being written
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 */
	private void reconcileTrigramIndexAbsence(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		if (this.trigramIndex.isEmpty()) {
			return;
		}
		final AttributeIndexKey lookupKey = AttributeIndex.createAttributeKey(referenceSchema, attributeSchema, locale);
		// the containsKey is NOT the redundant guard static analysis reports it as: `remove` on a TransactionalMap
		// goes through getOrCreateTransactionalMemoryLayer, so calling it unconditionally would allocate a MapChanges
		// layer - and a commit-time merge of it - on every write to a NON-accelerated attribute of any collection that
		// accelerates even one. `containsKey` takes the read-only getTransactionalMemoryLayerIfExists path, so the
		// layer is created only when there is really something to drop
		//noinspection RedundantCollectionOperation
		if (this.trigramIndex.containsKey(lookupKey)) {
			this.trigramIndex.remove(lookupKey);
		}
	}

	/**
	 * Resolves the trigram index the write about to happen must report to, creating it - and switching the shared
	 * value tree's id column on - the first time the attribute is written to.
	 *
	 * Attaching the value id consumer BEFORE the write is what makes the first value of an attribute countable: the
	 * tree stamps a bucket at the moment it creates it, so a consumer attaching afterwards would find that one value
	 * unstamped. It also makes the attach itself legal, because the tree is created empty here and the id column may
	 * only be switched on while it still is.
	 *
	 * The attach is reached only when the map holds no index yet, which is what keeps this off the steady-state write
	 * path: an entry in the map exists only because the attach that created it succeeded, and the entry is dropped
	 * both in lockstep with the tree it belongs to ({@link #dropTrigramIndexWithItsSharedValueTree}) and with the
	 * accelerator that asked for it
	 * ({@link #reconcileTrigramIndexAbsence(ReferenceSchemaContract, AttributeSchemaContract, Locale)}) — the two
	 * halves of the same invariant, and both are needed, since a withdrawn accelerator makes the drop hook unreachable.
	 * A tree that somehow lost its ids while its entry survived is caught loudly by the tree's own premise on the very
	 * next value born, rather than silently indexing everything under the unassigned id.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being written
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the value being written, which decides the key's locale for a localized attribute
	 * @return the trigram index of that attribute and locale
	 */
	@Nonnull
	private TrigramIndex obtainTrigramIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value
	) {
		final AttributeIndexKey lookupKey = AttributeIndex.createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value
		);
		final TrigramIndex existing = this.trigramIndex.get(lookupKey);
		if (existing != null) {
			return existing;
		}
		this.attributeIndex.attachSharedValueIdConsumer(
			lookupKey, attributeSchema, TrigramIndex.VALUE_ID_CONSUMER_NAME
		);
		final TrigramIndex created = new TrigramIndex(lookupKey);
		this.trigramIndex.put(lookupKey, created);
		return created;
	}

	/**
	 * Drops the trigram index of `lookupKey` when the removal that just ran emptied — and therefore dropped — the
	 * shared value tree it indexes.
	 *
	 * The two structures have to leave together: the postings are keyed by that tree's value ids, and a tree created
	 * again later starts its id sequence over, so a surviving trigram index would post yesterday's ids against
	 * tomorrow's values.
	 *
	 * @param lookupKey the attribute and locale whose shared value tree may have just been dropped
	 */
	private void dropTrigramIndexWithItsSharedValueTree(@Nonnull AttributeIndexKey lookupKey) {
		if (this.attributeIndex.getFilterIndex(lookupKey) == null) {
			this.trigramIndex.remove(lookupKey);
		}
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the priceIndex and trigramIndex slots
		return getBaseHeapSizeInBytes(2L * layout.referenceSize())
			+ this.priceIndex.getHeapSizeInBytes()
			// the price component this class registers, holding the price index alone
			+ layout.sizeOfObject(layout.referenceSize())
			// the trigram map charges a slot per key and nothing for the key itself: an entry exists only alongside the
			// shared value tree of the same attribute, filed under the very instance that tree is filed under, and the
			// attribute index charges that instance in full (see "Which map charges a key" on
			// AttributeIndex#getHeapSizeInBytes) - charging it here as well would report one object twice in one figure
			+ this.trigramIndex.getHeapSizeInBytes(key -> 0L, TrigramIndex::getHeapSizeInBytes)
			// the trigram component this class registers, holding the map alone
			+ layout.sizeOfObject(layout.referenceSize());
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
