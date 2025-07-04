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
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate.SelfTraversingPredicate;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;

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
	 * Field contains the original filter by constraint the {@link #filteringFormula} was created by.
	 */
	@Nullable private final FilterBy filterBy;
	/**
	 * Field contains the original filter by constraint the {@link #filteringFormula} was created by.
	 */
	@Nullable private final FilterBy anyChildFilter;
	/**
	 * The name of the target entity schema that is used to create the {@link #filteringFormula}.
	 */
	@Nonnull private final String targetEntityType;
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
	 * @param queryContext    current query context
	 * @param filterBy        the filtering that targets reference attributes but may also contain {@link EntityHaving}
	 *                        constraint (but only when reference schema is not null and the entity targets different entity hierarchy)
	 * @param anyChildFilter  the filtering that means, node is included in hierarchy tree if it has at least one child
	 *                        satisfying the filter
	 * @param referenceSchema the optional reference schema if the entity targets itself hierarchy tree
	 */
	public FilteringFormulaHierarchyEntityPredicate(
		@Nullable int[] parent,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
		@Nullable FilterBy filterBy,
		@Nullable FilterBy anyChildFilter,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		Assert.isPremiseValid(
			filterBy != null || anyChildFilter != null,
			"At least one of the filterBy or anyChildFilter must be specified!"
		);

		this.parent = parent;
		this.filterBy = filterBy;
		this.anyChildFilter = anyChildFilter;
		try {
			final Supplier<String> stepDescriptionSupplier =
				() -> referenceSchema == null ?
					"Hierarchy statistics of `" + queryContext.getSchema().getName() + "` : " + getFilterDescription()
					: "Hierarchy statistics of `" + referenceSchema.getName() + "` (`" + referenceSchema.getReferencedEntityType() + "`): " + getFilterDescription();
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
			final Formula theAnyChildFormula;
			final Scope scope = requestedScopes.iterator().next();
			final GlobalEntityIndex globalEntityIndex;
			if (referenceSchema == null) {
				Assert.isTrue(
					requestedScopes.size() == 1,
					() -> "The query contains multiple scopes: " +
						requestedScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + ". " +
						"The hierarchy filter can't be resolved - a hierarchy tree is maintained separately for each scope, and multiple trees cannot be merged."
				);

				this.targetEntityType = queryContext.getSchema().getName();
				final AttributeSchemaAccessor attributeSchemaAccessor = new AttributeSchemaAccessor(queryContext);
				globalEntityIndex = queryContext.getGlobalEntityIndex(scope);
				final List<GlobalEntityIndex> globalEntityIndices = Collections.singletonList(globalEntityIndex);
				final Function<FilterBy, Formula> formulaFactory = theFilterBy -> queryContext.analyse(
					theFilterByVisitor.executeInContextAndIsolatedFormulaStack(
						GlobalEntityIndex.class,
						() -> globalEntityIndices,
						null,
						queryContext.getSchema(),
						null,
						null,
						null,
						attributeSchemaAccessor,
						(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale)),
						() -> {
							theFilterBy.accept(theFilterByVisitor);
							// get the result and clear the visitor internal structures
							return theFilterByVisitor.getFormulaAndClear();
						}
					)
				);
				theFormula = filterBy == null ? null : formulaFactory.apply(filterBy);
				theAnyChildFormula = anyChildFilter == null ? null : formulaFactory.apply(anyChildFilter);
			} else {
				this.targetEntityType = referenceSchema.getReferencedEntityType();
				theFormula = filterBy == null ?
					null :
					FilterByVisitor.createFormulaForTheFilter(
						queryContext, requestedScopes, filterBy,
						this.targetEntityType, stepDescriptionSupplier
					);
				theAnyChildFormula = anyChildFilter == null ?
					null :
					FilterByVisitor.createFormulaForTheFilter(
						queryContext, requestedScopes, anyChildFilter,
						this.targetEntityType, stepDescriptionSupplier
					);
				globalEntityIndex = anyChildFilter == null ?
					null :
					queryContext.getGlobalEntityIndexIfExists(this.targetEntityType, scope).orElse(null);
			}
			// create a deferred formula that will log the execution time to query telemetry
			this.filteringFormula = new DeferredFormula(
				new FormulaWrapper(
					// this is only fake formula used to estimate cardinality and computation time of the formula
					// wrapper and other methods declared on formula interface except the compute() method
					FormulaFactory.or(
						Stream.of(theFormula, theAnyChildFormula)
							.filter(Objects::nonNull)
							.toArray(Formula[]::new)
					),
					(executionContext, formula) -> {
						try {
							executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, stepDescriptionSupplier);
							final Bitmap theFormulaResult;
							if (theFormula != null) {
								theFormula.initialize(executionContext);
								theFormulaResult = theFormula.compute();
							} else {
								theFormulaResult = EmptyBitmap.INSTANCE;
							}
							final Bitmap theAnyChildFormulaResult;
							if (theAnyChildFormula != null) {
								theAnyChildFormula.initialize(executionContext);
								theAnyChildFormulaResult = globalEntityIndex == null ?
									formula.compute() :
									globalEntityIndex.listNodesIncludingParents(formula.compute());
							} else {
								theAnyChildFormulaResult = EmptyBitmap.INSTANCE;
							}
							if (theFormula != null && theAnyChildFormula != null) {
								return new BaseBitmap(
									RoaringBitmapBackedBitmap.and(
										new RoaringBitmap[] {
											getRoaringBitmap(theFormulaResult),
											getRoaringBitmap(theAnyChildFormulaResult)
										}
									)
								);
							} else if (theFormula != null) {
								return theFormulaResult;
							} else {
								return theAnyChildFormulaResult;
							}
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
		@Nonnull String targetEntityType,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull FilterBy filterBy,
		@Nonnull Formula filteringFormula
	) {
		this.parent = null;
		this.targetEntityType = targetEntityType;
		this.requestedScopes = requestedScopes;
		this.filterBy = filterBy;
		this.anyChildFilter = null;
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
		this.filterBy = filterBy;
		this.anyChildFilter = null;
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
			this.targetEntityType = entitySchema.getName();
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

	/**
	 * Generates a description of the filtering criteria applied to the hierarchy entity predicate.
	 * The description includes details about the primary filtering constraint and any child-specific filters.
	 *
	 * @return A string representation of the filter criteria. If no filters are applied, returns an empty string.
	 */
	@Nonnull
	public String getFilterDescription() {
		return (this.filterBy == null ? "" : this.filterBy) +
			(this.anyChildFilter == null ? "" : (this.filterBy == null ? "child: " + this.anyChildFilter : " and any child matching: " + this.anyChildFilter));
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
		if (this.filteringFormula.compute().contains(hierarchyNodeId)) {
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
		return this.filteringFormula.compute().contains(hierarchyNodeId);
	}

	@Override
	public int hashCode() {
		int result = Arrays.hashCode(this.parent);
		result = 31 * result + (this.filterBy == null ? 0 : this.filterBy.hashCode());
		result = 31 * result + this.targetEntityType.hashCode();
		result = 31 * result + this.requestedScopes.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		FilteringFormulaHierarchyEntityPredicate that = (FilteringFormulaHierarchyEntityPredicate) o;
		return Arrays.equals(this.parent, that.parent) &&
			Objects.equals(this.filterBy, that.filterBy) &&
			this.targetEntityType.equals(that.targetEntityType) &&
			this.requestedScopes.equals(that.requestedScopes);
	}

	@Override
	public String toString() {
		return "HIERARCHY (" +
			"parent=" + Arrays.toString(this.parent) +
			", filterBy=" + this.filterBy +
			", targetEntitySchema='" + this.targetEntityType + '\'' +
			", requestedScopes=" + this.requestedScopes.stream().map(Enum::toString).collect(Collectors.joining(", ")) +
			')';
	}
}
