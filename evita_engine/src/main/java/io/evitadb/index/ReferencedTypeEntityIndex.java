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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ReferenceAttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.component.AttributeCardinalityIndexMapComponent;
import io.evitadb.index.component.HistogramIndexMapComponent;
import io.evitadb.index.component.PriceIndexComponent;
import io.evitadb.index.component.ReferenceTypeCardinalityComponent;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import lombok.experimental.Delegate;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.util.ReflectionUtils;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.index.attribute.AttributeIndex.createAttributeKey;
import static java.util.Optional.ofNullable;

/**
 * Referenced type entity index exists once per {@link EntitySchemaContract#getReference(String)} and indexes not
 * the owner entity primary key, but the referenced entity primary key with attributes that lay on the reference
 * relation. We need this index to be able to navigate to {@link AbstractReducedEntityIndex} that were specially created to
 * speed up queries that involve the references.
 *
 * This index doesn't maintain the prices of entities — only the attributes present on relations.
 *
 * **Histogram support** — as of 2026 this index also implements {@link HistogramCapableEntityIndex}, which means
 * it stores per-reference histogram data in a `histogramIndexes` map ({@code Map<String, HistogramIndex>}). These
 * histogram indexes back the {@link io.evitadb.api.query.require.ReferenceHistogramStatistics} computation for
 * non-grouped references: the accumulator calls {@link #getHistogramFilterIndex(String, Locale)} to obtain the
 * source {@link io.evitadb.index.attribute.FilterIndex} and runs histogram bucket computation against it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedTypeEntityIndex extends EntityIndex implements
	VoidTransactionMemoryProducer<ReferencedTypeEntityIndex>,
	IndexDataStructure,
	HistogramCapableEntityIndex
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
		(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(method, AbstractReducedEntityIndex.class, "getId"),
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
				proxyState.entitySchema(),
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
	private final ReferenceTypeCardinalityIndex indexPrimaryKeyCardinality;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link FilterIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes;
	/**
	 * Per-histogram-name index storing bucketed histogram data (filter indexes + cardinality tracking)
	 * for all locale variants of each histogram definition.
	 */
	@Nonnull private final TransactionalMap<String, HistogramIndex> histogramIndexes;

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
		this.indexPrimaryKeyCardinality = new ReferenceTypeCardinalityIndex();
		this.cardinalityIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(16), AttributeCardinalityIndex.class, Function.identity()
		);
		this.histogramIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), HistogramIndex.class, Function.identity()
		);
		registerSubclassComponents();
		// fresh empty index — every component contributes an empty manifest, so the baseline
		// captured here is the immutable empty set, preventing spurious manifest emits
		captureOriginalsFromComponents();
	}

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull ReferenceAttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull ReferenceTypeCardinalityIndex indexPrimaryKeyCardinality,
		@Nonnull Map<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes,
		@Nonnull Map<String, HistogramIndex> histogramIndexes
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, VoidPriceIndex.INSTANCE
		);
		this.indexPrimaryKeyCardinality = indexPrimaryKeyCardinality;
		this.cardinalityIndexes = new TransactionalMap<>(
			cardinalityIndexes, AttributeCardinalityIndex.class, Function.identity()
		);
		this.histogramIndexes = new TransactionalMap<>(
			histogramIndexes, HistogramIndex.class, Function.identity()
		);
		registerSubclassComponents();
		// re-capture the change-detection baseline from the components now that every subclass
		// sub-index map is populated — this replaces the former collectAttributeIndexStorageKeys()
		// helper which only patched in CARDINALITY keys and ignored histograms
		captureOriginalsFromComponents();
	}

	/**
	 * Retrieves the reference name derived from the discriminator of the entity index key.
	 *
	 * @return the reference name as a non-null string
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) Objects.requireNonNull(getIndexKey().discriminator());
	}

	@Nonnull
	@Override
	public <S extends PriceIndexContract> S getPriceIndex() {
		//noinspection unchecked
		return (S) this.priceIndex;
	}

	@Override
	public boolean isEmpty() {
		// null check required: parent constructor calls isEmpty() before subclass fields are initialized
		return super.isEmpty() && this.indexPrimaryKeyCardinality.isEmpty() && this.histogramIndexes.isEmpty();
	}

	/**
	 * Returns an unmodifiable view of all referenced entity primary keys tracked by this index. For a
	 * `REFERENCED_GROUP_ENTITY_TYPE` index these are the group entity PKs; for a `REFERENCED_ENTITY_TYPE`
	 * index these are the referenced (facet) entity PKs.
	 *
	 * Used by ReevaluateExpressionExecutor to iterate all groups when resolving group PKs
	 * for {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE} dependencies on grouped references.
	 *
	 * @return unmodifiable set of all tracked referenced entity primary keys
	 */
	@Nonnull
	public Set<Integer> getAllTrackedReferencedEntityPrimaryKeys() {
		return this.indexPrimaryKeyCardinality.getAllTrackedReferencedEntityPrimaryKeys();
	}

	/**
	 * Returns all referenced entity primary keys tracked by this index as a {@link Bitmap}. This is the
	 * bitmap-typed companion to {@link #getAllTrackedReferencedEntityPrimaryKeys()}. Used at query time
	 * by histogram boundary resolution to intersect with the source attribute's
	 * {@link FilterIndex#getRecordsEqualToFormula} bitmap.
	 *
	 * @return bitmap of all tracked referenced entity primary keys
	 */
	@Nonnull
	public Bitmap getAllReferencedPrimaryKeys() {
		return this.indexPrimaryKeyCardinality.getAllTrackedReferencedEntityPrimaryKeysAsBitmap();
	}

	/**
	 * Retrieves all reference indexes associated with the given referenced entity primary key.
	 *
	 * @param referencedEntityPrimaryKey the primary key of the referenced entity for which the indexes are to be retrieved
	 * @return an array of all reference indexes primary keys associated with the specified referenced entity primary key
	 */
	@Nonnull
	public int[] getAllReferenceIndexes(int referencedEntityPrimaryKey) {
		return this.indexPrimaryKeyCardinality.getAllReferenceIndexes(referencedEntityPrimaryKey);
	}

	/**
	 * Returns the referenced entity primary keys (forward-mapping keys) whose reduced-index PK bitmaps
	 * overlap with the given set of index primary keys. This is the reverse lookup of
	 * {@link #getIndexPrimaryKeys(RoaringBitmap)}.
	 *
	 * For a `REFERENCED_GROUP_ENTITY_TYPE` index this translates reduced-group-index PKs back to
	 * group entity primary keys.
	 *
	 * @param indexPrimaryKeys bitmap of reduced-index primary keys to look up
	 * @return bitmap of referenced entity primary keys whose index PKs overlap with the input
	 */
	@Nonnull
	public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys) {
		return this.indexPrimaryKeyCardinality.getReferencedPrimaryKeysForIndexPks(indexPrimaryKeys);
	}

	/**
	 * Registers the four subclass-owned {@link io.evitadb.index.component.IndexComponent}
	 * adapters into the parent {@link EntityIndex#addComponent} loop so cardinality / histogram /
	 * reference-type-cardinality flush, reset and remove-layer all flow through the uniform
	 * component path. The void price component preserves the parity with peer subclasses — its
	 * `getPriceListAndCurrencyIndexes()` returns the empty collection so the manifest contribution
	 * is trivially empty.
	 *
	 * Called from every constructor right after the subclass fields are populated and before
	 * {@link #captureOriginalsFromComponents()}.
	 */
	private void registerSubclassComponents() {
		// RTEI never has live prices, but the void price component is registered for shape
		// consistency with peer subclasses — it is a no-op on every loop step
		addComponent(new PriceIndexComponent(VoidPriceIndex.INSTANCE));
		addComponent(new AttributeCardinalityIndexMapComponent(this.cardinalityIndexes, this.indexKey));
		addComponent(new HistogramIndexMapComponent(this.histogramIndexes, this.indexKey));
		addComponent(
			new ReferenceTypeCardinalityComponent(this.indexPrimaryKeyCardinality, getReferenceName())
		);
	}

	/**
	 * Single-arg version is unsupported - use {@link #insertPrimaryKeyIfMissing(int, int)} instead.
	 *
	 * @throws UnsupportedOperationException always
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
		if (this.indexPrimaryKeyCardinality.addRecord(indexPrimaryKey, referencedEntityPrimaryKey)) {
			super.insertPrimaryKeyIfMissing(indexPrimaryKey);
		}
		return true;
	}

	/**
	 * Single-arg version is unsupported - use {@link #removePrimaryKey(int, int)} instead.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public boolean removePrimaryKey(int indexPrimaryKey) {
		throw new UnsupportedOperationException(
			"Use removePrimaryKey(int, int) instead!"
		);
	}

	/**
	 * This method should be called instead of {@link #removePrimaryKey(int)} because it tracks the cardinality
	 * both of the indexed primary key and the referenced primary key.
	 */
	public boolean removePrimaryKey(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		if (this.indexPrimaryKeyCardinality.removeRecord(indexPrimaryKey, referencedEntityPrimaryKey)) {
			super.removePrimaryKey(indexPrimaryKey);
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
		return this.indexPrimaryKeyCardinality.getIndexPrimaryKeys(referencedEntityPrimaryKeys);
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
		final AttributeCardinalityIndex theCardinalityIndex = this.cardinalityIndexes.computeIfAbsent(
			createAttributeKey(referenceSchema, attributeSchema, allowedLocales, locale, value),
			lookupKey -> new AttributeCardinalityIndex(attributeSchema.getPlainType())
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
		// first retrieve the cardinality index for given attribute
		final AttributeIndexKey attributeKey = createAttributeKey(referenceSchema, attributeSchema, allowedLocales, locale, value);
		final AttributeCardinalityIndex theCardinalityIndex = this.cardinalityIndexes.get(attributeKey);

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
			final AttributeCardinalityIndex removedIndex = this.cardinalityIndexes.remove(attributeKey);
			if (removedIndex != null) {
				ofNullable(getTransactionalLayerMaintainer())
					.ifPresent(removedIndex::removeLayer);
			}
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

	/**
	 * Returns the {@link HistogramIndex} for the given histogram name, or `null` if none exists.
	 *
	 * @param histogramName the name of the histogram definition
	 * @return the histogram index, or `null`
	 */
	@Nullable
	@Override
	public HistogramIndex getHistogramIndex(@Nonnull String histogramName) {
		return this.histogramIndexes.get(histogramName);
	}

	@Nullable
	@Override
	public FilterIndex getHistogramFilterIndex(@Nonnull String histogramName, @Nullable Locale locale) {
		final HistogramIndex histogramIndex = this.histogramIndexes.get(histogramName);
		return histogramIndex == null ? null : histogramIndex.getFilterIndex(locale);
	}

	@Override
	public void insertHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Number value,
		int ownerPK,
		@Nonnull Class<? extends Serializable> valueType
	) {
		final String referenceName = getReferenceName();
		final HistogramIndex histogramIndex = this.histogramIndexes.computeIfAbsent(
			histogramName,
			k -> locale != null
				? new LocalizedHistogramIndex(histogramName, referenceName, valueType)
				: new SimpleHistogramIndex(histogramName, referenceName, valueType)
		);
		histogramIndex.insertValue(locale, value, ownerPK);
		this.dirty.setToTrue();
	}

	@Override
	public void removeHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final HistogramIndex histogramIndex = this.histogramIndexes.get(histogramName);
		Assert.isPremiseValid(
			histogramIndex != null,
			() -> "Histogram index for histogram " + histogramName + " not found."
		);
		histogramIndex.removeValue(locale, value, ownerPK);
		this.dirty.setToTrue();
		// if the histogram index is now empty, remove it from the map and clean up transactional layers
		if (histogramIndex.isEmpty()) {
			final HistogramIndex removed = this.histogramIndexes.remove(histogramName);
			if (removed != null) {
				ofNullable(getTransactionalLayerMaintainer()).ifPresent(removed::removeLayer);
			}
		}
	}

	@Override
	public String toString() {
		return "ReducedEntityTypeIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) +
			", histograms=" + this.histogramIndexes.size() + ")";
	}

	@Nonnull
	@Override
	public ReferencedTypeEntityIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// AttributeIndex#createCopy preserves the subclass identity — the merged copy of a
		// ReferenceAttributeIndex stays a ReferenceAttributeIndex.
		return new ReferencedTypeEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			(ReferenceAttributeIndex) transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexPrimaryKeyCardinality),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityIndexes),
			transactionalLayer.getStateCopyWithCommittedChanges(this.histogramIndexes)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// drop our own diff layer (no-op for VoidTransactionMemoryProducer) and propagate the
		// recursive remove into every registered component via the base method — the base loop
		// covers the AttributeCardinality, Histogram and ReferenceTypeCardinality components too
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
	}

	/**
	 * Immutable record representing the state and schema of a proxy for {@link ReferencedTypeEntityIndex}.
	 * This proxy state is specifically designed to throw a {@link ReferenceNotIndexedException} for unhandled methods,
	 * ensuring that any unexpected or unsupported usage is explicitly flagged during execution.
	 *
	 * This record encapsulates the {@link EntitySchemaContract}, which defines the schema of the entity
	 * associated with the proxy index. The schema is used to validate the entity structure and its associated data.
	 *
	 * This class implements {@link Serializable} to support serialization and deserialization, aiding in
	 * transferring or persisting instances of this proxy state as needed.
	 *
	 * Fields:
	 * - `entitySchema`: The schema contract for the entity that the proxy index is associated with.
	 *
	 * The record is used strictly as a supporting structure within the {@link ReferencedTypeEntityIndex} to manage
	 * scenarios where the entity index is in a "throwing" state for specific unsupported operations.
	 *
	 * See also:
	 * - {@link ReferencedTypeEntityIndex#createThrowingStub(EntitySchemaContract, EntityIndexKey)} for creating a proxy instance.
	 * - {@link ReferenceNotIndexedException} for the type of exception thrown by this proxy in unhandled cases.
	 */
	private record ReferencedTypeEntityIndexProxyStateThrowing(@Nonnull EntitySchemaContract entitySchema)
		implements Serializable {
		@Serial private static final long serialVersionUID = 5594003658214725555L;
	}

}
