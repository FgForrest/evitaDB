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

package io.evitadb.core.query.indexSelection;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinRootTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes.EligibilityObstacle;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This visitor examines {@link Query#getFilterBy()} query and tries to construct multiple {@link TargetIndexes}
 * alternative that can be used to fully interpret the filtering query. These alternative index sets will compete
 * one with another to produce filtering query with minimal execution costs.
 *
 * Currently, the logic is quite stupid - it searches the filter for all constraints within AND relation and when
 * relation or hierarchy query is encountered, it adds specific {@link EntityIndexType#REFERENCED_ENTITY} that contains
 * limited subset of the entities related to that placement/relation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class IndexSelectionVisitor implements ConstraintVisitor {
	private final QueryPlanningContext queryContext;
	@Getter private final List<TargetIndexes<? extends Index<?>>> targetIndexes = new LinkedList<>();
	private final int mainIndexCardinality;
	private FilterByVisitor filterByVisitor;

	public IndexSelectionVisitor(@Nonnull QueryPlanningContext queryContext) {
		this.queryContext = queryContext;
		final Set<Scope> allowedScopes = this.queryContext.getScopes();
		if (this.queryContext.hasEntityGlobalIndex()) {
			final List<GlobalEntityIndex> indexes = Arrays.stream(Scope.values())
				.filter(allowedScopes::contains)
				.map(it -> this.queryContext.getIndex(new EntityIndexKey(EntityIndexType.GLOBAL, it)))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.map(GlobalEntityIndex.class::cast)
				.toList();
			this.targetIndexes.add(
				new TargetIndexes<>(
					indexes.stream().map(it -> it.getIndexKey().toString()).collect(Collectors.joining(", ")),
					GlobalEntityIndex.class,
					indexes
				)
			);
			this.mainIndexCardinality = indexes
				.stream()
				.map(GlobalEntityIndex::getAllPrimaryKeys)
				.mapToInt(Bitmap::size)
				.sum();
		} else {
			final List<CatalogIndex> indexes = Arrays.stream(Scope.values())
				.filter(allowedScopes::contains)
				.map(it -> this.queryContext.getIndex(new CatalogIndexKey(it)))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.map(CatalogIndex.class::cast)
				.toList();
			if (!indexes.isEmpty()) {
				this.targetIndexes.add(
					new TargetIndexes<>(
						indexes.stream().map(it -> it.getIndexKey().toString()).collect(Collectors.joining(", ")),
						CatalogIndex.class,
						indexes
					)
				);
			}
			this.mainIndexCardinality = Integer.MIN_VALUE;
		}
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final FilterConstraint filterConstraint = (FilterConstraint) constraint;

		if (filterConstraint instanceof And || filterConstraint instanceof FilterBy) {
			// if query is AND query we may traverse further
			@SuppressWarnings("unchecked") final FilterConstraint[] subConstraints = ((ConstraintContainer<FilterConstraint>) filterConstraint).getChildren();
			for (FilterConstraint subConstraint : subConstraints) {
				subConstraint.accept(this);
			}
		} else if (filterConstraint instanceof FilterInScope inScope) {
			getFilterByVisitor().getProcessingScope().doWithScope(
				EnumSet.of(inScope.getScope()),
				() -> {
					for (FilterConstraint subConstraint : inScope.getChildren()) {
						subConstraint.accept(this);
					}
					return null;
				}
			);
		} else if (filterConstraint instanceof HierarchyFilterConstraint hierarchyFilterConstraint) {
			// if query is hierarchy filtering query targeting different entity
			addHierarchyIndexOption(hierarchyFilterConstraint);
		} else if (filterConstraint instanceof final ReferenceHaving referenceHaving) {
			// if query is hierarchy filtering query targeting different entity
			addReferenceIndexOption(referenceHaving);
			for (FilterConstraint subConstraint : referenceHaving.getChildren()) {
				if (!(subConstraint instanceof EntityHaving)) {
					subConstraint.accept(this);
				}
			}
		}
	}

	/**
	 * Registers {@link TargetIndexes} that represents hierarchy placement. It finds collection of
	 * {@link EntityIndexType#REFERENCED_ENTITY} indexes that contains all relevant data for entities that
	 * are part of the requested tree. This significantly limits the scope that needs to be examined.
	 */
	private void addHierarchyIndexOption(@Nonnull HierarchyFilterConstraint constraint) {
		constraint.getReferenceName().ifPresent(
			referenceName -> {
				final EntitySchema entitySchema = this.queryContext.getSchema();
				final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
				if (referenceSchema.isReferencedEntityTypeManaged()) {
					final FilterByVisitor theFilterByVisitor = getFilterByVisitor();
					final Set<Scope> scopes = theFilterByVisitor.getProcessingScope().getScopes();
					for (Scope scope : scopes) {
						if (referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.NONE) {
							throw new ReferenceNotIndexedException(referenceSchema.getName(), entitySchema, scope);
						}
					}

					final Formula requestedHierarchyNodesFormula;
					if (constraint instanceof final HierarchyWithinRoot hierarchyWithinRoot) {
						requestedHierarchyNodesFormula = HierarchyWithinRootTranslator.createFormulaFromHierarchyIndex(
							hierarchyWithinRoot, theFilterByVisitor
						);
					} else if (constraint instanceof final HierarchyWithin hierarchyWithin) {
						requestedHierarchyNodesFormula = HierarchyWithinTranslator.createFormulaFromHierarchyIndex(
							hierarchyWithin, theFilterByVisitor
						);
					} else {
						//sanity check only
						throw new GenericEvitaInternalError("Should never happen");
					}
					if (requestedHierarchyNodesFormula instanceof EmptyFormula) {
						// if target entity has no global index present, it means that the query cannot be fulfilled
						// we may quickly return empty result
						this.targetIndexes.add(TargetIndexes.EMPTY);
					}
					// locate all hierarchy indexes
					final Bitmap requestedHierarchyNodes = requestedHierarchyNodesFormula.compute();
					final List<ReducedEntityIndex> theTargetIndexes = new ArrayList<>(requestedHierarchyNodes.size() * scopes.size());
					final AtomicInteger cardinalityCounter = new AtomicInteger(0);
					for (Integer hierarchyEntityId : requestedHierarchyNodes) {
						for (Scope scope : scopes) {
							this.queryContext.getIndex(
									new EntityIndexKey(
										EntityIndexType.REFERENCED_ENTITY,
										scope,
										new ReferenceKey(referenceName, hierarchyEntityId)
									)
								)
								.map(ReducedEntityIndex.class::cast)
								.ifPresent(ix -> {
									theTargetIndexes.add(ix);
									cardinalityCounter.addAndGet(ix.getAllPrimaryKeys().size());
								});
						}
					}
					// add indexes as potential target indexes
					this.targetIndexes.add(
						new TargetIndexes<>(
							EntityIndexType.REFERENCED_ENTITY.name() +
								" composed of " + requestedHierarchyNodes.size() + " indexes",
							constraint,
							ReducedEntityIndex.class,
							theTargetIndexes,
							Stream.of(
									allIndexesArePartitioned(scopes, referenceSchema) ? null : EligibilityObstacle.NOT_PARTITIONED_INDEX,
									cardinalityCounter.get() <= this.mainIndexCardinality / 2 ? null : EligibilityObstacle.HIGH_CARDINALITY
								)
								.filter(Objects::nonNull)
								.toArray(EligibilityObstacle[]::new)
						)
					);
				}
			}
		);
	}

	/**
	 * Registers {@link TargetIndexes} that represents hierarchy placement. It finds collection of
	 * {@link EntityIndexType#REFERENCED_ENTITY} indexes that contains all relevant data for entities that
	 * are related to respective entity type and id. This may significantly limit the scope that needs to be examined.
	 */
	private void addReferenceIndexOption(@Nonnull ReferenceHaving constraint) {
		final EntitySchema entitySchema = this.queryContext.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(constraint.getReferenceName());
		final FilterByVisitor theFilterByVisitor = getFilterByVisitor();
		final Set<Scope> scopes = theFilterByVisitor.getProcessingScope().getScopes();
		final List<ReducedEntityIndex> theTargetIndexes = theFilterByVisitor
			.getReferencedRecordEntityIndexes(constraint, scopes);

		if (theTargetIndexes.isEmpty() && !scopes.equals(theFilterByVisitor.getScopes())) {
			// if the scopes were redefined in processing scope (differ from globally allowed scopes)
			// skip this indexing option
		} else {
			// add indexes as potential target indexes
			this.targetIndexes.add(
				new TargetIndexes<>(
					EntityIndexType.REFERENCED_ENTITY.name() +
						" composed of " + theTargetIndexes.size() + " indexes",
					constraint,
					ReducedEntityIndex.class,
					theTargetIndexes,
					Stream.of(
							allIndexesArePartitioned(scopes, referenceSchema) ? null : EligibilityObstacle.NOT_PARTITIONED_INDEX,
							theTargetIndexes.stream().map(ReducedEntityIndex::getAllPrimaryKeys).mapToInt(Bitmap::size).sum() <= this.mainIndexCardinality / 2 ? null : EligibilityObstacle.HIGH_CARDINALITY
						)
						.filter(Objects::nonNull)
						.toArray(EligibilityObstacle[]::new)
				)
			);
		}
	}

	/**
	 * Checks if all indexes defined by the provided scopes are partitioned according to a specific reference index type
	 * in the given reference schema.
	 *
	 * @param scopes a set of {@link Scope} objects representing the indexes to be checked
	 * @param referenceSchema the {@link ReferenceSchemaContract} containing the reference index type information
	 * @return {@code true} if all indexes within the given scopes are partitioned for filtering and partitioning,
	 *         {@code false} otherwise
	 */
	private static boolean allIndexesArePartitioned(@Nonnull Set<Scope> scopes, @Nonnull ReferenceSchemaContract referenceSchema) {
		return scopes.stream()
			.allMatch(scope -> referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);
	}

	/**
	 * Retrieves an instance of the {@link FilterByVisitor}. If not already initialized, this method
	 * creates a new instance of {@link FilterByVisitor} using the current {@code queryContext},
	 * an empty collection of referenced attributes, and an empty {@link TargetIndexes}.
	 *
	 * @return the initialized {@link FilterByVisitor} instance
	 */
	@Nonnull
	private FilterByVisitor getFilterByVisitor() {
		if (this.filterByVisitor == null) {
			// create a lightweight visitor that can evaluate referenced attributes index location only
			this.filterByVisitor = new FilterByVisitor(
				this.queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY
			);
		}
		return this.filterByVisitor;
	}

}
