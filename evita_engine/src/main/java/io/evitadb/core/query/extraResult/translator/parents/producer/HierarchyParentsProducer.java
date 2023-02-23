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

package io.evitadb.core.query.extraResult.translator.parents.producer;

import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyParentsOfReference;
import io.evitadb.api.query.require.HierarchyParentsOfSelf;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.dataType.DataChunk;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link HierarchyParentsProducer} creates {@link HierarchyParents} DTO instance and does the heavy lifting to compute all information
 * necessary. The producer aggregates {@link ReferenceNameParentsProducer} for each {@link HierarchyParentsOfSelf}
 * or {@link HierarchyParentsOfReference} requirement and combines them into the single result.
 *
 * For each entity returned in {@link DataChunk} (i.e. {@link EvitaResponse}) it consults
 * {@link HierarchyIndex} and retrieves all parents in respective hierarchy and adds them
 * to the result DTO. If entity bodies are requested it also fetches appropriate {@link SealedEntity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyParentsProducer implements ExtraResultProducer {
	/**
	 * Reference to the query context that allows to access entity bodies.
	 */
	@Nonnull private final QueryContext queryContext;
	/**
	 * Function that returns array of primary keys of entities of specified `referenceName` the entity with `primaryKey`
	 * references.
	 */
	private final BiFunction<Integer, String, int[]> referenceFetcher;
	/**
	 * Predicate returning TRUE in case the {@link io.evitadb.api.requestResponse.EvitaRequest} contains requirement for {@link DataChunk}
	 * to fetch references of specified `referenceName`. I.e. we can relly on {@link SealedEntity#getReferences(String)}
	 * returning data needed for parents computation.
	 */
	private final Predicate<String> referenceFetchedPredicate;
	/**
	 * Lambda that compute {@link HierarchyParents} DTO for the queried entity itself. Lambda corresponds
	 * to {@link HierarchyParentsOfSelf} requirement
	 */
	private SelfParentsProducer selfParentsProducer;
	/**
	 * List of lambdas that each compute part of the {@link HierarchyParents} DTO. Each lambda corresponds to single
	 * {@link HierarchyParentsOfReference} requirement.
	 */
	private final List<ReferenceNameParentsProducer> producersByType = new LinkedList<>();

	public HierarchyParentsProducer(
		@Nonnull QueryContext queryContext,
		@Nonnull Predicate<String> referenceFetchedPredicate,
		boolean includingSelf,
		@Nonnull BiFunction<Integer, String, int[]> referenceFetcher,
		@Nonnull EntityIndex globalIndex,
		@Nullable EntityFetch entityRequirement
	) {
		this.queryContext = queryContext;
		this.referenceFetcher = referenceFetcher;
		this.referenceFetchedPredicate = referenceFetchedPredicate;
		this.selfParentsProducer = new SelfParentsProducer(
			queryContext, includingSelf, globalIndex, entityRequirement
		);
	}

	public HierarchyParentsProducer(
		@Nonnull QueryContext queryContext,
		@Nonnull Predicate<String> referenceFetchedPredicate,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean includingSelf,
		@Nonnull BiFunction<Integer, String, int[]> referenceFetcher,
		@Nonnull EntityIndex globalIndex,
		@Nullable EntityFetch entityRequirement
	) {
		this.queryContext = queryContext;
		this.referenceFetcher = referenceFetcher;
		this.referenceFetchedPredicate = referenceFetchedPredicate;
		this.producersByType.add(
			new ReferenceNameParentsProducer(
				queryContext, referenceSchema,
				referenceFetchedPredicate.test(referenceSchema.getName()),
				includingSelf,
				referenceFetcher,
				globalIndex,
				entityRequirement
			)
		);
	}

	@Nullable
	@Override
	 public <T extends Serializable> EvitaResponseExtraResult fabricate(@Nonnull List<T> entities) {
		return new HierarchyParents(
			Optional.ofNullable(selfParentsProducer)
				.map(it -> it.compute(entities))
				.orElse(null),
			// call each producer lambda to make up the result
			producersByType
				.stream()
				.collect(
					Collectors.toMap(
						it -> it.getReferenceSchema().getName(),
						it -> it.compute(entities)
					)
				)
		);
	}

	/**
	 * Registers a lambda that will compute parents for requested `referenceSchema`, loading the results according
	 * to `requirements` array. Lambda will use {@link HierarchyIndex} of passed `globalIndex`.
	 */
	public void addRequestedParents(
		@Nonnull EntityIndex globalIndex,
		@Nullable EntityFetch entityRequirement
	) {
		this.selfParentsProducer = new SelfParentsProducer(
			queryContext,
			false,
			globalIndex,
			entityRequirement
		);
	}

	/**
	 * Registers a lambda that will compute parents for requested `referenceSchema`, loading the results according
	 * to `requirements` array. Lambda will use {@link HierarchyIndex} of passed `globalIndex`. Computed parents will
	 * also include the referenced entity.
	 */
	public void addRequestedParentsIncludingSelf(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex globalIndex,
		@Nullable EntityFetch entityRequirement
	) {
		this.producersByType.add(
			new ReferenceNameParentsProducer(
				queryContext, referenceSchema,
				referenceFetchedPredicate.test(referenceSchema.getName()),
				true,
				referenceFetcher,
				globalIndex,
				entityRequirement
			)
		);
	}

	/**
	 * The class represents a computational lambda for single {@link ParentsByReference} sub-object of overall {@link HierarchyParents}
	 * container.
	 */
	private static class ReferenceNameParentsProducer {
		/**
		 * Contains {@link ReferenceSchema} of the referenced entity the parents are computed for.
		 */
		@Getter private final ReferenceSchemaContract referenceSchema;
		/**
		 * Reference to the query context that allows to access entity bodies.
		 */
		@Nonnull private final QueryContext queryContext;
		/**
		 * Contains TRUE in case the {@link io.evitadb.api.requestResponse.EvitaRequest} contains requirement for {@link DataChunk}
		 * to fetch references of specified `referenceName`. I.e. we can relly on {@link SealedEntity#getReferences(String)}
		 * returning data needed for parents computation.
		 */
		private final boolean referenceNameReferenceFetched;
		/**
		 * Function that returns array of primary keys of entities of specified `referenceName` the entity with `primaryKey`
		 * references.
		 */
		private final BiFunction<Integer, String, int[]> referenceFetcher;
		/**
		 * Contains internal function that allows to unify and share logic for gathering the output data.
		 */
		private final Collector<Integer, ?, Map<Integer, EntityClassifier[]>> parentEntityCollector;

		public ReferenceNameParentsProducer(
			@Nonnull QueryContext queryContext,
			@Nonnull ReferenceSchemaContract referenceSchema,
			boolean referenceNameReferenceFetched,
			boolean includingSelf,
			@Nonnull BiFunction<Integer, String, int[]> referenceFetcher,
			@Nonnull EntityIndex globalIndex,
			@Nullable EntityFetch entityRequirement
		) {
			this.queryContext = queryContext;
			this.referenceSchema = referenceSchema;
			this.referenceNameReferenceFetched = referenceNameReferenceFetched;
			this.referenceFetcher = referenceFetcher;
			final boolean requirementsPresent = entityRequirement != null;
			final String referencedEntityType = referenceSchema.getReferencedEntityType();
			this.parentEntityCollector = Collectors.toMap(
				Function.identity(),
				refEntityId -> {
					// return parents only or parents including the refEntityId
					final Integer[] parents = includingSelf ?
						globalIndex.listHierarchyNodesFromRootToTheNodeIncludingSelf(refEntityId) :
						globalIndex.listHierarchyNodesFromRootToTheNode(refEntityId);
					// return primary keys only (Integer) or full entities of requested size (SealedEntity)
					return requirementsPresent ?
						Arrays.stream(parents)
							.map(it -> this.queryContext.fetchEntity(referencedEntityType, it, entityRequirement).orElse(null))
							.toArray(EntityClassifier[]::new) :
						Arrays.stream(parents)
							.map(it -> new EntityReference(referencedEntityType, it))
							.toArray(EntityClassifier[]::new);
				}
			);
		}

		/**
		 * Computes {@link ParentsByReference} DTO - expected to be called only once.
		 */
		@Nonnull
		public <S extends Serializable> ParentsByReference compute(@Nonnull List<S> entities) {
			if (entities.isEmpty()) {
				// if data chunk contains no entities - our result will be empty as well
				return new ParentsByReference(referenceSchema.getName(), Collections.emptyMap());
			} else if (referenceNameReferenceFetched && entities.get(0) instanceof EntityContract) {
				// if data chunk contains EntityContract entities with information of requested referenced entity
				// take advantage of already preloaded data
				return new ParentsByReference(
					referenceSchema.getName(),
					entities
						.stream()
						.map(EntityContract.class::cast)
						.collect(
							Collectors.toMap(
								EntityContract::getPrimaryKey,
								sealedEntity -> sealedEntity
									.getReferences(referenceSchema.getName())
									.stream()
									.map(ReferenceContract::getReferencedPrimaryKey)
									// and use the shared collector logic
									.collect(parentEntityCollector)
							)
						)
				);
			} else if (entities.get(0) instanceof EntityContract) {
				// if data chunk contains EntityContract entities but these lack information of requested referenced
				// entity - use the translator function to fetch requested referenced container from MemTable lazily
				return new ParentsByReference(
					referenceSchema.getName(),
					entities
						.stream()
						.map(EntityContract.class::cast)
						.collect(
							Collectors.toMap(
								EntityContract::getPrimaryKey,
								sealedEntity -> Arrays.stream(referenceFetcher.apply(sealedEntity.getPrimaryKey(), referenceSchema.getName()))
									.boxed()
									// and use the shared collector logic
									.collect(parentEntityCollector)
							)
						)
				);
			} else {
				// if data chunk contains only primary keys of entities
				// use the translator function to fetch requested referenced container from MemTable lazily
				return new ParentsByReference(
					referenceSchema.getName(),
					entities
						.stream()
						.map(EntityReference.class::cast)
						.map(EntityReference::getPrimaryKey)
						.collect(
							Collectors.toMap(
								Function.identity(),
								entityPrimaryKey -> Arrays.stream(referenceFetcher.apply(entityPrimaryKey, referenceSchema.getName()))
									.boxed()
									// and use the shared collector logic
									.collect(parentEntityCollector)
							)
						)
				);
			}
		}
	}

	/**
	 * The class represents a computational lambda for single {@link ParentsByReference} sub-object of overall {@link HierarchyParents}
	 * container.
	 */
	private static class SelfParentsProducer {
		/**
		 * Reference to the query context that allows to access entity bodies.
		 */
		@Nonnull private final QueryContext queryContext;
		/**
		 * Contains internal function that allows to unify and share logic for gathering the output data.
		 */
		private final Collector<Integer, ?, Map<Integer, EntityClassifier[]>> parentEntityCollector;

		public SelfParentsProducer(
			@Nonnull QueryContext queryContext,
			boolean includingSelf,
			@Nonnull EntityIndex globalIndex,
			@Nullable EntityFetch entityRequirement
		) {
			this.queryContext = queryContext;
			final boolean requirementsPresent = entityRequirement != null;
			this.parentEntityCollector = Collectors.toMap(
				Function.identity(),
				refEntityId -> {
					// return parents only or parents including the refEntityId
					final Integer[] parents = includingSelf ?
						globalIndex.listHierarchyNodesFromRootToTheNodeIncludingSelf(refEntityId) :
						globalIndex.listHierarchyNodesFromRootToTheNode(refEntityId);
					// return primary keys only (Integer) or full entities of requested size (SealedEntity)
					return requirementsPresent ?
						Arrays.stream(parents)
							.map(it -> this.queryContext.fetchEntity(queryContext.getSchema().getName(), it, entityRequirement).orElse(null))
							.toArray(EntityClassifier[]::new) :
						Arrays.stream(parents)
							.map(it -> new EntityReference(queryContext.getSchema().getName(), it))
							.toArray(EntityClassifier[]::new);
				}
			);
		}

		/**
		 * Computes {@link ParentsByReference} DTO - expected to be called only once.
		 */
		@Nonnull
		public <S extends Serializable> ParentsByReference compute(@Nonnull List<S> entities) {
			if (entities.isEmpty()) {
				// if data chunk contains no entities - our result will be empty as well
				return new ParentsByReference(Collections.emptyMap());
			} else if (entities.get(0) instanceof EntityContract) {
				// if data chunk contains EntityContract entities but these lack information of requested referenced
				// entity - use the translator function to fetch requested referenced container from MemTable lazily
				return new ParentsByReference(
					entities
						.stream()
						.map(EntityContract.class::cast)
						.collect(
							Collectors.toMap(
								EntityContract::getPrimaryKey,
								sealedEntity -> Stream.of(sealedEntity.getPrimaryKey())
									// and use the shared collector logic
									.collect(parentEntityCollector)
							)
						)
				);
			} else {
				// if data chunk contains only primary keys of entities
				// use the translator function to fetch requested referenced container from MemTable lazily
				return new ParentsByReference(
					entities
						.stream()
						.map(EntityReference.class::cast)
						.map(EntityReference::getPrimaryKey)
						.collect(
							Collectors.toMap(
								Function.identity(),
								entityPrimaryKey -> Stream.of(entityPrimaryKey)
									// and use the shared collector logic
									.collect(parentEntityCollector)
							)
						)
				);
			}
		}
	}

}
