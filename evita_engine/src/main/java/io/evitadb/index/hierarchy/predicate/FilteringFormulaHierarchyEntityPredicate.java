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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure.CalculationContext;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate.SelfTraversingPredicate;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The predicate evaluates the nested query filter function to get the {@link Bitmap} of all hierarchy entity primary
 * keys that match the passed filtering constraint. It uses the result bitmap to resolve to decide output of the
 * predicate test method - for each key matching the computed result returns true, otherwise false.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class FilteringFormulaHierarchyEntityPredicate implements HierarchyFilteringPredicate, SelfTraversingPredicate {
	/**
	 * Identification of the root node this predicate relates to.
	 */
	@Nullable private final Integer parent;
	/**
	 * The result that should be returned for parent node (constant).
	 */
	private final boolean parentResult;
	/**
	 * Field contains the original filter by constraint the {@link #filteringFormula} was created by.
	 */
	@Getter @Nonnull private final FilterBy filterBy;
	/**
	 * Formula computes id of all hierarchical entities that match input filter by constraint.
	 */
	@Getter @Nonnull private final Formula filteringFormula;
	/**
	 * Signalizes that the {@link HierarchyTraversalPredicate} reached a node that was marked as "stop" node and its
	 * children should be tested by a predicate logic as "false".
	 */
	private boolean stopNodeEncountered;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private Long hash;

	/**
	 * This constructor should be used from filtering translators that need to take the attributes on references
	 * into an account.
	 *
	 * @param parent          identification of the root node this predicate relates to
	 * @param parentResult    the result that should be returned for parent node (constant)
	 * @param queryContext    current query context
	 * @param filterBy        the filtering that targets reference attributes but may also contain {@link EntityHaving}
	 *                        constraint (but only when reference schema is not null and the entity targets different entity hierarchy)
	 * @param referenceSchema the optional reference schema if the entity targets itself hierarchy tree
	 */
	public FilteringFormulaHierarchyEntityPredicate(
		@Nullable Integer parent,
		boolean parentResult,
		@Nonnull QueryContext queryContext,
		@Nonnull FilterBy filterBy,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		this.parent = parent;
		this.parentResult = parentResult;
		this.filterBy = filterBy;
		try {
			final Supplier<String> stepDescriptionSupplier =
				() -> referenceSchema == null ?
					"Hierarchy statistics of `" + queryContext.getSchema().getName() + "` : " + filterBy
					: "Hierarchy statistics of `" + referenceSchema.getName() + "` (`" + referenceSchema.getReferencedEntityType() + "`): " + filterBy;
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescriptionSupplier
			);
			// create a visitor
			final FilterByVisitor theFilterByVisitor = new FilterByVisitor(
				queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				false
			);
			final Formula theFormula;
			if (referenceSchema == null) {
				theFormula = queryContext.analyse(
					theFilterByVisitor.executeInContext(
						GlobalEntityIndex.class,
						Collections.singletonList(queryContext.getGlobalEntityIndex()),
						null,
						queryContext.getSchema(),
						null,
						null,
						null,
						new AttributeSchemaAccessor(queryContext),
						AttributesContract::getAttribute,
						() -> {
							filterBy.accept(theFilterByVisitor);
							// get the result and clear the visitor internal structures
							return theFilterByVisitor.getFormulaAndClear();
						}
					)
				);
			} else {
				theFormula = FilterByVisitor.createFormulaForTheFilter(
					queryContext, filterBy, referenceSchema.getReferencedEntityType(), stepDescriptionSupplier
				);
			}
			// create a deferred formula that will log the execution time to query telemetry
			this.filteringFormula = new DeferredFormula(
				new FormulaWrapper(
					theFormula,
					formula -> {
						try {
							queryContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							return formula.compute();
						} finally {
							queryContext.popStep();
						}
					}
				)
			);
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * This constructor could be used when the filtered set is already known.
	 *
	 * @param filterBy         the original filtering constraint that led to the formula
	 * @param filteringFormula the formula containing valid entity primary keys that should be matched by this predicate
	 */
	public FilteringFormulaHierarchyEntityPredicate(
		@Nonnull FilterBy filterBy,
		@Nonnull Formula filteringFormula
	) {
		this.parent = null;
		this.parentResult = false;
		this.filterBy = filterBy;
		this.filteringFormula = filteringFormula;
	}

	/**
	 * This constructor is expected to be used from HierarchyRequirements that should never use reference attributes
	 * but always target referenced hierarchy entity attributes.
	 *
	 * @param queryContext    current query context
	 * @param entityIndex     the global index of with the data of the target entity
	 * @param filterBy        the filtering that targets referenced entity attributes
	 * @param referenceSchema the optional reference schema if the entity targets itself hierarchy tree
	 */
	public FilteringFormulaHierarchyEntityPredicate(
		@Nonnull QueryContext queryContext,
		@Nonnull GlobalEntityIndex entityIndex,
		@Nonnull FilterBy filterBy,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		this.parent = null;
		this.parentResult = false;
		this.filterBy = filterBy;
		try {
			final Supplier<String> stepDescriptionSupplier =
				() -> referenceSchema == null ?
					"Hierarchy statistics of `" + queryContext.getSchema().getName() + "` : " + filterBy
					: "Hierarchy statistics of `" + referenceSchema.getName() + "` (`" + referenceSchema.getReferencedEntityType() + "`): " + filterBy;
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescriptionSupplier
			);
			// create a visitor
			final FilterByVisitor theFilterByVisitor = new FilterByVisitor(
				queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				false
			);
			// now analyze the filter by in a nested context with exchanged primary entity index
			final Formula theFormula = queryContext.analyse(
				theFilterByVisitor.executeInContext(
					GlobalEntityIndex.class,
					Collections.singletonList(entityIndex),
					null,
					entityIndex.getEntitySchema(),
					null,
					null,
					null,
					new AttributeSchemaAccessor(
						queryContext.getCatalogSchema(),
						entityIndex.getEntitySchema(),
						null
					),
					AttributesContract::getAttribute,
					() -> {
						filterBy.accept(theFilterByVisitor);
						// get the result and clear the visitor internal structures
						return theFilterByVisitor.getFormulaAndClear();
					}
				)
			);
			// create a deferred formula that will log the execution time to query telemetry
			this.filteringFormula = new DeferredFormula(
				new FormulaWrapper(
					theFormula,
					formula -> {
						try {
							queryContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							return formula.compute();
						} finally {
							queryContext.popStep();
						}
					}
				)
			);
		} finally {
			queryContext.popStep();
		}
	}

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		if (this.hash == null) {
			this.filteringFormula.initialize(calculationContext);
			this.hash = this.filteringFormula.getHash();
		}
	}

	@Override
	public long getHash() {
		if (this.hash == null) {
		initialize(CalculationContext.NO_CACHING_INSTANCE);
}
		return this.hash;
	}

	@Override
	public boolean test(int hierarchyNodeId, int level, int distance) {
		return !stopNodeEncountered;
	}

	@Override
	public void traverse(int hierarchyNodeId, int level, int distance, @Nonnull Runnable traverser) {
		if (Objects.equals(hierarchyNodeId, parent)) {
			traverser.run();
		} else if (filteringFormula.compute().contains(hierarchyNodeId)) {
			try {
				stopNodeEncountered = true;
				traverser.run();
			} finally {
				stopNodeEncountered = false;
			}
		} else {
			traverser.run();
		}
	}

	@Override
	public boolean test(int hierarchyNodeId) {
		if (Objects.equals(hierarchyNodeId, parent)) {
			return parentResult;
		}
		return filteringFormula.compute().contains(hierarchyNodeId);
	}

	@Override
	public String toString() {
		return "HIERARCHY (" +
			"parent=" + parent +
			", filterBy=" + filterBy +
			", filteringFormula=" + filteringFormula +
			')';
	}
}
