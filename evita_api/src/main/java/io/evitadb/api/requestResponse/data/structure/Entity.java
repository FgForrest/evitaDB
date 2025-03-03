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
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest.ChunkTransformer;
import io.evitadb.api.requestResponse.EvitaRequest.NoTransformer;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.ParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Based on our experience we've designed following data model for handling entities in evitaDB. Model is rather complex
 * but was designed to limit amount of data fetched from database and minimize an amount of data that are indexed and subject
 * to search.
 *
 * Minimal entity definition consists of:
 *
 * - entity type and
 * - primary key (even this is optional and may be autogenerated by the database).
 *
 * Other entity data is purely optional and may not be used at all.
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link InitialEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public class Entity implements SealedEntity {
	@Serial private static final long serialVersionUID = 8637366499361070438L;
	/**
	 * Default implementation of the chunk transformer that simply wraps the input list into a one page facade.
	 */
	public static final ChunkTransformerAccessor DEFAULT_CHUNK_TRANSFORMER =
		referenceName -> NoTransformer.INSTANCE;

	/**
	 * Contains version of this object and gets increased with any (direct) entity update. Allows to execute
	 * optimistic locking i.e. avoiding parallel modifications.
	 */
	final int version;
	/**
	 * Serializable type of entity. Using Enum type is highly recommended for this key.
	 * Entity type is main sharding key - all data of entities with same type are stored in separated index. Within the
	 * entity type entity is uniquely represented by primary key.
	 * Type is specified in each lookup {@link Query#getCollection()}
	 */
	@Getter @Nonnull final String type;
	/**
	 * Contains definition of the entity.
	 */
	@Getter @Nonnull final EntitySchemaContract schema;
	/**
	 * Unique Integer positive number (max. 2<sup>63</sup>-1) representing the entity. Can be used for fast lookup for
	 * entity (entities). Primary key must be unique within the same entity type.
	 * May be left empty if it should be auto generated by the database.
	 * Entities can by looked up by primary key by using query {@link EntityPrimaryKeyInSet}
	 */
	@Getter @Nullable final Integer primaryKey;
	/**
	 * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and may be
	 * referred by multiple child entities. Hierarchy is always composed of entities of same type.
	 * Each entity must be part of at most single hierarchy (tree).
	 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin}. It's also used for
	 * computation of extra data - such as {@link HierarchyContent}. It can also invert type of returned entities in
	 * case requirement {@link HierarchyOfSelf} is used.
	 */
	@Nullable final Integer parent;
	/**
	 * Contains true if the entity is allowed to have parents by the schema.
	 */
	final boolean withHierarchy;
	/**
	 * The reference refers to other entities (of same or different entity type).
	 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
	 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
	 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
	 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
	 *
	 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
	 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
	 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
	 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
	 * Evita.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 */
	final Map<ReferenceKey, ReferenceContract> references;
	/**
	 * Contains set of all {@link ReferenceSchemaContract#getName()} defined in entity {@link EntitySchemaContract}.
	 */
	final Set<String> referencesDefined;
	/**
	 * Entity (global) attributes allows defining set of data that are fetched in bulk along with the entity body.
	 * Attributes may be indexed for fast filtering ({@link AttributeSchemaContract#isFilterable()}) or can be used to sort along
	 * ({@link AttributeSchemaContract#isSortable()}). Attributes are not automatically indexed in order not to waste precious
	 * memory space for data that will never be used in search queries.
	 *
	 * Filtering in attributes is executed by using constraints like {@link io.evitadb.api.query.filter.And},
	 * {@link io.evitadb.api.query.filter.Not}, {@link AttributeEquals}, {@link AttributeContains}
	 * and many others. Sorting can be achieved with {@link AttributeNatural} or others.
	 *
	 * Attributes are not recommended for bigger data as they are all loaded at once when {@link AttributeContent}
	 * requirement is used. Large data that are occasionally used store in {@link @AssociatedData}.
	 */
	@Delegate(types = EntityAttributes.class) final EntityAttributes attributes;
	/**
	 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
	 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
	 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
	 * binary arrays representing entire files (i.e. pictures, documents).
	 *
	 * The search query must contain specific {@link AssociatedDataContent} requirement in order
	 * associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
	 */
	@Delegate(types = AssociatedDataContract.class) final AssociatedData associatedData;
	/**
	 * Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 * <p>
	 * Specifying prices on entity allows usage of {@link io.evitadb.api.query.filter.PriceValidIn},
	 * {@link io.evitadb.api.query.filter.PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural},
	 * ordering of the entities. Additional requirements
	 * {@link PriceHistogram}, {@link PriceContent}
	 * can be used in query as well.
	 */
	@Delegate(types = PricesContract.class, excludes = Versioned.class)
	@Nonnull final Prices prices;
	/**
	 * Contains set of all {@link Locale} that were used for localized {@link Attributes} or {@link AssociatedData} of
	 * this particular entity.
	 *
	 * Enables using {@link EntityLocaleEquals} filtering query in query.
	 */
	@Getter final Set<Locale> locales;
	/**
	 * Contains TRUE if entity was dropped - i.e. removed. Entities is not removed (unless tidying process
	 * does it), but are lying among other entities with tombstone flag. Dropped entities can be overwritten by
	 * a revived entity continuing with the versioning where it was stopped for the last time.
	 */
	private final boolean dropped;
	/**
	 * Contains the scope of the entity. The scope is used to determine the visibility of the entity in the system.
	 * @see Scope
	 */
	private final Scope scope;
	/**
	 * Contains map of all references by their name. This map is used for fast lookup of the references by their name
	 * and is initialized lazily on first request.
	 */
	private Map<String, DataChunk<ReferenceContract>> referencesByName;
	/**
	 * Contains transformer function that takes FULL list of references by their name and returns page that conforms
	 * to the evita request. The function is called lazily on first request for chunked data. The function should not
	 * trap entire evita request in its closure.
	 */
	private final ChunkTransformerAccessor referenceChunkTransformer;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static Entity _internalBuild(
		@Nullable Integer primaryKey,
		@Nullable Integer version,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Integer parent,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull EntityAttributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales,
		@Nonnull Scope scope,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		return new Entity(
			ofNullable(version).orElse(1),
			entitySchema,
			primaryKey,
			parent,
			references,
			attributes,
			associatedData,
			prices,
			locales,
			scope,
			false,
			referenceChunkTransformer
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static Entity _internalBuild(
		@Nullable Integer primaryKey,
		@Nullable Integer version,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Integer parent,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull EntityAttributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales,
		@Nonnull Set<String> referencesDefined,
		boolean withHierarchy,
		boolean dropped
	) {
		return new Entity(
			ofNullable(version).orElse(1),
			entitySchema,
			primaryKey,
			parent,
			references,
			attributes,
			associatedData,
			prices,
			locales,
			referencesDefined,
			withHierarchy,
			Scope.DEFAULT_SCOPE,
			dropped,
			Entity.DEFAULT_CHUNK_TRANSFORMER
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static Entity _internalBuild(
		int version,
		int primaryKey,
		@Nonnull EntitySchemaContract schema,
		@Nullable Integer parent,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull EntityAttributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales,
		@Nonnull Scope scope,
		boolean dropped,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		return new Entity(
			version, schema, primaryKey,
			parent, references,
			attributes, associatedData, prices,
			locales, scope, dropped,
			referenceChunkTransformer
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static Entity _internalBuild(
		@Nonnull Entity entity,
		int version,
		int primaryKey,
		@Nonnull EntitySchemaContract schema,
		@Nullable Integer parent,
		@Nullable Collection<ReferenceContract> references,
		@Nullable EntityAttributes attributes,
		@Nullable AssociatedData associatedData,
		@Nullable Prices prices,
		@Nullable Set<Locale> locales,
		@Nonnull Scope scope,
		boolean dropped,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		return new Entity(
			version, schema, primaryKey,
			ofNullable(parent).orElse(entity.parent),
			ofNullable(references).orElse(entity.references.values()),
			ofNullable(attributes).orElse(entity.attributes),
			ofNullable(associatedData).orElse(entity.associatedData),
			ofNullable(prices).orElse(entity.prices),
			ofNullable(locales).orElse(entity.locales),
			scope,
			dropped,
			referenceChunkTransformer
		);
	}

	/**
	 * Method allows mutation of the existing entity by the set of local mutations. If the mutations don't change any
	 * data (it may happen that the requested change was already applied by someone else) the very same entity is
	 * returned in the response.
	 */
	@Nonnull
	public static Entity mutateEntity(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Entity entity,
		@Nonnull Collection<? extends LocalMutation<?, ?>> localMutations
	) {
		final Optional<Entity> possibleEntity = ofNullable(entity);

		final Integer oldParent = possibleEntity.map(it -> it.parent).orElse(null);
		Integer newParent = oldParent;
		PriceInnerRecordHandling newPriceInnerRecordHandling = null;
		final Map<AttributeKey, AttributeValue> newAttributes = CollectionUtils.createHashMap(localMutations.size());
		final Map<AssociatedDataKey, AssociatedDataValue> newAssociatedData = CollectionUtils.createHashMap(localMutations.size());
		final Map<ReferenceKey, ReferenceContract> newReferences = CollectionUtils.createHashMap(localMutations.size());
		final Map<PriceKey, PriceContract> newPrices = CollectionUtils.createHashMap(localMutations.size());
		Scope newScope = possibleEntity.map(Entity::getScope).orElse(Scope.DEFAULT_SCOPE);

		for (LocalMutation<?, ?> localMutation : localMutations) {
			if (localMutation instanceof ParentMutation parentMutation) {
				newParent = mutateHierarchyPlacement(entitySchema, possibleEntity, parentMutation);
			} else if (localMutation instanceof AttributeMutation attributeMutation) {
				mutateAttributes(entitySchema, possibleEntity, newAttributes, attributeMutation);
			} else if (localMutation instanceof AssociatedDataMutation associatedDataMutation) {
				mutateAssociatedData(entitySchema, possibleEntity, newAssociatedData, associatedDataMutation);
			} else if (localMutation instanceof ReferenceMutation<?> referenceMutation) {
				mutateReferences(entitySchema, possibleEntity, newReferences, referenceMutation);
			} else if (localMutation instanceof PriceMutation priceMutation) {
				mutatePrices(entitySchema, possibleEntity, newPrices, priceMutation);
			} else if (localMutation instanceof SetPriceInnerRecordHandlingMutation innerRecordHandlingMutation) {
				newPriceInnerRecordHandling = mutateInnerPriceRecordHandling(entitySchema, possibleEntity, innerRecordHandlingMutation);
			} else if (localMutation instanceof SetEntityScopeMutation scopeMutation) {
				newScope = scopeMutation.mutateLocal(entitySchema, newScope);
			}
		}

		// create or reuse existing attribute container
		final EntityAttributes newAttributeContainer = recreateAttributeContainer(entitySchema, possibleEntity, newAttributes);

		// create or reuse existing associated data container
		final AssociatedData newAssociatedDataContainer = recreateAssociatedDataContainer(entitySchema, possibleEntity, newAssociatedData);

		// create or reuse existing reference container
		final ReferenceTuple mergedReferences = recreateReferences(possibleEntity, newReferences);

		// create or reuse existing prices
		final Prices priceContainer = recreatePrices(entitySchema, possibleEntity, newPriceInnerRecordHandling, newPrices);

		// aggregate entity locales
		final Set<Locale> entityLocales = new HashSet<>(newAttributeContainer.getAttributeLocales());
		entityLocales.addAll(newAssociatedDataContainer.getAssociatedDataLocales());

		if (!Objects.equals(newParent, oldParent) ||
			newPriceInnerRecordHandling != null ||
			!newAttributes.isEmpty() ||
			!newAssociatedData.isEmpty() ||
			!newPrices.isEmpty() ||
			!newReferences.isEmpty()
		) {
			return new Entity(
				possibleEntity.map(it -> it.version() + 1).orElse(1),
				entitySchema,
				possibleEntity.map(Entity::getPrimaryKey).orElse(null),
				newParent,
				mergedReferences.references(),
				newAttributeContainer,
				newAssociatedDataContainer,
				priceContainer,
				entityLocales,
				mergedReferences.referencesDefined(),
				entitySchema.isWithHierarchy() || newParent != null,
				newScope,
				false,
				entity.referenceChunkTransformer
			);
		} else if (entity == null) {
			return new Entity(entitySchema.getName(), null);
		} else {
			return entity;
		}
	}

	/**
	 * Method allows to create copy of the entity object with up-to-date schema definition. Data of the original
	 * entity are kept untouched.
	 */
	@Nonnull
	public static EntityDecorator decorate(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable EntityClassifierWithParent parentEntity,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchySerializablePredicate hierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		@Nullable ReferenceFetcher referenceFetcher
	) {
		return referenceFetcher == null || referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION ?
			new EntityDecorator(
				entity, entitySchema, parentEntity,
				localePredicate, hierarchyPredicate,
				attributePredicate, associatedDataValuePredicate,
				referencePredicate, pricePredicate,
				alignedNow
			)
			:
			new EntityDecorator(
				entity, entitySchema, parentEntity,
				localePredicate, hierarchyPredicate,
				attributePredicate, associatedDataValuePredicate,
				referencePredicate, pricePredicate,
				alignedNow,
				referenceFetcher
			);
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nonnull
	private static Prices recreatePrices(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nullable PriceInnerRecordHandling newPriceInnerRecordHandling,
		@Nonnull Map<PriceKey, PriceContract> newPrices
	) {
		final Prices priceContainer;
		if (newPrices.isEmpty()) {
			priceContainer = ofNullable(newPriceInnerRecordHandling)
				.map(npirc -> possibleEntity
					.map(it -> new Prices(entitySchema, it.version() + 1, it.getPrices(), npirc, !it.getPrices().isEmpty()))
					.orElseGet(() -> new Prices(entitySchema, 1, Collections.emptyList(), npirc, false))
				).orElseGet(() -> possibleEntity
					.map(it -> it.prices)
					.orElseGet(() -> new Prices(entitySchema, 1, Collections.emptyList(), PriceInnerRecordHandling.NONE, false)));
		} else {
			final List<PriceContract> mergedPrices = Stream.concat(
				possibleEntity.map(Entity::getPrices).orElseGet(Collections::emptyList)
					.stream()
					.filter(it -> !newPrices.containsKey(it.priceKey())),
				newPrices.values().stream()
			).toList();

			priceContainer = ofNullable(newPriceInnerRecordHandling)
				.map(npirc -> possibleEntity
					.map(it -> new Prices(entitySchema, it.version() + 1, mergedPrices, npirc, !mergedPrices.isEmpty()))
					.orElseGet(() -> new Prices(entitySchema, 1, mergedPrices, npirc, !mergedPrices.isEmpty()))
				).orElseGet(() -> possibleEntity
					.map(it -> new Prices(entitySchema, it.version + 1, mergedPrices, it.getPriceInnerRecordHandling(), !mergedPrices.isEmpty()))
					.orElseGet(() -> new Prices(entitySchema, 1, mergedPrices, PriceInnerRecordHandling.NONE, !mergedPrices.isEmpty())));
		}
		return priceContainer;
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nonnull
	private static ReferenceTuple recreateReferences(
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<ReferenceKey, ReferenceContract> newReferences
	) {
		final Set<String> mergedTypes;
		final Collection<ReferenceContract> mergedReferences;
		if (newReferences.isEmpty()) {
			mergedTypes = possibleEntity
				.map(Entity::getSchema)
				.map(EntitySchemaContract::getReferences)
				.map(Map::keySet)
				.orElseGet(Collections::emptySet);
			mergedReferences = possibleEntity
				.map(Entity::getReferences)
				.orElseGet(Collections::emptyList);
		} else {
			mergedTypes = Stream.concat(
					possibleEntity
						.map(Entity::getSchema)
						.map(EntitySchemaContract::getReferences)
						.map(Map::keySet)
						.orElseGet(Collections::emptySet)
						.stream(),
					newReferences.values()
						.stream()
						.map(ReferenceContract::getReferenceName)
				)
				.collect(Collectors.toSet());
			mergedReferences = Stream.concat(
				possibleEntity.map(Entity::getReferences).orElseGet(Collections::emptyList)
					.stream()
					.filter(it -> !newReferences.containsKey(it.getReferenceKey())),
				newReferences.values().stream()
			).toList();
		}
		return new ReferenceTuple(
			mergedReferences,
			mergedTypes
		);
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nonnull
	private static AssociatedData recreateAssociatedDataContainer(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<AssociatedDataKey, AssociatedDataValue> newAssociatedData
	) {
		final AssociatedData newAssociatedDataContainer;
		if (newAssociatedData.isEmpty()) {
			newAssociatedDataContainer = possibleEntity
				.map(it -> it.associatedData)
				.orElseGet(() -> new AssociatedData(entitySchema));
		} else {
			newAssociatedDataContainer = new AssociatedData(
				entitySchema,
				Stream.concat(
					possibleEntity.map(Entity::getAssociatedDataValues).orElseGet(Collections::emptyList)
						.stream()
						.filter(it -> !newAssociatedData.containsKey(it.key())),
					newAssociatedData.values().stream()
				).toList(),
				Stream.concat(
						entitySchema.getAssociatedData().values().stream(),
						newAssociatedData.values().stream()
							.filter(it -> !entitySchema.getAssociatedData().containsKey(it.key().associatedDataName()))
							.map(AssociatedDataBuilder::createImplicitSchema)
					)
					.collect(
						Collectors.toMap(
							NamedSchemaContract::getName,
							Function.identity()
						)
					)
			);
		}
		return newAssociatedDataContainer;
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nonnull
	private static EntityAttributes recreateAttributeContainer(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<AttributeKey, AttributeValue> newAttributes
	) {
		final EntityAttributes newAttributeContainer;
		if (newAttributes.isEmpty()) {
			newAttributeContainer = possibleEntity
				.map(it -> it.attributes)
				.orElseGet(() -> new EntityAttributes(entitySchema));
		} else {
			final Map<AttributeKey, AttributeValue> attributes = Stream.concat(
					possibleEntity.map(Entity::getAttributeValues).orElseGet(Collections::emptyList)
						.stream()
						.filter(it -> !newAttributes.containsKey(it.key())),
					newAttributes.values().stream()
				)
				.collect(
					Collectors.toMap(
						AttributeValue::key,
						Function.identity(),
						(o, n) -> {
							throw new EvitaInvalidUsageException("Duplicate attribute key " + o.key());
						},
						LinkedHashMap::new
					)
				);
			final Map<String, EntityAttributeSchemaContract> attributeTypes = Stream.concat(
					entitySchema.getAttributes().values().stream(),
					newAttributes.values().stream()
						.filter(it -> !entitySchema.getAttributes().containsKey(it.key().attributeName()))
						.map(AttributesBuilder::createImplicitEntityAttributeSchema)
				)
				.collect(
					Collectors.toMap(
						NamedSchemaContract::getName,
						Function.identity(),
						(o, n) -> {
							throw new EvitaInvalidUsageException("Duplicate attribute key " + o.getName());
						},
						LinkedHashMap::new
					)
				);
			newAttributeContainer = new EntityAttributes(
				entitySchema,
				attributes,
				attributeTypes
			);
		}
		return newAttributeContainer;
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nullable
	private static PriceInnerRecordHandling mutateInnerPriceRecordHandling(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull SetPriceInnerRecordHandlingMutation innerRecordHandlingMutation
	) {
		PriceInnerRecordHandling newPriceInnerRecordHandling;
		final PricesContract existingPrices = possibleEntity.map(it -> it.prices).orElse(null);
		final PricesContract newPriceContainer = returnIfChanged(
			existingPrices,
			innerRecordHandlingMutation.mutateLocal(entitySchema, existingPrices)
		);
		newPriceInnerRecordHandling = ofNullable(newPriceContainer)
			.map(PricesContract::getPriceInnerRecordHandling)
			.orElse(null);
		return newPriceInnerRecordHandling;
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void mutatePrices(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<PriceKey, PriceContract> newPrices,
		@Nonnull PriceMutation priceMutation
	) {
		final PriceContract existingPriceValue = possibleEntity
			.flatMap(it -> it.getPrice(priceMutation.getPriceKey()))
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingPriceValue,
				priceMutation.mutateLocal(entitySchema, existingPriceValue)
			)
		).ifPresent(it -> newPrices.put(it.priceKey(), it));
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void mutateReferences(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<ReferenceKey, ReferenceContract> newReferences,
		@Nonnull ReferenceMutation<?> referenceMutation) {
		final ReferenceContract existingReferenceValue = ofNullable(newReferences.get(referenceMutation.getReferenceKey()))
			.or(() -> possibleEntity.flatMap(it -> it.getReferenceWithoutSchemaCheck(referenceMutation.getReferenceKey())))
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingReferenceValue,
				referenceMutation.mutateLocal(entitySchema, existingReferenceValue)
			)
		).ifPresent(it -> newReferences.put(it.getReferenceKey(), it));
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void mutateAssociatedData(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<AssociatedDataKey, AssociatedDataValue> newAssociatedData,
		@Nonnull AssociatedDataMutation associatedDataMutation
	) {
		final AssociatedDataValue existingAssociatedDataValue = possibleEntity
			.flatMap(it -> {
				final AssociatedDataKey associatedDataKey = associatedDataMutation.getAssociatedDataKey();
				// we need to do this because new associated data can be added on the fly and getting them would trigger
				// an exception
				return it.getAssociatedDataNames().contains(associatedDataKey.associatedDataName()) ?
					it.getAssociatedDataValue(associatedDataKey) : empty();
			})
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingAssociatedDataValue,
				associatedDataMutation.mutateLocal(entitySchema, existingAssociatedDataValue)
			)
		).ifPresent(it -> newAssociatedData.put(it.key(), it));
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void mutateAttributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<AttributeKey, AttributeValue> newAttributes,
		@Nonnull AttributeMutation attributeMutation
	) {
		final AttributeValue existingAttributeValue = possibleEntity
			.flatMap(it -> {
				final AttributeKey attributeKey = attributeMutation.getAttributeKey();
				// we need to do this because new attributes can be added on the fly and getting them would trigger
				// an exception
				return it.getAttributeNames().contains(attributeKey.attributeName()) ?
					it.getAttributeValue(attributeKey) : empty();
			})
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingAttributeValue,
				attributeMutation.mutateLocal(entitySchema, existingAttributeValue)
			)
		).ifPresent(it -> newAttributes.put(it.key(), it));
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nullable
	private static Integer mutateHierarchyPlacement(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull ParentMutation parentMutation
	) {
		OptionalInt newParent;
		final OptionalInt existingPlacement = possibleEntity
			.map(Entity::getParent)
			.orElse(OptionalInt.empty());
		newParent = parentMutation.mutateLocal(entitySchema, existingPlacement);
		return newParent.stream().boxed().findAny().orElse(null);
	}

	/**
	 * Method will check whether the original value is exactly same as mutated value (the version id is compared).
	 * If not the NULL is returned instead of `mutatedValue`.
	 */
	@Nullable
	private static <T extends Versioned> T returnIfChanged(@Nullable T originalValue, @Nonnull T mutatedValue) {
		if (mutatedValue.version() > ofNullable(originalValue).map(Versioned::version).orElse(0)) {
			return mutatedValue;
		} else {
			return null;
		}
	}

	/**
	 * Entities are not meant to be constructed by the client code. Use {@link InitialEntityBuilder} to create new or update
	 * existing entities.
	 */
	private Entity(
		int version,
		@Nonnull EntitySchemaContract schema,
		@Nullable Integer primaryKey,
		@Nullable Integer parent,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull EntityAttributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales,
		@Nonnull Scope scope,
		boolean dropped,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		this(
			version,
			schema,
			primaryKey,
			parent,
			references,
			attributes,
			associatedData,
			prices,
			locales,
			schema.getReferences().keySet(),
			schema.isWithHierarchy(),
			scope,
			dropped,
			referenceChunkTransformer
		);
	}

	public Entity(@Nonnull String type, @Nullable Integer primaryKey) {
		this.version = 1;
		this.type = type;
		this.schema = EntitySchema._internalBuild(type);
		this.primaryKey = primaryKey;
		this.parent = null;
		this.withHierarchy = this.schema.isWithHierarchy();
		this.references = Collections.emptyMap();
		this.referencesDefined = Collections.emptySet();
		this.attributes = new EntityAttributes(this.schema);
		this.associatedData = new AssociatedData(this.schema);
		this.prices = new io.evitadb.api.requestResponse.data.structure.Prices(
			this.schema, 1, Collections.emptySet(), PriceInnerRecordHandling.NONE
		);
		this.locales = Collections.emptySet();
		this.scope = Scope.DEFAULT_SCOPE;
		this.dropped = false;
		this.referenceChunkTransformer = Entity.DEFAULT_CHUNK_TRANSFORMER;
	}

	/**
	 * Entities are not meant to be constructed by the client code. Use {@link InitialEntityBuilder} to create new or update
	 * existing entities.
	 */
	private Entity(
		int version,
		@Nonnull EntitySchemaContract schema,
		@Nullable Integer primaryKey,
		@Nullable Integer parent,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull EntityAttributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales,
		@Nonnull Set<String> referencesDefined,
		boolean withHierarchy,
		@Nonnull Scope scope,
		boolean dropped,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		this.version = version;
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = primaryKey;
		this.parent = parent;
		this.withHierarchy = withHierarchy;
		this.references = Collections.unmodifiableMap(
			references
				.stream()
				.collect(
					Collectors.toMap(
						ReferenceContract::getReferenceKey,
						Function.identity(),
						(o, o2) -> {
							throw new EvitaInvalidUsageException("Sanity check: " + o + ", " + o2);
						},
						LinkedHashMap::new
					)
				)
		);
		this.referencesDefined = referencesDefined;
		this.attributes = attributes;
		this.associatedData = associatedData;
		this.prices = prices;
		this.locales = Collections.unmodifiableSet(locales);
		this.scope = scope;
		this.dropped = dropped;
		this.referenceChunkTransformer = referenceChunkTransformer;
	}

	@Override
	public boolean parentAvailable() {
		return this.withHierarchy;
	}

	/**
	 * Returns hierarchy information about the entity. Hierarchy information allows to compose hierarchy tree composed
	 * of entities of the same type. Referenced entity is always entity of the same type. Referenced entity must be
	 * already present in the evitaDB and must also have hierarchy placement set. Root `parentPrimaryKey` (i.e. parent
	 * for top-level hierarchical placements) is null.
	 *
	 * Entities may be organized in hierarchical fashion. That means that entity may refer to single parent entity and
	 * may be referred by multiple child entities. Hierarchy is always composed of entities of same type.
	 * Each entity must be part of at most single hierarchy (tree).
	 *
	 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin}. It's also used for
	 * computation of extra data - such as {@link HierarchyOfSelf}. It can also invert type of returned entities in case
	 * requirement {@link HierarchyOfReference} is used.
	 *
	 * @throws EntityIsNotHierarchicalException when {@link EntitySchemaContract#isWithHierarchy()} is false
	 */
	@Nonnull
	public OptionalInt getParent() throws EntityIsNotHierarchicalException {
		Assert.isTrue(
			this.withHierarchy,
			() -> new EntityIsNotHierarchicalException(this.schema.getName())
		);
		return this.parent == null ? OptionalInt.empty() : OptionalInt.of(this.parent);
	}

	@Nonnull
	@Override
	public Optional<EntityClassifierWithParent> getParentEntity() {
		Assert.isTrue(
			this.withHierarchy,
			() -> new EntityIsNotHierarchicalException(this.schema.getName())
		);
		return ofNullable(this.parent)
			.map(it -> new EntityReferenceWithParent(this.type, it, null));
	}

	@Override
	public boolean referencesAvailable() {
		return true;
	}

	@Override
	public boolean referencesAvailable(@Nonnull String referenceName) {
		return true;
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return this.references.values();
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return this.getReferenceChunk(referenceName).getData();
	}

	@Nonnull
	@Override
	public <T extends DataChunk<ReferenceContract>> T getReferenceChunk(@Nonnull String referenceName) throws ContextMissingException {
		checkReferenceName(referenceName);
		if (this.referencesByName == null) {
			// here we never limit the references by input requirements
			// this is managed on entity decorator level
			this.referencesByName = this.references
				.entrySet()
				.stream()
				.collect(
					Collectors.groupingBy(
						it -> it.getKey().referenceName(),
						Collectors.mapping(
							Entry::getValue,
							Collectors.collectingAndThen(
								Collectors.toList(),
								PlainChunk::new
							)
						)
					)
				);
		}
		//noinspection unchecked
		return (T) this.referencesByName.computeIfAbsent(
			referenceName,
			refName -> this.referenceChunkTransformer.apply(refName).createChunk(Collections.emptyList())
		);
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		checkReferenceName(referenceName);
		return ofNullable(this.references.get(new ReferenceKey(referenceName, referencedEntityId)));
	}

	@Nonnull
	@Override
	public Set<Locale> getAllLocales() {
		return this.locales;
	}

	/**
	 * Checks whether the reference is defined in the schema or is otherwise known.
	 */
	public void checkReferenceName(@Nonnull String referenceName) {
		Assert.isTrue(
			this.referencesDefined.contains(referenceName),
			() -> new ReferenceNotFoundException(referenceName, this.schema)
		);
	}

	/**
	 * Returns reference contract without checking the existence in the schema.
	 * Part of the private API.
	 */
	@Nonnull
	public Optional<ReferenceContract> getReferenceWithoutSchemaCheck(@Nonnull ReferenceKey referenceKey) {
		return ofNullable(this.references.get(referenceKey));
	}

	@Nonnull
	public Optional<ReferenceContract> getReference(@Nonnull ReferenceKey referenceKey) {
		checkReferenceName(referenceKey.referenceName());
		return ofNullable(this.references.get(referenceKey));
	}

	@Nonnull
	@Override
	public Scope getScope() {
		return this.scope;
	}

	@Override
	public boolean dropped() {
		return this.dropped;
	}

	@Override
	public int version() {
		return this.version;
	}

	@Nonnull
	@Override
	public EntityBuilder openForWrite() {
		return new ExistingEntityBuilder(this);
	}

	@Nonnull
	@Override
	public EntityBuilder withMutations(@Nonnull LocalMutation<?, ?>... localMutations) {
		return new ExistingEntityBuilder(
			this,
			Arrays.asList(localMutations)
		);
	}

	@Nonnull
	@Override
	public EntityBuilder withMutations(@Nonnull Collection<LocalMutation<?, ?>> localMutations) {
		return new ExistingEntityBuilder(
			this,
			localMutations
		);
	}

	/**
	 * This method is part of the internal API and is not meant to be used by the client code.
	 */
	@Nonnull
	public ChunkTransformerAccessor getReferenceChunkTransformer() {
		return referenceChunkTransformer;
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + this.version;
		result = 31 * result + this.type.hashCode();
		result = 31 * result + (this.primaryKey == null ? 0 : this.primaryKey.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Entity entity = (Entity) o;
		return this.version == entity.version && this.type.equals(entity.type) && Objects.equals(this.primaryKey, entity.primaryKey);
	}

	@Override
	public String toString() {
		return describe();
	}

	/**
	 * DTO for passing merged references and their types.
	 */
	private record ReferenceTuple(
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull Set<String> referencesDefined
	) {
	}

	/**
	 * This interface provides access to chunk transformers for particular reference name. The implementations are
	 * not ought to check the existence of reference in the schema and simply fall back to {@link NoTransformer}
	 * implementation if necessary, but they must never return NULL.
	 */
	public interface ChunkTransformerAccessor {

		/**
		 * Returns chunk transformer for the given reference name.
		 *
		 * @param referenceName name of the reference
		 * @return chunk transformer for the given reference name, never NULL
		 */
		@Nonnull
		ChunkTransformer apply(@Nonnull String referenceName);

	}

}
