/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FacetInSet;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.HierarchyParentsOfSelf;
import io.evitadb.api.query.require.HierarchyStatisticsOfSelf;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.HierarchicalPlacementContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.HierarchicalPlacementMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchicalContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.LocaleSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
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
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public class Entity implements SealedEntity {
	@Serial private static final long serialVersionUID = 8637366499361070438L;

	/**
	 * Contains version of this object and gets increased with any (direct) entity update. Allows to execute
	 * optimistic locking i.e. avoiding parallel modifications.
	 *
	 * TOBEDONE JNO verify situations by writing new tests when this version gets incremented - it should correspond with EntityBodyStoragePart
	 */
	@Getter final int version;
	/**
	 * Serializable type of entity. Using Enum type is highly recommended for this key.
	 * Entity type is main sharding key - all data of entities with same type are stored in separated index. Within the
	 * entity type entity is uniquely represented by primary key.
	 * Type is specified in each lookup {@link Query#getEntities()}
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
	 * computation of extra data - such as {@link HierarchyParentsOfSelf}. It can also invert type of returned entities in case requirement
	 * {@link HierarchyStatisticsOfSelf} is used.
	 */
	@Nullable final HierarchicalPlacementContract hierarchicalPlacement;
	/**
	 * The reference refers to other entities (of same or different entity type).
	 * Allows entity filtering (but not sorting) of the entities by using {@link FacetInSet} query
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
	final Attributes attributes;
	/**
	 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
	 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
	 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
	 * binary arrays representing entire files (i.e. pictures, documents).
	 *
	 * The search query must contain specific {@link AssociatedDataContent} requirement in order
	 * associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
	 */
	final AssociatedData associatedData;
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
	@Nonnull final io.evitadb.api.requestResponse.data.structure.Prices prices;
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
	@Getter private final boolean dropped;

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
		@Nullable HierarchicalPlacementContract hierarchicalPlacement,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull Attributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales
	) {
		return new Entity(
			ofNullable(version).orElse(1),
			entitySchema,
			primaryKey,
			hierarchicalPlacement,
			references,
			attributes,
			associatedData,
			prices,
			locales,
			false
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
		@Nullable HierarchicalPlacementContract hierarchicalPlacement,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull Attributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull Prices prices,
		@Nonnull Set<Locale> locales,
		boolean dropped
	) {
		return new Entity(
			version, schema, primaryKey,
			hierarchicalPlacement, references,
			attributes, associatedData, prices,
			locales, dropped
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
		@Nullable HierarchicalPlacementContract hierarchicalPlacement,
		@Nullable Collection<ReferenceContract> references,
		@Nullable Attributes attributes,
		@Nullable AssociatedData associatedData,
		@Nullable Prices prices,
		@Nullable Set<Locale> locales,
		boolean dropped
	) {
		return new Entity(
			version, schema, primaryKey,
			ofNullable(hierarchicalPlacement).orElse(entity.hierarchicalPlacement),
			ofNullable(references).orElse(entity.references.values()),
			ofNullable(attributes).orElse(entity.attributes),
			ofNullable(associatedData).orElse(entity.associatedData),
			ofNullable(prices).orElse(entity.prices),
			ofNullable(locales).orElse(entity.locales),
			dropped
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

		HierarchicalPlacementContract newPlacement = null;
		PriceInnerRecordHandling newPriceInnerRecordHandling = null;
		final Map<AttributeKey, AttributeValue> newAttributes = CollectionUtils.createHashMap(localMutations.size());
		final Map<AssociatedDataKey, AssociatedDataValue> newAssociatedData = CollectionUtils.createHashMap(localMutations.size());
		final Map<ReferenceKey, ReferenceContract> newReferences = CollectionUtils.createHashMap(localMutations.size());
		final Map<PriceKey, PriceContract> newPrices = CollectionUtils.createHashMap(localMutations.size());

		for (LocalMutation<?, ?> localMutation : localMutations) {
			if (localMutation instanceof HierarchicalPlacementMutation hierarchicalPlacementMutation) {
				newPlacement = mutateHierarchyPlacement(entitySchema, possibleEntity, hierarchicalPlacementMutation);
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
			}
		}

		// create or reuse existing attribute container
		final Attributes newAttributeContainer = recreateAttributeContainer(entitySchema, possibleEntity, newAttributes);

		// create or reuse existing associated data container
		final AssociatedData newAssociatedDataContainer = recreateAssociatedDataContainer(entitySchema, possibleEntity, newAssociatedData);

		// create or reuse existing reference container
		final Collection<ReferenceContract> mergedReferences = recreateReferences(possibleEntity, newReferences);

		// create or reuse existing prices
		final Prices priceContainer = recreatePrices(possibleEntity, newPriceInnerRecordHandling, newPrices);

		// aggregate entity locales
		final Set<Locale> entityLocales = new HashSet<>(newAttributeContainer.getAttributeLocales());
		entityLocales.addAll(newAssociatedDataContainer.getAssociatedDataLocales());

		if (newPlacement != null || newPriceInnerRecordHandling != null ||
			!newAttributes.isEmpty() || !newAssociatedData.isEmpty() || !newPrices.isEmpty() || !newReferences.isEmpty()) {
			return new Entity(
				possibleEntity.map(it -> it.getVersion() + 1).orElse(1),
				entitySchema,
				possibleEntity.map(Entity::getPrimaryKey).orElse(null),
				ofNullable(newPlacement).orElseGet(() -> possibleEntity.flatMap(Entity::getHierarchicalPlacement).orElse(null)),
				mergedReferences,
				newAttributeContainer,
				newAssociatedDataContainer,
				priceContainer,
				entityLocales,
				false
			);
		} else if (entity == null) {
			return new Entity(entitySchema.getName(), null);
		} else {
			return entity;
		}
	}

	/**
	 * Method allows to create the entityDecorator object with up-to-date schema definition. Data of the entity are kept
	 * untouched.
	 */
	public static EntityDecorator decorate(
		@Nonnull Entity entity,
		@Nonnull EntitySchema entitySchema,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchicalContractSerializablePredicate hierarchicalPlacementPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow
	) {
		return new EntityDecorator(
			entity, entitySchema,
			localePredicate, hierarchicalPlacementPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow
		);
	}

	/**
	 * Method allows to create copy of the entityDecorator object with up-to-date schema definition. Data of the original
	 * entityDecorator are kept untouched.
	 */
	public static EntityDecorator decorate(
		@Nonnull EntityDecorator entityDecorator,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchicalContractSerializablePredicate hierarchicalPlacementPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow
	) {
		return new EntityDecorator(
			entityDecorator,
			localePredicate, hierarchicalPlacementPredicate,
			attributePredicate, associatedDataValuePredicate,
			referencePredicate, pricePredicate,
			alignedNow
		);
	}

	/**
	 * Method allows to create copy of the entity object with up-to-date schema definition. Data of the original
	 * entity are kept untouched.
	 */
	public static EntityDecorator decorate(
		@Nonnull Entity entity,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull LocaleSerializablePredicate localePredicate,
		@Nonnull HierarchicalContractSerializablePredicate hierarchicalPlacementPredicate,
		@Nonnull AttributeValueSerializablePredicate attributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate associatedDataValuePredicate,
		@Nonnull ReferenceContractSerializablePredicate referencePredicate,
		@Nonnull PriceContractSerializablePredicate pricePredicate,
		@Nonnull OffsetDateTime alignedNow,
		@Nullable ReferenceFetcher referenceFetcher
	) {
		return referenceFetcher == null || referenceFetcher == ReferenceFetcher.NO_IMPLEMENTATION ?
			new EntityDecorator(
				entity,
				entitySchema,
				localePredicate, hierarchicalPlacementPredicate,
				attributePredicate, associatedDataValuePredicate,
				referencePredicate, pricePredicate,
				alignedNow
			)
			:
			new EntityDecorator(
				entity,
				entitySchema,
				localePredicate, hierarchicalPlacementPredicate,
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
		@Nonnull Optional<Entity> possibleEntity,
		@Nullable PriceInnerRecordHandling newPriceInnerRecordHandling,
		@Nonnull Map<PriceKey, PriceContract> newPrices
	) {
		final Prices priceContainer;
		if (newPrices.isEmpty()) {
			priceContainer = ofNullable(newPriceInnerRecordHandling)
				.map(npirc -> possibleEntity
					.map(it -> new Prices(it.getVersion() + 1, it.getPrices(), npirc))
					.orElseGet(() -> new Prices(npirc))
				).orElseGet(() -> possibleEntity
					.map(it -> it.prices)
					.orElseGet(() -> new Prices(PriceInnerRecordHandling.NONE)));
		} else {
			final List<PriceContract> mergedPrices = Stream.concat(
				possibleEntity.map(Entity::getPrices).orElseGet(Collections::emptyList)
					.stream()
					.filter(it -> !newPrices.containsKey(it.getPriceKey())),
				newPrices.values().stream()
			).toList();

			priceContainer = ofNullable(newPriceInnerRecordHandling)
				.map(npirc -> possibleEntity
					.map(it -> new Prices(it.getVersion() + 1, mergedPrices, npirc))
					.orElseGet(() -> new Prices(npirc))
				).orElseGet(() -> possibleEntity
					.map(it -> new Prices(mergedPrices, it.getPriceInnerRecordHandling()))
					.orElseGet(() -> new Prices(mergedPrices, PriceInnerRecordHandling.NONE)));
		}
		return priceContainer;
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nonnull
	private static Collection<ReferenceContract> recreateReferences(
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<ReferenceKey, ReferenceContract> newReferences
	) {
		final Collection<ReferenceContract> mergedReferences;
		if (newReferences.isEmpty()) {
			mergedReferences = possibleEntity
				.map(Entity::getReferences)
				.orElseGet(Collections::emptyList);
		} else {
			mergedReferences = Stream.concat(
				possibleEntity.map(Entity::getReferences).orElseGet(Collections::emptyList)
					.stream()
					.filter(it -> !newReferences.containsKey(it.getReferenceKey())),
				newReferences.values().stream()
			).toList();
		}
		return mergedReferences;
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
						.filter(it -> !newAssociatedData.containsKey(it.getKey())),
					newAssociatedData.values().stream()
				).toList()
			);
		}
		return newAssociatedDataContainer;
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nonnull
	private static Attributes recreateAttributeContainer(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull Map<AttributeKey, AttributeValue> newAttributes
	) {
		final Attributes newAttributeContainer;
		if (newAttributes.isEmpty()) {
			newAttributeContainer = possibleEntity
				.map(it -> it.attributes)
				.orElseGet(() -> new Attributes(entitySchema));
		} else {
			newAttributeContainer = new Attributes(
				entitySchema,
				Stream.concat(
					possibleEntity.map(Entity::getAttributeValues).orElseGet(Collections::emptyList)
						.stream()
						.filter(it -> !newAttributes.containsKey(it.getKey())),
					newAttributes.values().stream()
				).toList()
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
		final Prices existingPrices = possibleEntity.map(it -> it.prices).orElse(null);
		final Prices newPriceContainer = returnIfChanged(
			existingPrices,
			innerRecordHandlingMutation.mutateLocal(entitySchema, existingPrices)
		);
		newPriceInnerRecordHandling = ofNullable(newPriceContainer)
			.map(Prices::getPriceInnerRecordHandling)
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
			.map(it -> it.getPrice(priceMutation.getPriceKey()))
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingPriceValue,
				priceMutation.mutateLocal(entitySchema, existingPriceValue)
			)
		).ifPresent(it -> newPrices.put(it.getPriceKey(), it));
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static void mutateReferences(
		@Nonnull EntitySchemaContract entitySchema,
		Optional<Entity> possibleEntity,
		@Nonnull Map<ReferenceKey, ReferenceContract> newReferences,
		@Nonnull ReferenceMutation<?> referenceMutation) {
		final ReferenceContract existingReferenceValue = possibleEntity
			.map(it -> it.getReference(referenceMutation.getReferenceKey()))
			.orElseGet(() -> newReferences.get(referenceMutation.getReferenceKey()));
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
			.map(it -> it.getAssociatedDataValue(associatedDataMutation.getAssociatedDataKey()))
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingAssociatedDataValue,
				associatedDataMutation.mutateLocal(entitySchema, existingAssociatedDataValue)
			)
		).ifPresent(it -> newAssociatedData.put(it.getKey(), it));
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
			.map(it -> it.getAttributeValue(attributeMutation.getAttributeKey()))
			.orElse(null);
		ofNullable(
			returnIfChanged(
				existingAttributeValue,
				attributeMutation.mutateLocal(entitySchema, existingAttributeValue)
			)
		).ifPresent(it -> newAttributes.put(it.getKey(), it));
	}

	/**
	 * Helper method for {@link #mutateEntity(EntitySchemaContract, Entity, Collection)}
	 */
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	@Nullable
	private static HierarchicalPlacementContract mutateHierarchyPlacement(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Optional<Entity> possibleEntity,
		@Nonnull HierarchicalPlacementMutation hierarchicalPlacementMutation
	) {
		HierarchicalPlacementContract newPlacement;
		final HierarchicalPlacementContract existingPlacement = possibleEntity
			.flatMap(Entity::getHierarchicalPlacement)
			.orElse(null);
		newPlacement = returnIfChanged(
			existingPlacement,
			hierarchicalPlacementMutation.mutateLocal(entitySchema, existingPlacement)
		);
		return newPlacement;
	}

	/**
	 * Method will check whether the original value is exactly same as mutated value (the version id is compared).
	 * If not the NULL is returned instead of `mutatedValue`.
	 */
	@Nullable
	private static <T extends Versioned> T returnIfChanged(@Nullable T originalValue, @Nonnull T mutatedValue) {
		if (mutatedValue.getVersion() > ofNullable(originalValue).map(Versioned::getVersion).orElse(0)) {
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
		@Nullable HierarchicalPlacementContract hierarchicalPlacement,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull Attributes attributes,
		@Nonnull AssociatedData associatedData,
		@Nonnull io.evitadb.api.requestResponse.data.structure.Prices prices,
		@Nonnull Set<Locale> locales,
		boolean dropped
	) {
		this.version = version;
		this.type = schema.getName();
		this.schema = schema;
		this.primaryKey = primaryKey;
		this.hierarchicalPlacement = hierarchicalPlacement;
		this.references = references
			.stream()
			.collect(
				Collectors.toUnmodifiableMap(
					ReferenceContract::getReferenceKey,
					Function.identity()
				)
			);
		this.attributes = attributes;
		this.associatedData = associatedData;
		this.prices = prices;
		this.locales = Collections.unmodifiableSet(locales);
		this.dropped = dropped;
	}

	public Entity(@Nonnull String type, @Nullable Integer primaryKey) {
		this.version = 1;
		this.type = type;
		this.schema = EntitySchema._internalBuild(type);
		this.primaryKey = primaryKey;
		this.hierarchicalPlacement = null;
		this.references = Collections.emptyMap();
		this.attributes = new Attributes(this.schema);
		this.associatedData = new AssociatedData(this.schema);
		this.prices = new io.evitadb.api.requestResponse.data.structure.Prices(1, Collections.emptySet(), PriceInnerRecordHandling.NONE);
		this.locales = Collections.emptySet();
		this.dropped = false;
	}

	@Nonnull
	@Override
	public Optional<HierarchicalPlacementContract> getHierarchicalPlacement() {
		return ofNullable(hierarchicalPlacement);
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences() {
		return references.values();
	}

	@Nonnull
	@Override
	public Collection<ReferenceContract> getReferences(@Nonnull String referenceName) {
		return references
			.values()
			.stream()
			.filter(it -> Objects.equals(referenceName, it.getReferenceName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId) {
		return ofNullable(references.get(new ReferenceKey(referenceName, referencedEntityId)));
	}

	@Nonnull
	@Override
	public Set<Locale> getAllLocales() {
		return locales;
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		return attributes.getAttribute(attributeName);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		return attributes.getAttributeArray(attributeName);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		return attributes.getAttributeValue(attributeName);
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		return attributes.getAttribute(attributeName, locale);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		return attributes.getAttributeArray(attributeName, locale);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		return attributes.getAttributeValue(attributeName, locale);
	}

	@Override
	@Nonnull
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return attributes.getAttributeSchema(attributeName);
	}

	@Override
	@Nonnull
	public Set<String> getAttributeNames() {
		return attributes.getAttributeNames();
	}

	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return attributes.getAttributeKeys();
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues() {
		return attributes.getAttributeValues();
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		return attributes.getAttributeValues(attributeName);
	}

	@Nonnull
	@Override
	public Set<Locale> getAttributeLocales() {
		return attributes.getAttributeLocales();
	}

	@Nullable
	public AttributeValue getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return attributes.getAttributeValue(attributeKey);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName) {
		return associatedData.getAssociatedData(associatedDataName);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return associatedData.getAssociatedData(associatedDataName, dtoType, reflectionLookup);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName) {
		return associatedData.getAssociatedData(associatedDataName);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName) {
		return associatedData.getAssociatedDataValue(associatedDataName);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return associatedData.getAssociatedData(associatedDataName, locale);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return associatedData.getAssociatedData(associatedDataName, locale, dtoType, reflectionLookup);
	}

	@Nullable
	@Override
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return associatedData.getAssociatedData(associatedDataName, locale);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return associatedData.getAssociatedDataValue(associatedDataName, locale)
			.or(() -> associatedData.getAssociatedDataValue(associatedDataName));
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return associatedData.getAssociatedDataSchema(associatedDataName);
	}

	@Nonnull
	@Override
	public Set<String> getAssociatedDataNames() {
		return associatedData.getAssociatedDataNames();
	}

	@Nonnull
	@Override
	public Set<AssociatedDataKey> getAssociatedDataKeys() {
		return associatedData.getAssociatedDataKeys();
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		return associatedData.getAssociatedDataValues();
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		return associatedData.getAssociatedDataValues();
	}

	@Nonnull
	@Override
	public Set<Locale> getAssociatedDataLocales() {
		return associatedData.getAssociatedDataLocales();
	}

	@Nullable
	public AssociatedDataValue getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		return associatedData.getAssociatedDataValue(associatedDataKey);
	}

	@Nullable
	public PriceContract getPrice(@Nonnull PriceKey priceKey) {
		return prices.getPrice(priceKey);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return prices.getPrice(priceId, priceList, currency);
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSale() throws ContextMissingException {
		throw new ContextMissingException();
	}

	@Nonnull
	@Override
	public Optional<PriceContract> getPriceForSaleIfAvailable() {
		return empty();
	}

	@Nonnull
	@Override
	public List<PriceContract> getAllPricesForSale() {
		return getAllPricesForSale(null, null, (String) null);
	}

	@Override
	public boolean hasPriceInInterval(@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode queryPriceMode) throws ContextMissingException {
		throw new ContextMissingException();
	}

	@Nonnull
	@Override
	public Collection<PriceContract> getPrices() {
		return prices.getPrices();
	}

	@Nonnull
	@Override
	public PriceInnerRecordHandling getPriceInnerRecordHandling() {
		return prices.getPriceInnerRecordHandling();
	}

	@Override
	public int getPricesVersion() {
		return prices.getPricesVersion();
	}

	@Nullable
	public ReferenceContract getReference(@Nonnull ReferenceKey referenceKey) {
		return references.get(referenceKey);
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


	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + version;
		result = 31 * result + type.hashCode();
		result = 31 * result + (primaryKey == null ? 0 : primaryKey.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Entity entity = (Entity) o;
		return version == entity.version && type.equals(entity.type) && Objects.equals(primaryKey, entity.primaryKey);
	}

	@Override
	public String toString() {
		return describe();
	}

}
