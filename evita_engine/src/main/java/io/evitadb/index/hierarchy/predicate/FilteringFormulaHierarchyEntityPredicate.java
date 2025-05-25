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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate.SelfTraversingPredicate;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	@Nullable private final int[] parent;
	/**
	 * The result that should be returned for parent node (constant).
	 */
	private final boolean parentResult;
	/**
	 * Field contains the original filter by constraint the {@link #filteringFormula} was created by.
	 */
	@Getter @Nonnull private final FilterBy filterBy;
	/**
	 * The name of the target entity schema that is used to create the {@link #filteringFormula}.
	 */
	@Nonnull private final String targetEntitySchema;
	/**
	 * The scope of the target entity schema.
	 */
	@Nonnull private final Set<Scope> requestedScopes;
	/**
	 * Formula computes id of all hierarchical entities that match input filter by constraint.
	 */
	@Getter @Nonnull private final Formula filteringFormula;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private final Long hash;
	/**
	 * Signalizes that the {@link HierarchyTraversalPredicate} reached a node that was marked as "stop" node and its
	 * children should be tested by a predicate logic as "false".
	 */
	private boolean stopNodeEncountered;
	/**
	 * True if the {@link #initializeIfNotAlreadyInitialized(QueryExecutionContext)} method was called.
	 */
	private boolean initialized;


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
		@Nullable int[] parent,
		boolean parentResult,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
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
				TargetIndexes.EMPTY
			);
			this.requestedScopes = requestedScopes;
			final Formula theFormula;
			if (referenceSchema == null) {
				Assert.isTrue(
					requestedScopes.size() == 1,
					() -> "The query contains multiple scopes: " +
						requestedScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + ". " +
						"The hierarchy filter can't be resolved - hierarchy tree is maintained separately for each scope and multiple trees cannot be merged."
				);

				final Scope scope = requestedScopes.iterator().next();
				this.targetEntitySchema = queryContext.getSchema().getName();
				theFormula = queryContext.analyse(
					theFilterByVisitor.executeInContextAndIsolatedFormulaStack(
						GlobalEntityIndex.class,
						() -> Collections.singletonList(queryContext.getGlobalEntityIndex(scope)),
						null,
						queryContext.getSchema(),
						null,
						null,
						null,
						new AttributeSchemaAccessor(queryContext),
						(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale)),
						() -> {
							filterBy.accept(theFilterByVisitor);
							// get the result and clear the visitor internal structures
							return theFilterByVisitor.getFormulaAndClear();
						}
					)
				);
			} else {
				this.targetEntitySchema = referenceSchema.getReferencedEntityType();
				theFormula = FilterByVisitor.createFormulaForTheFilter(
					queryContext, requestedScopes, filterBy,
					this.targetEntitySchema, stepDescriptionSupplier
				);
			}
			// create a deferred formula that will log the execution time to query telemetry
			this.filteringFormula = new DeferredFormula(
				new FormulaWrapper(
					theFormula,
					(executionContext, formula) -> {
						try {
							executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							formula.initialize(executionContext);
							return formula.compute();
						} finally {
							executionContext.popStep();
						}
					}
				)
			);
			this.hash = this.filteringFormula.getHash();
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
		@Nonnull String targetEntitySchema,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull FilterBy filterBy,
		@Nonnull Formula filteringFormula
	) {
		this.parent = null;
		this.parentResult = false;
		this.targetEntitySchema = targetEntitySchema;
		this.requestedScopes = requestedScopes;
		this.filterBy = filterBy;
		this.filteringFormula = filteringFormula;
		this.hash = this.filteringFormula.getHash();
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
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull GlobalEntityIndex entityIndex,
		@Nonnull FilterBy filterBy,
		@Nonnull EntitySchemaContract entitySchema,
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
				TargetIndexes.EMPTY
			);
			this.targetEntitySchema = entitySchema.getName();
			this.requestedScopes = Set.of(entityIndex.getIndexKey().scope());
			// now analyze the filter by in a nested context with exchanged primary entity index
			final Formula theFormula = queryContext.analyse(
				theFilterByVisitor.executeInContextAndIsolatedFormulaStack(
					GlobalEntityIndex.class,
					() -> Collections.singletonList(entityIndex),
					null,
					entitySchema,
					null,
					null,
					null,
					new AttributeSchemaAccessor(
						queryContext.getCatalogSchema(),
						entitySchema,
						null
					),
					(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale)),
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
					(executionContext, formula) -> {
						try {
							executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							return formula.compute();
						} finally {
							executionContext.popStep();
						}
					}
				)
			);
			this.hash = this.filteringFormula.getHash();
		} finally {
			queryContext.popStep();
		}
	}

	@Override
	public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
		if (!this.initialized) {
			this.filteringFormula.initialize(executionContext);
			this.initialized = true;
		}
	}

	@Override
	public long getHash() {
		Assert.isPremiseValid(this.hash != null, "The predicate hasn't been initialized!");
		return this.hash;
	}

	@Override
	public boolean test(int hierarchyNodeId, int level, int distance) {
		return !this.stopNodeEncountered;
	}

	@Override
	public void traverse(int hierarchyNodeId, int level, int distance, @Nonnull Runnable traverser) {
		if (this.parent != null && Arrays.stream(this.parent).anyMatch(it -> it == hierarchyNodeId)) {
			traverser.run();
		} else if (this.filteringFormula.compute().contains(hierarchyNodeId)) {
			try {
				this.stopNodeEncountered = true;
				traverser.run();
			} finally {
				this.stopNodeEncountered = false;
			}
		} else {
			traverser.run();
		}
	}

	@Override
	public boolean test(int hierarchyNodeId) {
		if (this.parent != null && Arrays.stream(this.parent).anyMatch(it -> it == hierarchyNodeId)) {
			return this.parentResult;
		}
		return this.filteringFormula.compute().contains(hierarchyNodeId);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		FilteringFormulaHierarchyEntityPredicate that = (FilteringFormulaHierarchyEntityPredicate) o;
		return this.parentResult == that.parentResult &&
			Arrays.equals(this.parent, that.parent) &&
			this.filterBy.equals(that.filterBy) &&
			this.targetEntitySchema.equals(that.targetEntitySchema) &&
			this.requestedScopes.equals(that.requestedScopes);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(this.parent);
		result = 31 * result + Boolean.hashCode(this.parentResult);
		result = 31 * result + this.filterBy.hashCode();
		result = 31 * result + this.targetEntitySchema.hashCode();
		result = 31 * result + this.requestedScopes.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return "HIERARCHY (" +
			"parent=" + Arrays.toString(this.parent) +
			", filterBy=" + this.filterBy +
			", targetEntitySchema='" + this.targetEntitySchema + '\'' +
			", requestedScopes=" + this.requestedScopes.stream().map(Enum::toString).collect(Collectors.joining(", ")) +
			')';
	}
}
