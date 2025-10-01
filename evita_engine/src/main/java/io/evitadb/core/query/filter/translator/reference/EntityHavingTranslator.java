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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.reference.ReferenceOwnerTranslatingFormula;
import io.evitadb.core.query.algebra.reference.ReferencedEntityIndexPrimaryKeyTranslatingFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.sort.entity.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.entity.EntityNestedQueryComparator.EntityPropertyWithScopes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link io.evitadb.api.query.filter.EntityHaving} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityHavingTranslator implements FilteringConstraintTranslator<EntityHaving>, SelfTraversingTranslator {

	/**
	 * Plans and constructs a nested query by for the provided target entity type and filter constraint. The formula
	 * is cached and computed only once to avoid redundant computation. When the target entity doesn't have a global
	 * index, an empty formula is returned (since no entities are present there).
	 *
	 * @param targetEntityType         The type of the target entity for which the nested query is being planned.
	 * @param filter                   The filter constraint that applies the necessary filtering logic.
	 * @param filterByVisitor          The visitor object used for traversing and processing filter constraints.
	 * @param taskDescriptionSupplier  A supplier that provides a task description used in exception messages
	 *                                 and logging.
	 * @return A GlobalIndexAndFormula object containing the global entity index and
	 *                                 filter formula resulting from planning the nested query.
	 */
	@Nonnull
	private static List<GlobalIndexAndFormula> planNestedQuery(
		@Nonnull String targetEntityType,
		@Nonnull FilterConstraint filter,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Supplier<String> taskDescriptionSupplier
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		final EntityCollection targetEntityCollection = filterByVisitor.getEntityCollectionOrThrowException(targetEntityType, taskDescriptionSupplier);
		final List<GlobalEntityIndex> globalIndexes = processingScope.getScopes()
			.stream()
			.map(scope -> targetEntityCollection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope)))
			.filter(Objects::nonNull)
			.map(GlobalEntityIndex.class::cast)
			.toList();
		if (globalIndexes.isEmpty()) {
			return List.of(new GlobalIndexAndFormula(null, EmptyFormula.INSTANCE));
		} else {
			final Function<FilterConstraint, FilterConstraint> enricher = processingScope.getNestedQueryFormulaEnricher();
			final FilterConstraint enrichedConstraint = enricher.apply(filter);
			final FilterBy combinedFilterBy = enrichedConstraint instanceof FilterBy fb ? fb : new FilterBy(enrichedConstraint);
			final Optional<EntityNestedQueryComparator> entityNestedQueryComparator = ofNullable(processingScope.getEntityNestedQueryComparator());

			return globalIndexes
				.stream()
				.map(globalIndex -> new GlobalIndexAndFormula(
						globalIndex,
						filterByVisitor.computeOnlyOnce(
							Collections.singletonList(globalIndex),
							combinedFilterBy,
							() -> {
								final QueryPlanningContext nestedQueryContext = entityNestedQueryComparator
									.map(it -> {
										final Optional<EntityPropertyWithScopes> orderBy = ofNullable(it.getOrderBy());
										return targetEntityCollection.createQueryContext(
											filterByVisitor.getQueryContext(),
											filterByVisitor.getEvitaRequest().deriveCopyWith(
												targetEntityType,
												combinedFilterBy,
												orderBy.map(EntityPropertyWithScopes::createStandaloneOrderBy).orElse(null),
												it.getLocale(),
												orderBy.map(EntityPropertyWithScopes::scopes).orElseGet(() -> EnumSet.of(globalIndex.getIndexKey().scope()))
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
												EnumSet.of(globalIndex.getIndexKey().scope())
											),
											filterByVisitor.getEvitaSession()
										)
									);

								return QueryPlanner.planNestedQuery(nestedQueryContext, taskDescriptionSupplier)
									.getFilter();
							}
						)
					)
				).toList();
		}
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull EntityHaving entityHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final EntitySchemaContract entitySchema = Objects.requireNonNull(filterByVisitor.getProcessingScope().getEntitySchema());
		final ReferenceSchemaContract referenceSchema = filterByVisitor
			.getReferenceSchema()
			.orElseThrow(() -> new EvitaInvalidUsageException(
				             "Filtering constraint `" + entityHaving + "` needs to be placed within `ReferenceHaving` " +
					             "parent constraint that allows to resolve the entity `" +
					             entitySchema.getName() + "` referenced entity type."
			             )
			);
		Assert.isTrue(
			referenceSchema.isReferencedEntityTypeManaged(),
			() -> "Filtering constraint `" + entityHaving + "` targets entity " +
				"`" + referenceSchema.getReferencedEntityType() + "` that is not managed by evitaDB."
		);
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final FilterConstraint filterConstraint = entityHaving.getChild();
		if (filterConstraint != null) {
			@SuppressWarnings("unchecked") final ProcessingScope<EntityIndex> processingScope = (ProcessingScope<EntityIndex>) filterByVisitor.getProcessingScope();
			final Supplier<String> nestedQueryDescription = () -> "filtering reference `" + referenceSchema.getName() +
				"` by entity `" + referencedEntityType + "` having: " + filterConstraint;

			return FormulaFactory.or(
				planNestedQuery(
					referencedEntityType,
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
									.map(ReferencedTypeEntityIndex.class::cast)
									.map(
										it -> new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
										     referenceSchema,
										     filterByVisitor::getGlobalEntityIndexIfExists,
										     it,
										     nestedResult.filter(),
										     processingScope.getScopes()
									     )
									)
									.toArray(Formula[]::new)
							);
						} else {
							return filterByVisitor.computeOnlyOnce(
								processingScope.getIndexes(),
								filterConstraint,
								() -> {
									if (nestedResult.globalIndex() == null) {
										return EmptyFormula.INSTANCE;
									}
									final ReferenceOwnerTranslatingFormula outputFormula = new ReferenceOwnerTranslatingFormula(
										nestedResult.globalIndex(),
										nestedResult.filter(),
										it -> {
											// leave the return here, so that we can easily debug it
											final RoaringBitmap combinedResult = RoaringBitmap.or(
												filterByVisitor.getReferencedEntityIndexes(entitySchema, referenceSchema, it)
													.map(EntityIndex::getAllPrimaryKeys)
													.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
													.toArray(RoaringBitmap[]::new)
											);
											return combinedResult.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(combinedResult);
										}
									);
									return new DeferredFormula(
										new FormulaWrapper(
											outputFormula,
											(executionContext, formula) -> {
												try {
													executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, nestedQueryDescription);
													return formula.compute();
												} finally {
													executionContext.popStep();
												}
											}
										)
									);
								},
								2L,
								// we need to add exact pointers to the entity schema and reference schema, which play role
								// in the lambda evaluation
								NumberUtils.join(
									System.identityHashCode(entitySchema),
									System.identityHashCode(referenceSchema)
								),
								nestedResult.globalIndex() == null ? 0L : nestedResult.globalIndex().getPrimaryKey()
							);
						}
					})
					.toArray(Formula[]::new)
			);
		}
		return EmptyFormula.INSTANCE;
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
	private record GlobalIndexAndFormula(
		@Nullable GlobalEntityIndex globalIndex,
		@Nonnull Formula filter
	) {

	}

}
