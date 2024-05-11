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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Entity decorator class envelopes any {@link Entity} and allows to filter out properties that are not passing predicate
 * conditions. This allows us to reuse rich {@link Entity} objects from the cache even if clients requests thinner ones.
 * For example if we have full-blown entity in our cache and client asks for entity in English language, we can use
 * entity decorator to hide all attributes that refers to other languages than English one.
 *
 * We try to keep evitaDB responses consistent and provide only those type of data that were really requested in the query
 * and avoid inconsistent situations that richer data are returned just because the entity was found in cache in a form
 * that more than fulfills the request.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class EntityDecorator implements SealedEntity {
	@Serial private static final long serialVersionUID = -3641248774594311898L;
	/**
	 * Contains reference to the (possibly richer than requested) entity object.
	 */
	@Getter private final Entity delegate;
	/**
	 * Copy of the {@link EvitaRequest#getAlignedNow()} that was actual when entity was fetched from the database.
	 */
	@Getter private final OffsetDateTime alignedNow;
	/**
	 * Contains actual entity schema.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * This predicate filters out non-fetched locales.
	 */
	private final LocaleSerializablePredicate localePredicate;
	/**
	 * This predicate filters out access to the hierarchy parent that were not fetched in query.
	 */
	private final HierarchySerializablePredicate hierarchyPredicate;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	private final AttributeValueSerializablePredicate attributePredicate;
	/**
	 * This predicate filters out associated data that were not fetched in query.
	 */
	private final AssociatedDataValueSerializablePredicate associatedDataPredicate;
	/**
	 * This predicate filters out references that were not fetched in query.
	 */
	private final ReferenceContractSerializablePredicate referencePredicate;
	/**
	 * This predicate filters out prices that were not fetched in query.
	 */
	private final PriceContractSerializablePredicate pricePredicate;
	/**
	 * Contains body of the parent entity. The body is accessible only when the input request (query) contains
	 * requirements for fetching entity (i.e. {@link EntityFetch}) in the {@link HierarchyContent} requirement.
	 */
	private final EntityClassifierWithParent parentEntity;
	/**
	 * Optimization that ensures that expensive attributes filtering using predicates happens only once.
	 */
	private List<AttributeValue> filteredAttributes;
	/**
	 * Optimization that ensures that expensive associated data filtering using predicates happens only once.
	 */
	private List<AssociatedDataValue> filteredAssociatedData;
	/**
	 * Optimization that ensures that expensive reference filtering using predicates happens only once.
	 */
	private Map<ReferenceKey, ReferenceContract> filteredReferences;
	/**
	 * Optimization that ensures that expensive prices filtering using predicates happens only once.
	 */
	private List<PriceContract> filteredPrices;

	/**
	 * Method sorts and filters the data in `references` using `referenceFilter` and `referenceComparator` but only
	 * within bounds specified by `start` (inclusive) and `end` (exclusive).
	 */
	private static void sortAndFilterSubList(
		int entityPrimaryKey,
		@Nonnull ReferenceDecorator[] references,
		@Nullable ReferenceComparator referenceComparator,
		@Nullable BiPredicate<Integer, ReferenceDecorator> referenceFilter,
		int start,
		int end
	) {
		if (referenceComparator != null) {
			int nonSortedReferenceCount = 0;
			do {
				if (referenceFilter == null) {
					Arrays.sort(references, start, end, referenceComparator);
				} else {
					final ReferenceDecorator[] filteredReferences = Arrays.stream(references, start, end)
						.filter(it -> referenceFilter.test(entityPrimaryKey, it))
						.toArray(ReferenceDecorator[]::new);

					for (int i = start; i < end; i++) {
						final int filteredIndex = i - start;
						references[i] = filteredReferences.length > filteredIndex ? filteredReferences[filteredIndex] : null;
					}
					Arrays.sort(references, start, end, referenceComparator);
				}
				nonSortedReferenceCount = referenceComparator.getNonSortedReferenceCount();
				start = start + (end - nonSortedReferenceCount);
				referenceComparator = referenceComparator.getNextComparator();
			} while (referenceComparator != null && nonSortedReferenceCount > 0);
		}

	}

	/**
	 * Creates wrapper around {@link Entity} that filters existing data according passed predicates (which are constructed
	 * to match query that is used to retrieve the decorator).
	 *
	 * @param delegate                fully or partially loaded entity - it's usually wider than decorator (may be even complete), delegate
	 *                                might be obtained from shared global cache
	 * @param entitySchema            schema of the delegate entity
	 * @param parentEntity            object of the parentEntity
	 * @param localePredicate         predicate used to filter out locales to match input query
	 * @param hierarchyPredicate      predicate used to filter out parent to match input query
	 * @param attributePredicate      predicate used to filter out attributes to match input query
	 * @param associatedDataPredicate predicate used to filter out associated data to match input query
	 * @param referencePredicate      predicate used to filter out references to match input query
	 * @param pricePredicate          predicate used to filter out prices to match input query
	 */
	public EntityDecorator(
		@Nonnull Entity delegate,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow
	) {
		this.delegate = delegate;
		this.entitySchema = entitySchema;
		this.parentEntity = parentEntity;
		this.localePredicate = localePredicate;
		this.hierarchyPredicate = hierarchyPredicate;
		this.attributePredicate = attributePredicate;
		this.associatedDataPredicate = associatedDataPredicate;
		this.referencePredicate = referencePredicate;
		this.pricePredicate = pricePredicate;
		this.alignedNow = alignedNow;
	}

	/**
	 * Creates wrapper around {@link Entity} that filters existing data according passed predicates (which are constructed
	 * to match query that is used to retrieve the decorator).
	 *
	 * @param decorator               decorator with fully or partially loaded entity - it's usually wider than
	 *                                decorator (may be even complete)
	 * @param parentEntity            object of the parentEntity
	 * @param localePredicate         predicate used to filter out locales to match input query
	 * @param hierarchyPredicate      predicate used to filter out parent to match input query
	 * @param attributePredicate      predicate used to filter out attributes to match input query
	 * @param associatedDataPredicate predicate used to filter out associated data to match input query
	 * @param referencePredicate      predicate used to filter out references to match input query
	 * @param pricePredicate          predicate used to filter out prices to match input query
	 */
	public EntityDecorator(
		@Nonnull EntityDecorator decorator,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow
	) {
		this.delegate = decorator.getDelegate();
		this.parentEntity = ofNullable(parentEntity)
			.or(() -> of(delegate).filter(Entity::parentAvailable).flatMap(Entity::getParentEntity))
			.orElse(null);
		this.entitySchema = decorator.getSchema();
		this.localePredicate = localePredicate;
		this.hierarchyPredicate = hierarchyPredicate;
		this.attributePredicate = attributePredicate;
		this.associatedDataPredicate = associatedDataPredicate;
		this.referencePredicate = referencePredicate;
		this.pricePredicate = pricePredicate;
		this.alignedNow = alignedNow;
		this.filteredReferences = decorator.referencesAvailable() ?
			decorator.getReferences()
				.stream()
				.filter(referencePredicate)
				.map(reference ->
					// prefer the instances from decorator, since they may have initialized the pointers to rich entities
					ofNullable(decorator.filteredReferences.get(reference.getReferenceKey()))
						.orElseGet(() -> this.delegate.getReference(reference.getReferenceKey()).orElse(null))
				)
				.filter(Objects::nonNull)
				// the listing from decorator is also properly sorted, so we don't need to sort it again
				.collect(
					Collectors.toMap(
						ReferenceContract::getReferenceKey,
						Function.identity(),
						(o, o2) -> {
							throw new EvitaInvalidUsageException("Sanity check: " + o + ", " + o2);
						},
						LinkedHashMap::new
					)
				) : Collections.emptyMap();
	}

	/**
	 * Creates wrapper around {@link Entity} that filters existing data according passed predicates (which are constructed
	 * to match query that is used to retrieve the decorator).
	 *
	 * @param entity                  fully or partially loaded entity - it's usually wider than decorator (may be even complete), decorator
	 *                                might be obtained from shared global cache
	 * @param parentEntity            object of the parentEntity
	 * @param referenceFetcher        fetcher that can be used for fetching, filtering and ordering referenced
	 *                                entities / groups
	 */
	public EntityDecorator(
		@Nonnull EntityDecorator entity,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		this(
			entity.getDelegate(),
			entity.getSchema(),
			parentEntity,
			entity.localePredicate,
			entity.hierarchyPredicate,
			entity.attributePredicate,
			entity.associatedDataPredicate,
			entity.referencePredicate,
			entity.pricePredicate,
			entity.alignedNow,
			referenceFetcher
		);
	}

	/**
	 * Creates wrapper around {@link Entity} that filters existing data according passed predicates (which are constructed
	 * to match query that is used to retrieve the decorator).
	 *
	 * @param entity                  fully or partially loaded entity - it's usually wider than decorator (may be even complete), decorator
	 *                                might be obtained from shared global cache
	 * @param parentEntity            object of the parentEntity
	 * @param localePredicate         predicate used to filter out locales to match input query
	 * @param attributePredicate      predicate used to filter out attributes to match input query
	 * @param associatedDataPredicate predicate used to filter out associated data to match input query
	 * @param referencePredicate      predicate used to filter out references to match input query
	 * @param pricePredicate          predicate used to filter out prices to match input query
	 * @param referenceFetcher        fetcher that can be used for fetching, filtering and ordering referenced
	 *                                entities / groups
	 */
	public EntityDecorator(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		@Nonnull ReferenceFetcher referenceFetcher
	) {
		this.delegate = entity;
		this.entitySchema = entitySchema;
		this.parentEntity = parentEntity;
		this.localePredicate = localePredicate;
		this.hierarchyPredicate = hierarchyPredicate;
		this.attributePredicate = attributePredicate;
		this.associatedDataPredicate = associatedDataPredicate;
		this.referencePredicate = referencePredicate;
		this.pricePredicate = pricePredicate;
		this.alignedNow = alignedNow;

		final Collection<ReferenceContract> originalReferences = entity.getReferences();
		final ReferenceContract[] filteredReferences = originalReferences
			.stream()
			.filter(referencePredicate)
			.sorted(Comparator.comparing(ReferenceContract::getReferenceName))
			.toArray(ReferenceContract[]::new);

		int index = -1;
		ReferenceSchemaContract referenceSchema = null;
		Function<Integer, SealedEntity> entityFetcher = null;
		Function<Integer, SealedEntity> entityGroupFetcher = null;
		ReferenceComparator fetchedReferenceComparator = null;

		final int entityPrimaryKey = Objects.requireNonNull(getPrimaryKey());
		final ReferenceDecorator[] fetchedAndFilteredReferences = new ReferenceDecorator[filteredReferences.length];
		for (int i = 0; i < fetchedAndFilteredReferences.length; i++) {
			final ReferenceContract referenceContract = filteredReferences[i];
			final String thisReferenceName = referenceContract.getReferenceName();
			if (referenceSchema == null) {
				index = i;
				referenceSchema = entitySchema.getReference(thisReferenceName)
					.orElseThrow(() -> new GenericEvitaInternalError("Sanity check!"));
				entityFetcher = referenceFetcher.getEntityFetcher(referenceSchema);
				entityGroupFetcher = referenceFetcher.getEntityGroupFetcher(referenceSchema);
				fetchedReferenceComparator = referenceFetcher.getEntityComparator(referenceSchema);
			} else if (!referenceSchema.getName().equals(thisReferenceName)) {
				sortAndFilterSubList(
					entityPrimaryKey,
					fetchedAndFilteredReferences, fetchedReferenceComparator,
					referenceFetcher.getEntityFilter(referenceSchema),
					index, i
				);
				index = i;
				referenceSchema = entitySchema.getReference(thisReferenceName)
					.orElseThrow(() -> new GenericEvitaInternalError("Sanity check!"));
				entityFetcher = referenceFetcher.getEntityFetcher(referenceSchema);
				entityGroupFetcher = referenceFetcher.getEntityGroupFetcher(referenceSchema);
				fetchedReferenceComparator = referenceFetcher.getEntityComparator(referenceSchema);
			}

			fetchedAndFilteredReferences[i] = ofNullable(
				fetchReference(
					referenceContract, referenceSchema, entityFetcher, entityGroupFetcher
				)
			).orElseGet(() -> new ReferenceDecorator(referenceContract, referencePredicate.getAttributePredicate(thisReferenceName)));
		}
		if (referenceSchema != null && fetchedReferenceComparator != null) {
			sortAndFilterSubList(
				entityPrimaryKey,
				fetchedAndFilteredReferences, fetchedReferenceComparator,
				referenceFetcher.getEntityFilter(referenceSchema),
				index, fetchedAndFilteredReferences.length
			);
		}

		this.filteredReferences = Arrays.stream(fetchedAndFilteredReferences)
			.filter(Objects::nonNull)
			.collect(
				Collectors.toMap(
					ReferenceContract::getReferenceKey,
					Function.identity(),
					(o, o2) -> {
						throw new EvitaInvalidUsageException("Sanity check: " + o + ", " + o2);
					},
					LinkedHashMap::new
				)
			);
	}

	/**
	 * Creates wrapper around {@link Entity} that filters existing data according passed predicates (which are constructed
	 * to match query that is used to retrieve the decorator).
	 *
	 * @param delegate     fully or partially loaded entity - it's usually wider than decorator (may be even complete),
	 *                     delegate might be obtained from shared global cache
	 * @param entitySchema schema of the delegate entity
	 * @param parent       body of the {@link Entity#getParent()} entity
	 * @param evitaRequest request that was used for retrieving the `delegate` entity
	 */
	public EntityDecorator(
		@Nonnull Entity delegate,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parent,
		@Nonnull EvitaRequest evitaRequest
	) {
		this.delegate = delegate;
		this.entitySchema = entitySchema;
		this.parentEntity = parent;
		this.localePredicate = new LocaleSerializablePredicate(evitaRequest);
		this.hierarchyPredicate = new HierarchySerializablePredicate(evitaRequest);
		this.attributePredicate = new AttributeValueSerializablePredicate(evitaRequest);
		this.associatedDataPredicate = new AssociatedDataValueSerializablePredicate(evitaRequest);
		this.referencePredicate = new ReferenceContractSerializablePredicate(evitaRequest);
		this.pricePredicate = new PriceContractSerializablePredicate(evitaRequest, (Boolean) null);
		this.alignedNow = evitaRequest.getAlignedNow();
	}

	/**
	 * Returns {@link LocaleSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public LocaleSerializablePredicate getLocalePredicate() {
		return ofNullable(localePredicate.getUnderlyingPredicate()).orElse(localePredicate);
	}

	/**
	 * Returns {@link HierarchySerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public HierarchySerializablePredicate getHierarchyPredicate() {
		return ofNullable(hierarchyPredicate.getUnderlyingPredicate()).orElse(hierarchyPredicate);
	}

	/**
	 * Returns {@link LocaleSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public LocaleSerializablePredicate createLocalePredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return localePredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link HierarchySerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public HierarchySerializablePredicate createHierarchyPredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return hierarchyPredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link AttributeValueSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public AttributeValueSerializablePredicate getAttributePredicate() {
		return ofNullable(attributePredicate.getUnderlyingPredicate()).orElse(attributePredicate);
	}

	/**
	 * Returns {@link AttributeValueSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public AttributeValueSerializablePredicate createAttributePredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return attributePredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link AssociatedDataValueSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public AssociatedDataValueSerializablePredicate getAssociatedDataPredicate() {
		return ofNullable(associatedDataPredicate.getUnderlyingPredicate()).orElse(associatedDataPredicate);
	}

	/**
	 * Returns {@link AssociatedDataValueSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public AssociatedDataValueSerializablePredicate createAssociatedDataPredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return associatedDataPredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link ReferenceContractSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public ReferenceContractSerializablePredicate getReferencePredicate() {
		return ofNullable(referencePredicate.getUnderlyingPredicate()).orElse(referencePredicate);
	}

	/**
	 * Returns {@link ReferenceContractSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public ReferenceContractSerializablePredicate createReferencePredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return referencePredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link PriceContractSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public PriceContractSerializablePredicate getPricePredicate() {
		return ofNullable(pricePredicate.getUnderlyingPredicate()).orElse(pricePredicate);
	}

	/**
	 * Returns {@link PriceContractSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public PriceContractSerializablePredicate createPricePredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return pricePredicate.createRicherCopyWith(evitaRequest);
	}

	@Nonnull
	@Override
	public String getType() {
		return delegate.getType();
	}

	@Nonnull
	@Override
	public EntitySchemaContract getSchema() {
		return entitySchema;
	}

	@Nullable
	@Override
	public Integer getPrimaryKey() {
		return delegate.getPrimaryKey();
	}

	@Override
	public boolean parentAvailable() {
		return delegate.parentAvailable() && hierarchyPredicate.wasFetched();
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		hierarchyPredicate.checkFetched();
		Assert.isTrue(
			getSchema().isWithHierarchy(),
			() -> new EntityIsNotHierarchicalException(getSchema().getName())
		);
		if (parentAvailable()) {
			return parentEntity == CONCEALED_ENTITY ? empty() :
				ofNullable(parentEntity).or(delegate::getParentEntity);
		} else {
			return empty();
		}
	}

	@Override
	public boolean referencesAvailable() {
		return referencePredicate.wasFetched();
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return referencePredicate.wasFetched(referenceName);
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		referencePredicate.checkFetched();
		return getFilteredReferences().values();
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		referencePredicate.checkFetched(referenceName);
		delegate.checkReferenceName(referenceName);
		final Collection<ReferenceContract> values = getFilteredReferences().values();
		final List<ReferenceContract> matchingReferences = new ArrayList<>(values.size());
		boolean found = false;
		for (ReferenceContract value : values) {
			if (Objects.equals(referenceName, value.getReferenceName())) {
				found = true;
				matchingReferences.add(value);
			} else if (found) {
				// we may prematurely finish here,
				// because the filtered references are primarily sorted by reference name
				break;
			}
		}
		return matchingReferences;
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		referencePredicate.checkFetched(referenceName);
		return ofNullable(getFilteredReferences().get(new ReferenceKey(referenceName, referencedEntityId)));
	}

	@Nonnull
	@Override
	public Set<Locale> getAllLocales() {
		return delegate.getAllLocales();
	}

	@Nonnull
	@Override
	public Set<Locale> getLocales() {
		return delegate.getLocales()
			.stream()
			.filter(localePredicate)
			.collect(Collectors.toSet());
	}

	/**
	 * Returns parent entity without checking the predicate.
	 * Part of the PRIVATE API.
	 */
	@Nonnull
	public Optional<EntityClassifierWithParent> getParentEntityWithoutCheckingPredicate() {
		return ofNullable(parentEntity);
	}

	@Nonnull
	public Optional<ReferenceContract> getReferenceWithoutCheckingPredicate(@Nonnull String referenceName, int referencedEntityId) {
		return ofNullable(getFilteredReferences().get(new ReferenceKey(referenceName, referencedEntityId)));
	}

	@Nullable
	public Locale getImplicitLocale() {
		return localePredicate.getImplicitLocale();
	}

	@Override
	public boolean dropped() {
		return delegate.dropped();
	}

	@Override
	public int version() {
		return delegate.version();
	}

	@Override
	public boolean attributesAvailable() {
		return attributePredicate.wasFetched();
	}

	@Override
	public boolean attributesAvailable(@Nonnull Locale locale) {
		return attributePredicate.wasFetched(locale);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName) {
		return attributePredicate.wasFetched(attributeName);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName, @Nonnull Locale locale) {
		return attributePredicate.wasFetched(attributeName, locale);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		//noinspection unchecked
		return getAttributeValue(attributeName)
			.map(it -> (T) it.value())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return getAttributeValue(attributeName)
			.map(it -> (T[]) it.value())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		final AttributeKey attributeKey;
		if (attributePredicate.isLocaleSet()) {
			final Locale locale = attributePredicate.getLocale();
			attributeKey = locale == null ?
				new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
		} else {
			attributeKey = new AttributeKey(attributeName);
		}
		attributePredicate.checkFetched(attributeKey);
		return delegate.getAttributeValue(attributeKey).filter(attributePredicate);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return getAttributeValue(attributeName, locale)
			.filter(attributePredicate)
			.map(it -> (T) it.value())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return getAttributeValue(attributeName, locale)
			.filter(attributePredicate)
			.map(it -> (T[]) it.value())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
		attributePredicate.checkFetched(attributeKey);
		return delegate.getAttributeValue(attributeKey)
			.filter(attributePredicate);
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return delegate.getAttributeSchema(attributeName);
	}

	@Nonnull
	@Override
	public Set<String> getAttributeNames() {
		return getAttributeValues()
			.stream()
			.map(it -> it.key().attributeName())
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return getAttributeValues()
			.stream()
			.map(AttributeValue::key)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		attributePredicate.checkFetched(attributeKey);
		return delegate.getAttributeValue(attributeKey)
			.filter(attributePredicate);
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues() {
		attributePredicate.checkFetched();
		if (filteredAttributes == null) {
			filteredAttributes = delegate.getAttributeValues()
				.stream()
				.filter(attributePredicate)
				.collect(Collectors.toList());
		}
		return filteredAttributes;
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		attributePredicate.checkFetched(new AttributeKey(attributeName));
		return delegate.getAttributeValues(attributeName)
			.stream()
			.filter(attributePredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Set<Locale> getAttributeLocales() {
		return this.delegate.getAttributeLocales();
	}

	@Override
	public boolean associatedDataAvailable() {
		return associatedDataPredicate.wasFetched();
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull Locale locale) {
		return associatedDataPredicate.wasFetched(locale);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName) {
		return associatedDataPredicate.wasFetched(associatedDataName);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return associatedDataPredicate.wasFetched(associatedDataName, locale);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return getAssociatedDataValue(associatedDataName)
			.map(it -> (T) it.value())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return getAssociatedDataValue(associatedDataName)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it.value(), dtoType, reflectionLookup))
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return getAssociatedDataValue(associatedDataName)
			.map(it -> (T[]) it.value())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName) {
		final AssociatedDataKey associatedDataKey;
		if (associatedDataPredicate.isLocaleSet()) {
			final Locale locale = associatedDataPredicate.getLocale();
			associatedDataKey = locale == null ?
				new AssociatedDataKey(associatedDataName) : new AssociatedDataKey(associatedDataName, locale);
		} else {
			associatedDataKey = new AssociatedDataKey(associatedDataName);
		}
		associatedDataPredicate.checkFetched(associatedDataKey);
		return delegate.getAssociatedDataValue(associatedDataKey).filter(associatedDataPredicate);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return delegate.getAssociatedDataValue(associatedDataName, locale)
			.filter(associatedDataPredicate)
			.map(it -> (T) it.value())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return delegate.getAssociatedDataValue(associatedDataName, locale)
			.filter(associatedDataPredicate)
			.map(AssociatedDataValue::value)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return delegate.getAssociatedDataValue(associatedDataName, locale)
			.filter(associatedDataPredicate)
			.map(it -> (T[]) it.value())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
		associatedDataPredicate.checkFetched(associatedDataKey);
		return delegate.getAssociatedDataValue(associatedDataKey)
			.filter(associatedDataPredicate);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return delegate.getAssociatedDataSchema(associatedDataName);
	}

	@Nonnull
	@Override
	public Set<String> getAssociatedDataNames() {
		return getAssociatedDataValues()
			.stream()
			.map(it -> it.key().associatedDataName())
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AssociatedDataKey> getAssociatedDataKeys() {
		return getAssociatedDataValues()
			.stream()
			.map(AssociatedDataValue::key)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		associatedDataPredicate.checkFetched(associatedDataKey);
		return delegate.getAssociatedDataValue(associatedDataKey)
			.filter(associatedDataPredicate);
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		if (filteredAssociatedData == null) {
			associatedDataPredicate.checkFetched();
			filteredAssociatedData = delegate.getAssociatedDataValues()
				.stream()
				.filter(associatedDataPredicate)
				.collect(Collectors.toList());
		}
		return filteredAssociatedData;
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		return delegate.getAssociatedDataValues(associatedDataName)
			.stream()
			.filter(associatedDataPredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Set<Locale> getAssociatedDataLocales() {
		return this.delegate.getAssociatedDataLocales();
	}

	@Override
	public boolean pricesAvailable() {
		return pricePredicate.isFetched() && delegate.pricesAvailable();
	}

	@Nullable
	@Override
	public Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey) throws ContextMissingException {
		pricePredicate.checkFetched(priceKey.currency(), priceKey.priceList());
		return delegate.getPrice(priceKey)
			.filter(pricePredicate);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		pricePredicate.checkFetched(currency, priceList);
		return delegate.getPrice(priceId, priceList, currency)
			.filter(pricePredicate);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(@Nonnull String priceList, @Nonnull Currency currency) throws UnexpectedResultCountException, ContextMissingException {
		pricePredicate.checkFetched(currency, priceList);
		return delegate.getPrice(priceList, currency)
			.filter(pricePredicate);
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices(@Nonnull String priceList) throws ContextMissingException {
		pricePredicate.checkFetched(null, priceList);
		return SealedEntity.super.getPrices(priceList);
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices(@Nonnull Currency currency) throws ContextMissingException {
		pricePredicate.checkFetched(currency);
		return SealedEntity.super.getPrices(currency);
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices(@Nonnull Currency currency, @Nonnull String priceList) throws ContextMissingException {
		pricePredicate.checkFetched(currency, priceList);
		return SealedEntity.super.getPrices(currency, priceList);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale(@Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String... priceListPriority) {
		pricePredicate.checkFetched(currency, priceListPriority);
		if (pricePredicate.isContextAvailable()) {
			// verify the mandated context
			Assert.isTrue(
				Objects.equals(currency, pricePredicate.getCurrency()) &&
					Objects.equals(atTheMoment, pricePredicate.getValidIn()) &&
					Arrays.equals(priceListPriority, pricePredicate.getPriceLists()),
				() -> "Entity cannot provide price for sale different from the context it was loaded in! " +
					"The entity `" + getPrimaryKey() + "` was fetched using `" + pricePredicate.getCurrency() + "` currency, " +
					ofNullable(pricePredicate.getValidIn()).map(it -> "valid at `" + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it) + "`").orElse("") +
					" and price list in this priority: " + Arrays.stream(pricePredicate.getPriceLists()).map(it -> "`" + it + "`").collect(Collectors.joining(", ")) + "."
			);
		}
		return SealedEntity.super.getPriceForSale(currency, atTheMoment, priceListPriority);
	}

	@Override
	public boolean isPriceForSaleContextAvailable() {
		return pricePredicate.isContextAvailable();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		if (pricePredicate.isContextAvailable()) {
			pricePredicate.checkPricesFetched();
			return SealedEntity.super.getPriceForSale(
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		if (pricePredicate.isContextAvailable()) {
			pricePredicate.checkPricesFetched();
			return getPriceForSale(
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists()
			);
		} else {
			return empty();
		}
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale(@Nullable Currency currency, @Nullable OffsetDateTime atTheMoment, @Nullable String... priceListPriority) throws ContextMissingException {
		pricePredicate.checkFetched(currency, priceListPriority);
		final List<PriceContract> allPricesForSale = SealedEntity.super.getAllPricesForSale(currency, atTheMoment, priceListPriority);
		if (allPricesForSale.size() > 1) {
			allPricesForSale.sort(
				Comparator.comparing(
					pricePredicate.getQueryPriceMode() == QueryPriceMode.WITH_TAX ?
						PriceContract::priceWithTax : PriceContract::priceWithoutTax
				)
			);
		}
		return allPricesForSale;
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		pricePredicate.checkPricesFetched();
		return getAllPricesForSale(
			pricePredicate.getCurrency(),
			pricePredicate.getValidIn(),
			pricePredicate.getPriceLists()
		);
	}

	@Override
	public boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode) throws ContextMissingException {
		if (pricePredicate.isContextAvailable()) {
			pricePredicate.checkPricesFetched();
			return hasPriceInInterval(
				from, to, queryPriceMode,
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		if (filteredPrices == null) {
			pricePredicate.checkPricesFetched();
			filteredPrices = delegate.getPrices()
				.stream()
				.filter(pricePredicate)
				.collect(Collectors.toList());
		}
		return filteredPrices;
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		if (pricePredicate.getPriceContentMode() == PriceContentMode.NONE) {
			return PriceInnerRecordHandling.UNKNOWN;
		}
		return delegate.getPriceInnerRecordHandling();
	}

	@Nonnull
	public Optional<PriceContract> getPriceForSale(@Nonnull Predicate<PriceContract> predicate) throws ContextMissingException {
		if (pricePredicate.isContextAvailable()) {
			pricePredicate.checkPricesFetched();
			return PricesContract.computePriceForSale(
				getPrices(),
				getPriceInnerRecordHandling(),
				pricePredicate.getCurrency(),
				pricePredicate.getValidIn(),
				pricePredicate.getPriceLists(),
				predicate
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public EntityBuilder openForWrite() {
		return new ExistingEntityBuilder(this);
	}

	@Nonnull
	@Override
	public EntityBuilder withMutations(@Nonnull LocalMutation<?, ?>... localMutations) {
		return new ExistingEntityBuilder(this, Arrays.asList(localMutations));
	}

	@Nonnull
	@Override
	public EntityBuilder withMutations(@Nonnull Collection<LocalMutation<?, ?>> localMutations) {
		return new ExistingEntityBuilder(this, localMutations);
	}

	@Override
	public int hashCode() {
		return delegate.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return delegate.equals(obj instanceof EntityDecorator ? ((EntityDecorator) obj).delegate : obj);
	}

	@Override
	public String toString() {
		return describe();
	}

	/**
	 * Returns locale that was used for fetching the entity - either the {@link #getImplicitLocale()} or the locale
	 * from {@link #getLocalePredicate()} if there was exactly single locale used for fetching.
	 *
	 * @return locale that was used for fetching the entity
	 */
	@Nullable
	public Locale getRequestedLocale() {
		final Set<Locale> locales = localePredicate.getLocales();
		return locales != null && locales.size() == 1 ?
			locales.iterator().next() : localePredicate.getImplicitLocale();
	}

	/**
	 * Returns true if there was more than one locale used for fetching the entity.
	 *
	 * @return true if there was more than one locale used for fetching the entity
	 */
	public boolean isMultipleLocalesRequested() {
		final Set<Locale> locales = localePredicate.getLocales();
		return locales != null && locales.size() != 1;
	}

	/**
	 * Returns true if passed locale was requested when the entity was fetched.
	 *
	 * @param locale locale to check
	 * @return true if passed locale was requested when the entity was fetched
	 */
	public boolean isLocaleRequested(@Nonnull Locale locale) {
		return localePredicate.test(locale);
	}

	/**
	 * Method collects all references in delegate and filters them by using {@link #referencePredicate}. The result
	 * of this operation is memoized so all subsequent calls to this method are pretty cheap.
	 */
	@Nonnull
	private Map<ReferenceKey, ReferenceContract> getFilteredReferences() {
		if (filteredReferences == null) {
			filteredReferences = delegate.getReferences()
				.stream()
				.filter(referencePredicate)
				.map(
					it -> new ReferenceDecorator(
						it, referencePredicate.getAttributePredicate(it.getReferenceKey().referenceName())
					)
				)
				.collect(
					Collectors.toMap(
						ReferenceContract::getReferenceKey,
						Function.identity(),
						(o, o2) -> {
							throw new EvitaInvalidUsageException("Sanity check: " + o + ", " + o2);
						},
						LinkedHashMap::new
					)
				);
		}
		return filteredReferences;
	}

	/**
	 * Method fetches the referenced entity and group entity if those entities are managed by the evitaDB and there
	 * is a request for fetching their bodies in input request.
	 */
	@Nullable
	private ReferenceDecorator fetchReference(
		@Nonnull ReferenceContract reference,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Function<Integer, SealedEntity> referenceEntityFetcher,
		@Nullable Function<Integer, SealedEntity> referenceGroupEntityFetcher
	) {
		final SealedEntity referencedEntity;
		if (referenceSchema.isReferencedEntityTypeManaged() && referenceEntityFetcher != null) {
			referencedEntity = referenceEntityFetcher.apply(reference.getReferenceKey().primaryKey());
		} else {
			referencedEntity = null;
		}

		final SealedEntity referencedGroupEntity = referenceSchema.isReferencedGroupTypeManaged() && referenceGroupEntityFetcher != null && referencedEntity != null ?
			reference.getGroup().map(group -> referenceGroupEntityFetcher.apply(group.primaryKey())).orElse(null) : null;
		return new ReferenceDecorator(
			reference,
			referencedEntity,
			referencedGroupEntity,
			referencePredicate.getAttributePredicate(
				referenceSchema.getName()
			)
		);
	}

}
