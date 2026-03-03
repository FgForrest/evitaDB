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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.SeparateEntityScopeContainer;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.reference.ReferenceOwnerTranslatingFormula;
import io.evitadb.core.query.algebra.reference.ReferencedEntityIndexPrimaryKeyTranslatingFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator.EntityPropertyWithScopes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.NumberUtils;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Shared utility methods for {@link EntityHavingTranslator} and {@link GroupHavingTranslator}.
 * Both translators share identical nested query planning logic and a very similar formula
 * construction flow, differing only in:
 *
 * - the target entity type (referenced entity vs. referenced group entity),
 * - the managed-type check,
 * - the index lookup method for translating matched PKs back to owner entity PKs.
 *
 * This helper extracts the shared logic to avoid duplication.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class HavingTranslatorHelper {

	/**
	 * A functional interface for looking up reduced entity indexes given the entity schema,
	 * the reference schema, and a primary key of the target entity (either referenced entity
	 * or group entity).
	 */
	@FunctionalInterface
	interface ReducedIndexLookup {

		/**
		 * Returns a stream of reduced entity indexes for the given target entity primary key.
		 *
		 * @param entitySchema the schema of the queried entity
		 * @param referenceSchema the schema of the reference
		 * @param targetEntityPk the primary key of the target entity (referenced or group)
		 * @return stream of reduced entity indexes
		 */
		@Nonnull
		Stream<? extends EntityIndex> lookup(
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull ReferenceSchemaContract referenceSchema,
			int targetEntityPk
		);
	}

	/**
	 * Represents a combination of a global entity index and a corresponding filter formula.
	 * This record is used within the context of query planning and execution to encapsulate
	 * the necessary components for filtering entities based on specific criteria.
	 *
	 * @param globalIndex The global entity index that contains a complete set of indexed data,
	 *                    including their bodies, for all entities in the collection.
	 * @param filter      The formula that represents the filter constraints applied to the entities
	 *                    in the global index.
	 */
	record GlobalIndexAndFormula(
		@Nullable GlobalEntityIndex globalIndex,
		@Nonnull Formula filter
	) {
	}

	/**
	 * Plans and constructs a nested query for the provided target entity type and filter constraint.
	 * The formula is cached and computed only once to avoid redundant computation. When the target
	 * entity doesn't have a global index, an empty formula is returned (since no entities are present
	 * there).
	 *
	 * @param targetEntityType         the type of the target entity for which the nested query is being planned
	 * @param filter                   the filter constraint that applies the necessary filtering logic
	 * @param filterByVisitor          the visitor object used for traversing and processing filter constraints
	 * @param taskDescriptionSupplier  a supplier that provides a task description used in exception messages
	 *                                 and logging
	 * @return list of {@link GlobalIndexAndFormula} objects containing global entity indexes and
	 *         filter formulas resulting from planning the nested query
	 */
	@Nonnull
	static List<GlobalIndexAndFormula> planNestedQuery(
		@Nonnull String targetEntityType,
		@Nonnull FilterConstraint filter,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Supplier<String> taskDescriptionSupplier
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		final EntityCollection targetEntityCollection = filterByVisitor.getEntityCollectionOrThrowException(
			targetEntityType, taskDescriptionSupplier
		);
		final List<GlobalEntityIndex> globalIndexes = processingScope.getScopes()
			.stream()
			.map(
				scope -> targetEntityCollection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.GLOBAL, scope)
				)
			)
			.filter(Objects::nonNull)
			.map(GlobalEntityIndex.class::cast)
			.toList();

		if (globalIndexes.isEmpty()) {
			return List.of(new GlobalIndexAndFormula(null, EmptyFormula.INSTANCE));
		} else {
			final Function<FilterConstraint, FilterConstraint> enricher =
				processingScope.getNestedQueryFormulaEnricher();
			final FilterConstraint enrichedConstraint = enricher.apply(filter);
			final FilterBy combinedFilterBy = enrichedConstraint instanceof FilterBy fb ?
				fb : new FilterBy(enrichedConstraint);
			final Optional<EntityNestedQueryComparator> entityNestedQueryComparator =
				ofNullable(processingScope.getEntityNestedQueryComparator());

			return globalIndexes
				.stream()
				.map(globalIndex -> new GlobalIndexAndFormula(
						globalIndex,
						filterByVisitor.computeOnlyOnce(
							Collections.singletonList(globalIndex),
							combinedFilterBy,
							() -> {
								final Set<Scope> targetedScopes = EnumSet.noneOf(Scope.class);
								Collections.addAll(
									targetedScopes,
									ofNullable(
										QueryUtils.findConstraint(
											combinedFilterBy, EntityScope.class,
											SeparateEntityScopeContainer.class
										)
									).map(it -> it.getScope().toArray(Scope[]::new))
										.orElseGet(() -> new Scope[]{
											globalIndex.getIndexKey().scope()
										})
								);
								final QueryPlanningContext nestedQueryContext = entityNestedQueryComparator
									.map(it -> {
										final Optional<EntityPropertyWithScopes> orderBy =
											ofNullable(it.getOrderBy());
										orderBy.ifPresent(
											ob -> targetedScopes.addAll(ob.scopes())
										);
										return targetEntityCollection.createQueryContext(
											filterByVisitor.getQueryContext(),
											filterByVisitor.getEvitaRequest().deriveCopyWith(
												targetEntityType,
												combinedFilterBy,
												orderBy
													.map(EntityPropertyWithScopes::createStandaloneOrderBy)
													.orElse(null),
												it.getLocale(),
												targetedScopes
											),
											filterByVisitor.getEvitaSession()
										);
									})
									.orElseGet(
										() -> targetEntityCollection.createQueryContext(
											filterByVisitor.getQueryContext(),
											filterByVisitor.getEvitaRequest().deriveCopyWith(
												targetEntityType,
												combinedFilterBy,
												null,
												null,
												targetedScopes
											),
											filterByVisitor.getEvitaSession()
										)
									);

								return QueryPlanner.planNestedQuery(
									nestedQueryContext, taskDescriptionSupplier
								).getFilter();
							}
						)
					)
				).toList();
		}
	}

	/**
	 * Translates a having constraint (either {@link io.evitadb.api.query.filter.EntityHaving} or
	 * {@link io.evitadb.api.query.filter.GroupHaving}) into a formula that computes owner entity
	 * primary keys matching the constraint.
	 *
	 * @param filterConstraint          the child filter constraint from the having container
	 * @param filterByVisitor           the visitor for traversing filter constraints
	 * @param entitySchema              the schema of the queried entity
	 * @param referenceSchema           the reference schema
	 * @param targetEntityType          the type of the target entity (referenced or group)
	 * @param isTargetManaged           whether the target entity type is managed by evitaDB
	 * @param reducedIndexLookup        the function to look up reduced entity indexes for a target PK
	 * @param nestedQueryDescription    a supplier for the description of this nested query
	 * @return a formula computing matching owner entity primary keys
	 */
	@Nonnull
	static Formula translateHavingConstraint(
		@Nonnull FilterConstraint filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String targetEntityType,
		boolean isTargetManaged,
		@Nonnull ReducedIndexLookup reducedIndexLookup,
		@Nonnull Supplier<String> nestedQueryDescription
	) {
		@SuppressWarnings("unchecked")
		final ProcessingScope<EntityIndex> processingScope =
			(ProcessingScope<EntityIndex>) filterByVisitor.getProcessingScope();

		return FormulaFactory.or(
			planNestedQuery(
				targetEntityType,
				filterConstraint,
				filterByVisitor,
				nestedQueryDescription
			)
				.stream()
				.map(nestedResult -> {
					if (ReferencedTypeEntityIndex.class.isAssignableFrom(processingScope.getIndexType())) {
						return FormulaFactory.or(
							processingScope
								.getIndexStream()
								.filter(it -> processingScope.getScopes().contains(it.getIndexKey().scope()))
								.map(ReferencedTypeEntityIndex.class::cast)
								.map(
									it -> new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
										referenceSchema,
										targetEntityType,
										isTargetManaged,
										filterByVisitor::getGlobalEntityIndexIfExists,
										it,
										nestedResult.filter(),
										processingScope.getScopes(),
										processingScope.getReferencedEntityExpansionFunction()
									)
								)
								.toArray(Formula[]::new)
						);
					} else {
						if (nestedResult.globalIndex() == null) {
							return EmptyFormula.INSTANCE;
						}
						return filterByVisitor.computeOnlyOnce(
							List.of(nestedResult.globalIndex()),
							filterConstraint,
							() -> {
								final ReferenceOwnerTranslatingFormula outputFormula =
									new ReferenceOwnerTranslatingFormula(
										nestedResult.globalIndex(),
										nestedResult.filter(),
										it -> {
											final RoaringBitmap combinedResult = RoaringBitmap.or(
												reducedIndexLookup.lookup(entitySchema, referenceSchema, it)
													.map(EntityIndex::getAllPrimaryKeys)
													.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
													.toArray(RoaringBitmap[]::new)
											);
											return combinedResult.isEmpty() ?
												EmptyBitmap.INSTANCE : new BaseBitmap(combinedResult);
										}
									);
								return new DeferredFormula(
									new FormulaWrapper(
										outputFormula,
										(executionContext, formula) -> {
											try {
												executionContext.pushStep(
													QueryPhase.EXECUTION_FILTER_NESTED_QUERY,
													nestedQueryDescription
												);
												return formula.compute();
											} finally {
												executionContext.popStep();
											}
										}
									)
								);
							},
							2L,
							// we need to add exact pointers to the entity schema and reference schema,
							// which play role in the lambda evaluation
							NumberUtils.join(
								System.identityHashCode(entitySchema),
								System.identityHashCode(referenceSchema)
							),
							nestedResult.globalIndex().getPrimaryKey()
						);
					}
				})
				.toArray(Formula[]::new)
		);
	}

	private HavingTranslatorHelper() {
		// utility class
	}

}
