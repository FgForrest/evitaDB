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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityIsNotHierarchicalException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException.Operation;
import io.evitadb.api.exception.ReferenceNotFoundException;
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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
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
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.data.structure.References.DUPLICATE_REFERENCE;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
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
	 * Optimization that ensures that expensive reference filtering using predicates happens only once.
	 */
	private Map<ReferenceKey, ReferenceContract> filteredReferences;
	/**
	 * Optimization that ensures that expensive reference filtering using predicates happens only once.
	 */
	private Map<ReferenceKey, List<ReferenceContract>> filteredDuplicateReferences;
	/**
	 * Contains map of all references by their name. This map is used for fast lookup of the references by their name
	 * and is initialized lazily on first request.
	 */
	private Map<String, DataChunk<ReferenceContract>> filteredReferencesByName;
	/**
	 * Optimization that ensures that expensive attributes filtering using predicates happens only once.
	 */
	private List<AttributeValue> filteredAttributes;
	/**
	 * Optimization that ensures that expensive associated data filtering using predicates happens only once.
	 */
	private List<AssociatedDataValue> filteredAssociatedData;
	/**
	 * Optimization that ensures that expensive prices filtering using predicates happens only once.
	 */
	private List<PriceContract> filteredPrices;

	/**
	 * Method sorts and filters the data in `references` using `referenceFilter` and `referenceComparator` but only
	 * within bounds specified by `start` (inclusive) and `end` (exclusive).
	 *
	 * @return count of filtered out references
	 */
	private static int sortAndFilterSubList(
		int entityPrimaryKey,
		@Nonnull ReferenceDecorator[] references,
		@Nullable ReferenceComparator referenceComparator,
		@Nullable BiPredicate<Integer, ReferenceDecorator> referenceFilter,
		int start,
		int end
	) {
		int filteredOutReferences = 0;
		if (referenceComparator != null) {
			if (referenceComparator instanceof ReferenceComparator.EntityPrimaryKeyAwareComparator epkAware) {
				epkAware.setEntityPrimaryKey(entityPrimaryKey);
			}
			int nonSortedReferenceCount;
			do {
				if (referenceFilter == null) {
					Arrays.sort(references, start, end, referenceComparator);
				} else {
					final ReferenceDecorator[] filteredReferences = Arrays
						.stream(references, start, end)
						.filter(it -> referenceFilter.test(entityPrimaryKey, it))
						.toArray(ReferenceDecorator[]::new);
					filteredOutReferences += (end - start) - filteredReferences.length;

					Arrays.sort(filteredReferences, referenceComparator);

					for (int i = start; i < end; i++) {
						final int filteredIndex = i - start;
						references[i] = filteredReferences.length > filteredIndex ?
							filteredReferences[filteredIndex] :
							null;
					}
				}
				nonSortedReferenceCount = referenceComparator.getNonSortedReferenceCount();
				start = start + (end - nonSortedReferenceCount);
				referenceComparator = referenceComparator.getNextComparator();
			} while (referenceComparator != null && nonSortedReferenceCount > 0);
		}
		return filteredOutReferences;
	}

	/**
	 * Removes references from {@code filteredReferences} that are not present in the provided data chunk. Most of the
	 * time all the references will be present in the chunk, only when pagination is used and there is a lot of references
	 * some of them will be missing. In such case, we need to remove them from the filtered references.
	 *
	 * @param chunk                The chunk containing a subset of reference contracts. Must not be null.
	 * @param references           The list of references to filter. Must not be null.
	 *                             References within the list might contain null elements.
	 * @param filteredReferences   Map of filtered references to be updated. Must not be null.
	 * @param duplicatedReferences Map of duplicated references to be updated. Must not be null.
	 */
	private static void removeReferencesNotPresentInChunk(
		@Nonnull DataChunk<ReferenceContract> chunk,
		@Nonnull List<ReferenceContract> references,
		@Nonnull Map<ReferenceKey, ReferenceContract> filteredReferences,
		@Nonnull Map<ReferenceKey, List<ReferenceContract>> duplicatedReferences
	) {
		// this will be true only if there is pagination defined (small number of cases)
		final int chunkSize = chunk.getData().size();
		if (chunkSize < references.size()) {
			final Set<ReferenceKey> returnedKeys = createHashSet(chunkSize);
			for (ReferenceContract referenceContract : chunk) {
				returnedKeys.add(referenceContract.getReferenceKey());
			}
			for (ReferenceContract reference : references) {
				if (reference != null && !returnedKeys.contains(reference.getReferenceKey())) {
					final ReferenceContract removedReference = filteredReferences.remove(reference.getReferenceKey());
					if (removedReference == DUPLICATE_REFERENCE) {
						duplicatedReferences.remove(removedReference.getReferenceKey());
					}
				}
			}
		}
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
		this(
			delegate,
			entitySchema,
			parent,
			new LocaleSerializablePredicate(evitaRequest),
			new HierarchySerializablePredicate(evitaRequest),
			new AttributeValueSerializablePredicate(evitaRequest),
			new AssociatedDataValueSerializablePredicate(evitaRequest),
			new ReferenceContractSerializablePredicate(evitaRequest),
			new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
			evitaRequest.getAlignedNow()
		);
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
			.or(() -> of(this.delegate).filter(Entity::parentAvailable).flatMap(Entity::getParentEntity))
			.orElse(null);
		this.entitySchema = decorator.getSchema();
		this.localePredicate = localePredicate;
		this.hierarchyPredicate = hierarchyPredicate;
		this.attributePredicate = attributePredicate;
		this.associatedDataPredicate = associatedDataPredicate;
		this.referencePredicate = referencePredicate;
		this.pricePredicate = pricePredicate;
		this.alignedNow = alignedNow;
		this.filteredReferences = decorator.filteredReferences;
		this.filteredDuplicateReferences = decorator.filteredDuplicateReferences;
		this.filteredReferencesByName = decorator.filteredReferencesByName;
	}

	/**
	 * Creates wrapper around {@link Entity} that filters existing data according passed predicates (which are constructed
	 * to match query that is used to retrieve the decorator).
	 *
	 * @param entity           fully or partially loaded entity - it's usually wider than decorator (may be even complete), decorator
	 *                         might be obtained from shared global cache
	 * @param parentEntity     object of the parentEntity
	 * @param referenceFetcher fetcher that can be used for fetching, filtering and ordering referenced
	 *                         entities / groups
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
			referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION ?
				entity.referencePredicate :
				entity.referencePredicate.createRicherCopyWith(referenceFetcher.getEnvelopingEntityRequest()),
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
		int filteredOutReferences = 0;
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
				filteredOutReferences += sortAndFilterSubList(
					entityPrimaryKey,
					fetchedAndFilteredReferences, fetchedReferenceComparator,
					referenceFetcher.getEntityFilter(referenceSchema),
					index, i - filteredOutReferences
				);
				index = i - filteredOutReferences;
				referenceSchema = entitySchema.getReference(thisReferenceName)
				                              .orElseThrow(() -> new GenericEvitaInternalError("Sanity check!"));
				entityFetcher = referenceFetcher.getEntityFetcher(referenceSchema);
				entityGroupFetcher = referenceFetcher.getEntityGroupFetcher(referenceSchema);
				fetchedReferenceComparator = referenceFetcher.getEntityComparator(referenceSchema);
			}

			fetchedAndFilteredReferences[i - filteredOutReferences] = ofNullable(
				fetchReference(
					referenceContract, referenceSchema, entityFetcher, entityGroupFetcher
				)
			).orElseGet(() -> new ReferenceDecorator(
				referenceContract,
				referencePredicate.getAttributePredicate(thisReferenceName)
			));
		}
		if (referenceSchema != null && fetchedReferenceComparator != null) {
			filteredOutReferences += sortAndFilterSubList(
				entityPrimaryKey,
				fetchedAndFilteredReferences, fetchedReferenceComparator,
				referenceFetcher.getEntityFilter(referenceSchema),
				index, fetchedAndFilteredReferences.length - filteredOutReferences
			);
		}

		final Map<String, ReferenceSchemaContract> referencesAccordingToSchema = entitySchema.getReferences();
		this.filteredReferencesByName = createLinkedHashMap(referencesAccordingToSchema.size());

		final int length = fetchedAndFilteredReferences.length - filteredOutReferences;
		final Map<String, List<ReferenceContract>> indexByName = createLinkedHashMap(length);
		this.filteredReferences = createLinkedHashMap(length);
		final int averageExpectedCount = referencesAccordingToSchema.isEmpty() ?
			16 : Math.min(16, length / referencesAccordingToSchema.size() + 1);

		// cache last resolved schema to avoid Optional/map overhead on each iteration
		final EntitySchemaContract schema = this.delegate.getSchema();
		ReferenceSchemaContract lastResolvedSchema = null;
		String lastResolvedSchemaName = null;
		for (int i = 0; i < length; i++) {
			final ReferenceDecorator reference = fetchedAndFilteredReferences[i];
			final String referenceName = reference.getReferenceName();
			indexByName
				.computeIfAbsent(
					referenceName,
					s -> new ArrayList<>(averageExpectedCount)
				)
				.add(reference);

			// resolve schema only when reference name changes
			if (lastResolvedSchema == null || !referenceName.equals(lastResolvedSchemaName)) {
				lastResolvedSchema = schema.getReference(referenceName).orElse(null);
				lastResolvedSchemaName = referenceName;
			}
			final boolean duplicatesAllowed = lastResolvedSchema == null
				? Cardinality.ZERO_OR_MORE.allowsDuplicates()
				: lastResolvedSchema.getCardinality().allowsDuplicates();

			final ReferenceKey referenceKey = reference.getReferenceKey();
			if (duplicatesAllowed) {
				// mark reference key as duplicate bearer
				this.filteredReferences.put(referenceKey, DUPLICATE_REFERENCE);
				if (this.filteredDuplicateReferences == null) {
					this.filteredDuplicateReferences = createHashMap(schema.getReferences().size());
				}
				this.filteredDuplicateReferences
					.computeIfAbsent(
						new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey()),
						k -> new ArrayList<>(2)
					)
					.add(reference);
			} else {
				// entity decorator wraps entity with up-to-date schema, so having duplicate reference which is not
				// allowed by the schema is a sign of an invalid state
				final ReferenceContract previous = this.filteredReferences.putIfAbsent(referenceKey, reference);
				Assert.isPremiseValid(
					previous == null,
					"Unexpected duplicate reference " + referenceKey + " in entity " + entityPrimaryKey + "!"
				);
			}
		}
		final Set<String> referenceNames = referencePredicate.getReferenceSet().isEmpty() ?
			referencesAccordingToSchema.keySet() : referencePredicate.getReferenceSet().keySet();
		for (String referenceName : referenceNames) {
			if (referencePredicate.isReferenceRequested(referenceName)) {
				final List<ReferenceContract> references = ofNullable(indexByName.get(referenceName))
					.orElse(Collections.emptyList());
				final DataChunk<ReferenceContract> chunk = referenceFetcher.createChunk(
					entity, referenceName, references
				);
				removeReferencesNotPresentInChunk(
					chunk, references,
					this.filteredReferences,
					this.filteredDuplicateReferences
				);
				this.filteredReferencesByName.put(referenceName, chunk);
			}
		}
	}

	/**
	 * Returns {@link LocaleSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public LocaleSerializablePredicate getLocalePredicate() {
		return ofNullable(this.localePredicate.getUnderlyingPredicate()).orElse(this.localePredicate);
	}

	/**
	 * Returns {@link HierarchySerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public HierarchySerializablePredicate getHierarchyPredicate() {
		return ofNullable(this.hierarchyPredicate.getUnderlyingPredicate()).orElse(this.hierarchyPredicate);
	}

	/**
	 * Returns {@link LocaleSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public LocaleSerializablePredicate createLocalePredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return this.localePredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link HierarchySerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public HierarchySerializablePredicate createHierarchyPredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return this.hierarchyPredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link AttributeValueSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public AttributeValueSerializablePredicate getAttributePredicate() {
		return ofNullable(this.attributePredicate.getUnderlyingPredicate()).orElse(this.attributePredicate);
	}

	/**
	 * Returns {@link AttributeValueSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public AttributeValueSerializablePredicate createAttributePredicateRicherCopyWith(
		@Nonnull EvitaRequest evitaRequest
	) {
		return this.attributePredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link AssociatedDataValueSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public AssociatedDataValueSerializablePredicate getAssociatedDataPredicate() {
		return ofNullable(this.associatedDataPredicate.getUnderlyingPredicate()).orElse(this.associatedDataPredicate);
	}

	/**
	 * Returns {@link AssociatedDataValueSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public AssociatedDataValueSerializablePredicate createAssociatedDataPredicateRicherCopyWith(
		@Nonnull EvitaRequest evitaRequest
	) {
		return this.associatedDataPredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link ReferenceContractSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public ReferenceContractSerializablePredicate getReferencePredicate() {
		return ofNullable(this.referencePredicate.getUnderlyingPredicate()).orElse(this.referencePredicate);
	}

	/**
	 * Returns {@link ReferenceContractSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public ReferenceContractSerializablePredicate createReferencePredicateRicherCopyWith(
		@Nonnull EvitaRequest evitaRequest
	) {
		return this.referencePredicate.createRicherCopyWith(evitaRequest);
	}

	/**
	 * Returns {@link PriceContractSerializablePredicate} that represents the scope of the fetched data of the underlying entity.
	 */
	@Nonnull
	public PriceContractSerializablePredicate getPricePredicate() {
		return ofNullable(this.pricePredicate.getUnderlyingPredicate()).orElse(this.pricePredicate);
	}

	/**
	 * Returns {@link PriceContractSerializablePredicate} that is enriched enough to satisfy passed `evitaRequest`.
	 */
	@Nonnull
	public PriceContractSerializablePredicate createPricePredicateRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		return this.pricePredicate.createRicherCopyWith(evitaRequest);
	}

	@Nonnull
	@Override
	public String getType() {
		return this.delegate.getType();
	}

	@Nonnull
	@Override
	public EntitySchemaContract getSchema() {
		return this.entitySchema;
	}

	@Nullable
	@Override
	public Integer getPrimaryKey() {
		return this.delegate.getPrimaryKey();
	}

	@Override
	public boolean parentAvailable() {
		return this.delegate.parentAvailable() && this.hierarchyPredicate.wasFetched();
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		this.hierarchyPredicate.checkFetched();
		Assert.isTrue(
			getSchema().isWithHierarchy(),
			() -> new EntityIsNotHierarchicalException(getSchema().getName())
		);
		if (parentAvailable()) {
			return this.parentEntity == CONCEALED_ENTITY ? empty() :
				ofNullable(this.parentEntity).or(this.delegate::getParentEntity);
		} else {
			return empty();
		}
	}

	@Nonnull
	@Override
	public Set<Locale> getAllLocales() {
		return this.delegate.getAllLocales();
	}

	@Nonnull
	@Override
	public Set<Locale> getLocales() {
		return this.delegate
			.getLocales()
			.stream()
			.filter(this.localePredicate)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Scope getScope() {
		return this.delegate.getScope();
	}

	@Override
	public boolean referencesAvailable() {
		return this.referencePredicate.wasFetched();
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return this.referencePredicate.wasFetched(referenceName);
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		this.referencePredicate.checkFetched();
		final Map<ReferenceKey, ReferenceContract> theFilteredReferences = getFilteredReferences();
		if (this.filteredDuplicateReferences == null || this.filteredDuplicateReferences.isEmpty()) {
			return theFilteredReferences.values();
		} else {
			// need to expand duplicates
			return theFilteredReferences
				.entrySet()
				.stream()
				.flatMap(
					entry -> entry.getValue() == DUPLICATE_REFERENCE ?
						this.filteredDuplicateReferences.get(entry.getKey()).stream() :
						Stream.of(entry.getValue())
				)
				.collect(Collectors.toList());
		}
	}

	@Nonnull
	@Override
	public Set<String> getReferenceNames() {
		this.referencePredicate.checkFetched();
		return getFilteredReferencesByName().keySet();
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		this.referencePredicate.checkFetched(referenceName);
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedEntityId);
		return ofNullable(getFilteredReferences().get(referenceKey))
			.map(it -> avoidDuplicates(referenceKey, it));
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		this.referencePredicate.checkFetched(referenceKey.referenceName());
		return ofNullable(getFilteredReferences().get(referenceKey))
			.map(it -> avoidDuplicates(referenceKey, it));
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return getReferenceChunk(referenceName).getData();
	}

	@Nonnull
	@Override
	public List<ReferenceContract> getReferences(
		@Nonnull String referenceName,
		int referencedEntityId
	) throws ContextMissingException, ReferenceNotFoundException {
		return getReferences(new ReferenceKey(referenceName, referencedEntityId));
	}

	@Nonnull
	@Override
	public List<ReferenceContract> getReferences(
		@Nonnull ReferenceKey referenceKey
	) throws ContextMissingException, ReferenceNotFoundException {
		this.referencePredicate.checkFetched(referenceKey.referenceName());
		final ReferenceContract reference = getFilteredReferences().get(referenceKey);
		if (reference == null) {
			return Collections.emptyList();
		} else if (reference == DUPLICATE_REFERENCE) {
			return this.filteredDuplicateReferences.get(referenceKey);
		} else {
			return Collections.singletonList(reference);
		}
	}

	@Nonnull
	@Override
	public DataChunk<ReferenceContract> getReferenceChunk(
		@Nonnull String referenceName
	) throws ContextMissingException {
		this.referencePredicate.checkFetched(referenceName);
		this.delegate.references.checkReferenceNameAndCardinality(referenceName, true, true);
		final DataChunk<ReferenceContract> chunk = getFilteredReferencesByName().get(referenceName);
		return chunk == null ?
			this.delegate.references
				.getReferenceChunkTransformer()
				.apply(referenceName)
				.createChunk(Collections.emptyList()) :
			chunk;
	}

	/**
	 * Returns parent entity without checking the predicate.
	 * Part of the PRIVATE API.
	 */
	@Nonnull
	public Optional<EntityClassifierWithParent> getParentEntityWithoutCheckingPredicate() {
		return ofNullable(this.parentEntity);
	}

	@Nonnull
	public Optional<ReferenceContract> getReferenceWithoutCheckingPredicate(
		@Nonnull String referenceName, int referencedEntityId
	) {
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, referencedEntityId);
		return ofNullable(getFilteredReferences().get(referenceKey))
			.map(it -> avoidDuplicates(it.getReferenceKey(), it));
	}

	@Nonnull
	public Collection<ReferenceContract> getReferencesWithoutCheckingPredicate() {
		return getFilteredReferences().values();
	}

	@Nullable
	public Locale getImplicitLocale() {
		return this.localePredicate.getImplicitLocale();
	}

	@Override
	public boolean dropped() {
		return this.delegate.dropped();
	}

	@Override
	public int version() {
		return this.delegate.version();
	}

	@Override
	public boolean attributesAvailable() {
		return this.attributePredicate.wasFetched();
	}

	@Override
	public boolean attributesAvailable(@Nonnull Locale locale) {
		return this.attributePredicate.wasFetched(locale);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName) {
		return this.attributePredicate.wasFetched(attributeName);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName, @Nonnull Locale locale) {
		return this.attributePredicate.wasFetched(attributeName, locale);
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
		if (this.attributePredicate.isLocaleSet()) {
			final Locale locale = this.attributePredicate.getLocale();
			attributeKey = locale == null ?
				new AttributeKey(attributeName) : new AttributeKey(attributeName, locale);
		} else {
			attributeKey = new AttributeKey(attributeName);
		}
		this.attributePredicate.checkFetched(attributeKey);
		return this.delegate.getAttributeValue(attributeKey).filter(this.attributePredicate);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return getAttributeValue(attributeName, locale)
			.filter(this.attributePredicate)
			.map(it -> (T) it.value())
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return getAttributeValue(attributeName, locale)
			.filter(this.attributePredicate)
			.map(it -> (T[]) it.value())
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
		this.attributePredicate.checkFetched(attributeKey);
		return this.delegate.getAttributeValue(attributeKey)
		                    .filter(this.attributePredicate);
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.delegate.getAttributeSchema(attributeName);
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
		this.attributePredicate.checkFetched(attributeKey);
		return this.delegate.getAttributeValue(attributeKey)
		                    .filter(this.attributePredicate);
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues() {
		this.attributePredicate.checkFetched();
		if (this.filteredAttributes == null) {
			this.filteredAttributes = this.delegate.getAttributeValues()
			                                       .stream()
			                                       .filter(this.attributePredicate)
			                                       .collect(Collectors.toList());
		}
		return this.filteredAttributes;
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		this.attributePredicate.checkFetched(new AttributeKey(attributeName));
		return this.delegate.getAttributeValues(attributeName)
		                    .stream()
		                    .filter(this.attributePredicate)
		                    .collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Set<Locale> getAttributeLocales() {
		return this.delegate.getAttributeLocales();
	}

	@Override
	public boolean associatedDataAvailable() {
		return this.associatedDataPredicate.wasFetched();
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull Locale locale) {
		return this.associatedDataPredicate.wasFetched(locale);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName) {
		return this.associatedDataPredicate.wasFetched(associatedDataName);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return this.associatedDataPredicate.wasFetched(associatedDataName, locale);
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
	public <T extends Serializable> T getAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return getAssociatedDataValue(associatedDataName)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(
				Objects.requireNonNull(it.value()), dtoType,
				reflectionLookup
			))
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
		if (this.associatedDataPredicate.isLocaleSet()) {
			final Locale locale = this.associatedDataPredicate.getLocale();
			associatedDataKey = locale == null ?
				new AssociatedDataKey(associatedDataName) : new AssociatedDataKey(associatedDataName, locale);
		} else {
			associatedDataKey = new AssociatedDataKey(associatedDataName);
		}
		this.associatedDataPredicate.checkFetched(associatedDataKey);
		return this.delegate.getAssociatedDataValue(associatedDataKey).filter(this.associatedDataPredicate);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return this.delegate.getAssociatedDataValue(associatedDataName, locale)
		                    .filter(this.associatedDataPredicate)
		                    .map(it -> (T) it.value())
		                    .orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		return this.delegate.getAssociatedDataValue(associatedDataName, locale)
		                    .filter(this.associatedDataPredicate)
		                    .map(AssociatedDataValue::value)
		                    .map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
		                    .orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAssociatedDataArray(
		@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return this.delegate.getAssociatedDataValue(associatedDataName, locale)
		                    .filter(this.associatedDataPredicate)
		                    .map(it -> (T[]) it.value())
		                    .orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(
		@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
		this.associatedDataPredicate.checkFetched(associatedDataKey);
		return this.delegate.getAssociatedDataValue(associatedDataKey)
		                    .filter(this.associatedDataPredicate);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return this.delegate.getAssociatedDataSchema(associatedDataName);
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
		this.associatedDataPredicate.checkFetched(associatedDataKey);
		return this.delegate.getAssociatedDataValue(associatedDataKey)
		                    .filter(this.associatedDataPredicate);
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		if (this.filteredAssociatedData == null) {
			this.associatedDataPredicate.checkFetched();
			this.filteredAssociatedData = this.delegate.getAssociatedDataValues()
			                                           .stream()
			                                           .filter(this.associatedDataPredicate)
			                                           .collect(Collectors.toList());
		}
		return this.filteredAssociatedData;
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		return this.delegate.getAssociatedDataValues(associatedDataName)
		                    .stream()
		                    .filter(this.associatedDataPredicate)
		                    .collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Set<Locale> getAssociatedDataLocales() {
		return this.delegate.getAssociatedDataLocales();
	}

	@Override
	public boolean pricesAvailable() {
		return this.pricePredicate.isFetched() && this.delegate.pricesAvailable();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(@Nonnull PriceKey priceKey) throws ContextMissingException {
		this.pricePredicate.checkFetched(priceKey.currency(), priceKey.priceList());
		return this.delegate.getPrice(priceKey)
		                    .filter(this.pricePredicate);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		this.pricePredicate.checkFetched(currency, priceList);
		return this.delegate.getPrice(priceId, priceList, currency)
		                    .filter(this.pricePredicate);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(
		@Nonnull String priceList,
		@Nonnull Currency currency
	) throws UnexpectedResultCountException, ContextMissingException {
		this.pricePredicate.checkFetched(currency, priceList);
		return this.delegate.getPrice(priceList, currency)
		                    .filter(this.pricePredicate);
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices(@Nonnull String priceList) throws ContextMissingException {
		this.pricePredicate.checkFetched(null, priceList);
		return SealedEntity.super.getPrices(priceList);
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices(@Nonnull Currency currency) throws ContextMissingException {
		this.pricePredicate.checkFetched(currency);
		return SealedEntity.super.getPrices(currency);
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices(
		@Nonnull Currency currency, @Nonnull String priceList) throws ContextMissingException {
		this.pricePredicate.checkFetched(currency, priceList);
		return SealedEntity.super.getPrices(currency, priceList);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale(
		@Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment, @Nonnull String... priceListPriority) {
		this.pricePredicate.checkFetched(currency, priceListPriority);
		if (this.pricePredicate.isContextAvailable()) {
			// verify the mandated context
			Assert.isTrue(
				Objects.equals(currency, this.pricePredicate.getCurrency()) &&
					Objects.equals(atTheMoment, this.pricePredicate.getValidIn()) &&
					Arrays.equals(priceListPriority, this.pricePredicate.getPriceLists()),
				() -> "Entity cannot provide price for sale different from the context it was loaded in! " +
					"The entity `" + getPrimaryKey() + "` was fetched using `" + this.pricePredicate.getCurrency() + "` currency, " +
					ofNullable(this.pricePredicate.getValidIn()).map(
						it -> "valid at `" + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(it) + "`").orElse("") +
					" and price list in this priority: " + ofNullable(this.pricePredicate.getPriceLists()).map(
						                                                                                      Arrays::stream)
					                                                                                      .map(
						                                                                                      it -> it.map(
							                                                                                              element -> "`" + element + "`")
						                                                                                              .collect(
							                                                                                              Collectors.joining(
								                                                                                              ", "))) + "."
			);
		}
		return SealedEntity.super.getPriceForSale(currency, atTheMoment, priceListPriority);
	}

	@Override
	public boolean isPriceForSaleContextAvailable() {
		return this.pricePredicate.isContextAvailable();
	}

	@Nonnull
	@Override
	public Optional<PriceForSaleContext> getPriceForSaleContext() {
		return this.pricePredicate.getPriceForSaleContext();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		if (this.pricePredicate.isContextAvailable()) {
			this.pricePredicate.checkPricesFetched();
			// method context available does NullPointer check
			// noinspection DataFlowIssue
			return SealedEntity.super.getPriceForSale(
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		if (this.pricePredicate.isContextAvailable()) {
			this.pricePredicate.checkPricesFetched();
			// method context available does NullPointer check
			// noinspection DataFlowIssue
			return getPriceForSale(
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			return empty();
		}
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale(
		@Nonnull Currency currency, @Nullable OffsetDateTime atTheMoment,
		@Nonnull String... priceListPriority
	) throws ContextMissingException {
		this.pricePredicate.checkFetched(currency, priceListPriority);
		final List<PriceContract> allPricesForSale = SealedEntity.super.getAllPricesForSale(
			currency, atTheMoment, priceListPriority);
		if (allPricesForSale.size() > 1) {
			return allPricesForSale
				.stream()
				.sorted(
					Comparator.comparing(PriceContract::priceId)
					          .thenComparing(
						          PriceContract::innerRecordId,
						          Comparator.nullsLast(Integer::compareTo)
					          )
				).toList();
		} else {
			return allPricesForSale;
		}
	}

	@Nonnull
	@Override
	public List<PriceForSaleWithAccompanyingPrices> getAllPricesForSaleWithAccompanyingPrices() {
		final PriceForSaleContext context = getPriceForSaleContext().orElseThrow(ContextMissingException::new);
		this.pricePredicate.checkFetched(
			context.currency().orElseThrow(ContextMissingException::new),
			Stream.concat(
				Arrays.stream(context.priceListPriority().orElseThrow(ContextMissingException::new)),
				Arrays.stream(context.accompanyingPrices().orElseThrow(ContextMissingException::new))
				      .flatMap(it -> Arrays.stream(it.priceListPriority()))
			).toArray(String[]::new)
		);
		final List<PriceForSaleWithAccompanyingPrices> allPricesForSale = SealedEntity.super.getAllPricesForSaleWithAccompanyingPrices();
		if (allPricesForSale.size() > 1) {
			return allPricesForSale
				.stream()
				.sorted(
					Comparator.comparing(it -> ((PriceForSaleWithAccompanyingPrices) it).priceForSale().priceId())
					          .thenComparing(
						          it -> ((PriceForSaleWithAccompanyingPrices) it).priceForSale().innerRecordId(),
						          Comparator.nullsLast(Integer::compareTo)
					          )
				).toList();
		} else {
			return allPricesForSale;
		}
	}

	@Nonnull
	@Override
	public List<PriceForSaleWithAccompanyingPrices> getAllPricesForSaleWithAccompanyingPrices(
		@Nonnull Currency currency,
		@Nullable OffsetDateTime atTheMoment,
		@Nonnull String[] priceListPriority,
		@Nonnull AccompanyingPrice[] accompanyingPricesRequest
	) {
		this.pricePredicate.checkFetched(currency, priceListPriority);
		final List<PriceForSaleWithAccompanyingPrices> allPricesForSale = SealedEntity.super.getAllPricesForSaleWithAccompanyingPrices(
			currency, atTheMoment, priceListPriority, accompanyingPricesRequest);
		if (allPricesForSale.size() > 1) {
			return allPricesForSale
				.stream()
				.sorted(
					Comparator.comparing(it -> ((PriceForSaleWithAccompanyingPrices) it).priceForSale().priceId())
					          .thenComparing(
						          it -> ((PriceForSaleWithAccompanyingPrices) it).priceForSale().innerRecordId(),
						          Comparator.nullsLast(Integer::compareTo)
					          )
				).toList();
		} else {
			return allPricesForSale;
		}
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		if (this.pricePredicate.isContextAvailable()) {
			this.pricePredicate.checkPricesFetched();
			// method context available does NullPointer check
			// noinspection DataFlowIssue
			return getAllPricesForSale(
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Override
	public boolean hasPriceInInterval(
		@Nonnull BigDecimal from, @Nonnull BigDecimal to,
		@Nonnull QueryPriceMode queryPriceMode
	) throws ContextMissingException {
		if (this.pricePredicate.isContextAvailable()) {
			this.pricePredicate.checkPricesFetched();
			// method context available does NullPointer check
			// noinspection DataFlowIssue
			return hasPriceInInterval(
				from, to, queryPriceMode,
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists()
			);
		} else {
			throw new ContextMissingException();
		}
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		if (this.filteredPrices == null) {
			this.pricePredicate.checkPricesFetched();
			this.filteredPrices = this.delegate.getPrices()
			                                   .stream()
			                                   .filter(this.pricePredicate)
			                                   .collect(Collectors.toList());
		}
		return this.filteredPrices;
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		if (this.pricePredicate.getPriceContentMode() == PriceContentMode.NONE) {
			return PriceInnerRecordHandling.UNKNOWN;
		}
		return this.delegate.getPriceInnerRecordHandling();
	}

	@Nonnull
	public Optional<PriceContract> getPriceForSale(
		@Nonnull Predicate<PriceContract> predicate
	) throws ContextMissingException {
		if (this.pricePredicate.isContextAvailable()) {
			this.pricePredicate.checkPricesFetched();
			// method context available does NullPointer check
			// noinspection DataFlowIssue
			return PricesContract.computePriceForSale(
				getPrices(),
				getPriceInnerRecordHandling(),
				this.pricePredicate.getCurrency(),
				this.pricePredicate.getValidIn(),
				this.pricePredicate.getPriceLists(),
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
		return this.delegate.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return this.delegate.equals(obj instanceof EntityDecorator ? ((EntityDecorator) obj).delegate : obj);
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
		final Set<Locale> locales = this.localePredicate.getLocales();
		return locales != null && locales.size() == 1 ?
			locales.iterator().next() : this.localePredicate.getImplicitLocale();
	}

	/**
	 * Returns true if there was more than one locale used for fetching the entity.
	 *
	 * @return true if there was more than one locale used for fetching the entity
	 */
	public boolean isMultipleLocalesRequested() {
		final Set<Locale> locales = this.localePredicate.getLocales();
		return locales != null && locales.size() != 1;
	}

	/**
	 * Returns true if passed locale was requested when the entity was fetched.
	 *
	 * @param locale locale to check
	 * @return true if passed locale was requested when the entity was fetched
	 */
	public boolean isLocaleRequested(@Nonnull Locale locale) {
		return this.localePredicate.test(locale);
	}

	/**
	 * Ensures that the provided reference is not a duplicate. If the reference is marked as a duplicate,
	 * an exception is thrown. Otherwise, the original reference is returned.
	 *
	 * @param referenceKey  the key of the reference to verify for duplicates
	 * @param reference     the reference found in {@link #getFilteredReferences()}
	 * @return the provided reference if no duplicates are detected
	 * @throws ReferenceAllowsDuplicatesException if the reference is marked as a duplicate
	 */
	@Nullable
	private ReferenceContract avoidDuplicates(@Nonnull ReferenceKey referenceKey, @Nonnull ReferenceContract reference) {
		if (reference == DUPLICATE_REFERENCE) {
			if (referenceKey.isUnknownReference()) {
				throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), this.entitySchema, Operation.READ);
			} else {
				// when this.references contains DUPLICATE_REFERENCE marker,
				// this.duplicateReferences must contain the actual references
				final List<ReferenceContract> duplicatedReferences = Objects.requireNonNull(this.filteredDuplicateReferences.get(referenceKey));
				for (ReferenceContract duplicatedReference : duplicatedReferences) {
					if (duplicatedReference.getReferenceKey().internalPrimaryKey() == referenceKey.internalPrimaryKey()) {
						return duplicatedReference;
					}
				}
				return null;
			}
		} else {
			return reference;
		}
	}

	/**
	 * Retrieves a filtered map of references based on the provided filtering logic.
	 * The method processes references, applies a predicate to select which references are included,
	 * and handles cases where duplicate references (with the same {@link ReferenceKey}) are allowed
	 * based on their schema definitions.
	 *
	 * If the references allow duplicates, they are tracked separately in an internal map.
	 * For references that do not allow duplicates, the values are stored directly in the returned map.
	 *
	 * @return a map of filtered references, where the keys are unique {@link ReferenceKey}
	 * objects and the values are corresponding {@link ReferenceContract} objects.
	 * If duplicates are permitted for certain references, those keys are marked
	 * and handled accordingly.
	 */
	@Nonnull
	private Map<ReferenceKey, ReferenceContract> getFilteredReferences() {
		if (this.filteredReferences == null) {
			final Collection<ReferenceContract> references = this.delegate.getReferences();
			if (references.isEmpty()) {
				this.filteredReferences = Collections.emptyMap();
				this.filteredDuplicateReferences = Collections.emptyMap();
			} else {
				// this quite ugly part is necessary so that we can propely handle references that allow duplicates
				// i.e. references with same ReferenceKey, that are distinguished only by their set of attributes
				Map<ReferenceKey, ReferenceContract> indexedReferences = createLinkedHashMap(references.size());
				Map<ReferenceKey, List<ReferenceContract>> duplicatedIndexedReferences = null;

				// cache last resolved schema to avoid Optional/map overhead on each iteration
				final EntitySchemaContract schema = getSchema();
				ReferenceSchemaContract lastResolvedSchema = null;
				String lastResolvedSchemaName = null;
				Boolean duplicatesAllowed = null;
				ReferenceKey lastReferenceKey = null;

				for (ReferenceContract reference : references) {
					if (this.referencePredicate.test(reference)) {
						final String referenceName = reference.getReferenceName();
						final ReferenceKey referenceKey = reference.getReferenceKey();

						// resolve schema only when reference name changes
						if (lastResolvedSchema == null || !referenceName.equals(lastResolvedSchemaName)) {
							lastResolvedSchema = schema.getReference(referenceName).orElse(null);
							lastResolvedSchemaName = referenceName;
							duplicatesAllowed = lastResolvedSchema == null
								? Cardinality.ZERO_OR_MORE.allowsDuplicates()
								: lastResolvedSchema.getCardinality().allowsDuplicates();
						}

						if (lastReferenceKey != null && lastReferenceKey.equalsInGeneral(referenceKey.primaryKey())) {
							if (duplicatesAllowed) {
								if (duplicatedIndexedReferences == null) {
									duplicatedIndexedReferences = CollectionUtils.createHashMap(references.size());
								}
								final ReferenceKey genericKey = new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey());
								final ReferenceContract previous = indexedReferences.remove(lastReferenceKey);
								final List<ReferenceContract> duplicatedList;
								if (previous == DUPLICATE_REFERENCE) {
									duplicatedList = Objects.requireNonNull(duplicatedIndexedReferences.get(genericKey));
								} else if (previous != null) {
									duplicatedList = new ArrayList<>(2);
									duplicatedIndexedReferences.put(genericKey, duplicatedList);
									duplicatedList.add(previous);
								} else {
									duplicatedList = Objects.requireNonNull(duplicatedIndexedReferences.get(genericKey));
								}
								duplicatedList.add(reference);
								indexedReferences.put(genericKey, DUPLICATE_REFERENCE);
							} else {
								throw new ReferenceAllowsDuplicatesException(referenceKey.referenceName(), schema, Operation.CREATE);
							}
						} else {
							indexedReferences.put(referenceKey, reference);
						}
						lastReferenceKey = referenceKey;
					}
				}

				this.filteredReferences = Collections.unmodifiableMap(indexedReferences);
				this.filteredDuplicateReferences = duplicatedIndexedReferences == null ?
					Collections.emptyMap() :
					Collections.unmodifiableMap(duplicatedIndexedReferences);
			}
		}
		return this.filteredReferences;
	}

	/**
	 * Retrieves a map of filtered references grouped by their reference name.
	 * The filtering is based on a predicate that determines which references are included.
	 * If the references have not been previously filtered, this method processes the delegate's
	 * references, applies the filtering, groups them by name, and transforms them into data chunks.
	 *
	 * @return a non-null map where keys are reference names and values are DataChunk objects containing filtered references.
	 */
	@Nonnull
	private Map<String, DataChunk<ReferenceContract>> getFilteredReferencesByName() {
		if (this.filteredReferencesByName == null) {
			final Collection<ReferenceContract> references = this.delegate.getReferences();
			final Map<String, List<ReferenceContract>> allReferencesByName = createLinkedHashMap(
				this.entitySchema.getReferences().size()
			);
			for (ReferenceContract reference : references) {
				if (this.referencePredicate.test(reference)) {
					allReferencesByName
						.computeIfAbsent(
							reference.getReferenceName(),
							s -> new ArrayList<>(references.size() / this.entitySchema.getReferences().size())
						)
						.add(reference);
				}
			}
			this.filteredReferencesByName = allReferencesByName
				.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Map.Entry::getKey,
						entry -> this.delegate
							.references
							.getReferenceChunkTransformer()
							.apply(entry.getKey())
							.createChunk(entry.getValue())
					)
				);
		}
		return this.filteredReferencesByName;
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
			reference.getGroup().map(group -> referenceGroupEntityFetcher.apply(group.primaryKey())).orElse(null) :
			null;
		return new ReferenceDecorator(
			reference,
			referencedEntity,
			referencedGroupEntity,
			this.referencePredicate.getAttributePredicate(
				referenceSchema.getName()
			)
		);
	}

}
