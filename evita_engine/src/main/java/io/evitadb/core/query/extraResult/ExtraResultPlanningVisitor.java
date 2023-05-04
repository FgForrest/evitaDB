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

package io.evitadb.core.query.extraResult;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.query.require.*;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.PrefetchRequirementCollector;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.RequireTranslator;
import io.evitadb.core.query.extraResult.translator.facet.FacetSummaryOfReferenceTranslator;
import io.evitadb.core.query.extraResult.translator.facet.FacetSummaryTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyChildrenTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyFromNodeTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyFromRootTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyOfReferenceTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyOfSelfTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchyParentsTranslator;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.HierarchySiblingsTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.AttributeHistogramTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.PriceHistogramTranslator;
import io.evitadb.core.query.extraResult.translator.reference.HierarchyContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceContentTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.DeferredSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * This {@link ConstraintVisitor} translates tree of {@link RequireConstraint} to a list of {@link ExtraResultProducer}.
 * Visitor represents the "planning" phase for the requirement resolution. The planning should be as light-weight as
 * possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExtraResultPlanningVisitor implements ConstraintVisitor {
	private static final Map<Class<? extends RequireConstraint>, RequireConstraintTranslator<? extends RequireConstraint>> TRANSLATORS;

	/* initialize list of all RequireConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = createHashMap(10);
		TRANSLATORS.put(Require.class, new RequireTranslator());
		TRANSLATORS.put(FacetSummary.class, new FacetSummaryTranslator());
		TRANSLATORS.put(FacetSummaryOfReference.class, new FacetSummaryOfReferenceTranslator());
		TRANSLATORS.put(AttributeHistogram.class, new AttributeHistogramTranslator());
		TRANSLATORS.put(PriceHistogram.class, new PriceHistogramTranslator());
		TRANSLATORS.put(HierarchyOfSelf.class, new HierarchyOfSelfTranslator());
		TRANSLATORS.put(HierarchyOfReference.class, new HierarchyOfReferenceTranslator());
		TRANSLATORS.put(HierarchyFromRoot.class, new HierarchyFromRootTranslator());
		TRANSLATORS.put(HierarchyFromNode.class, new HierarchyFromNodeTranslator());
		TRANSLATORS.put(HierarchyParents.class, new HierarchyParentsTranslator());
		TRANSLATORS.put(HierarchyChildren.class, new HierarchyChildrenTranslator());
		TRANSLATORS.put(HierarchySiblings.class, new HierarchySiblingsTranslator());
		TRANSLATORS.put(HierarchyContent.class, new HierarchyContentTranslator());
		TRANSLATORS.put(ReferenceContent.class, new ReferenceContentTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate @Getter private final QueryContext queryContext;
	/**
	 * This instance contains the {@link EntityIndex} set that is used to resolve passed query filter.
	 */
	@Getter private final TargetIndexes indexSetToUse;
	/**
	 * Reference to the collector of requirements for entity prefetch phase.
	 */
	@Getter @Delegate
	private final PrefetchRequirementCollector prefetchRequirementCollector;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Getter private final Formula filteringFormula;
	/**
	 * Contains prepared sorter implementation that takes output of the {@link #filteringFormula} and sorts the entity
	 * primary keys according to {@link OrderConstraint} in {@link EvitaRequest}.
	 */
	@Getter private final Sorter sorter;
	/**
	 * Contains the list of producers that react to passed requirements.
	 */
	@Getter private final LinkedHashSet<ExtraResultProducer> extraResultProducers = new LinkedHashSet<>(16);
	/**
	 * Contains an accessor providing access to the attribute schemas.
	 */
	@Getter private final AttributeSchemaAccessor attributeSchemaAccessor;
	/**
	 * Performance optimization when multiple translators ask for the same (last) producer.
	 */
	private ExtraResultProducer lastReturnedProducer;
	/**
	 * Contains {@link #getFilterBy()} without {@link HierarchyWithin} / {@link HierarchyWithinRoot} sub-constraints.
	 * The field is initialized lazily.
	 */
	@Nullable private FilterBy filterByWithoutHierarchyFilter;
	/**
	 * Contains {@link #getFilteringFormula()} without {@link UserFilterFormula} sub-trees. The field is initialized
	 * lazily.
	 */
	private Formula filteringFormulaWithoutUserFilter;
	/**
	 * Contains {@link #getFilterBy()} ()} without {@link UserFilterFormula} and {@link HierarchyWithin} /
	 * {@link HierarchyWithinRoot} sub-constraints. The field is initialized lazily.
	 */
	private FilterBy filteringFormulaWithoutHierarchyAndUserFilter;
	/**
	 * Contains set (usually of size == 1 or 0) that contains references to the {@link UserFilterFormula} inside
	 * {@link #filteringFormula}. This is a helper field that allows to reuse result of the formula search multiple
	 * times.
	 */
	private Set<Formula> userFilterFormula;

	public ExtraResultPlanningVisitor(
		@Nonnull QueryContext queryContext,
		@Nonnull TargetIndexes indexSetToUse,
		@Nonnull PrefetchRequirementCollector prefetchRequirementCollector,
		@Nonnull Formula filteringFormula,
		@Nullable Sorter sorter
	) {
		this.queryContext = queryContext;
		this.indexSetToUse = indexSetToUse;
		this.prefetchRequirementCollector = prefetchRequirementCollector;
		this.filteringFormula = filteringFormula;
		this.sorter = sorter;
		this.attributeSchemaAccessor = new AttributeSchemaAccessor(queryContext);
	}

	/**
	 * Method finds existing {@link ExtraResultProducer} implementation of particular `producerClass` allowing multiple
	 * translators to reuse (enrich) it.
	 */
	@Nullable
	public <T extends ExtraResultProducer> T findExistingProducer(Class<T> producerClass) {
		if (producerClass.isInstance(lastReturnedProducer)) {
			//noinspection unchecked
			return (T) lastReturnedProducer;
		}
		for (ExtraResultProducer extraResultProducer : extraResultProducers) {
			if (producerClass.isInstance(extraResultProducer)) {
				lastReturnedProducer = extraResultProducer;
				//noinspection unchecked
				return (T) extraResultProducer;
			}
		}
		return null;
	}

	/**
	 * Returns the {@link #getFilteringFormula()} that is stripped of all {@link UserFilterFormula} parts.
	 * Result of this method is cached so that additional calls introduce no performance penalty and also the formula
	 * memoized sub-results are shared once the {@link Formula#compute()} method is called for the first time.
	 */
	@Nonnull
	public Formula getFilteringFormulaWithoutUserFilter() {
		if (filteringFormulaWithoutUserFilter == null) {
			filteringFormulaWithoutUserFilter = FormulaCloner.clone(
				filteringFormula,
				formula -> formula instanceof UserFilterFormula ? null : formula
			);
		}
		return filteringFormulaWithoutUserFilter;
	}

	/**
	 * Returns set (usually of size == 1 or 0) that contains references to the {@link UserFilterFormula} inside
	 * {@link #filteringFormula}. Result of this method is cached so that additional calls introduce no performance
	 * penalty.
	 */
	@Nonnull
	public Set<Formula> getUserFilteringFormula() {
		if (userFilterFormula == null) {
			userFilterFormula = new HashSet<>(
				FormulaFinder.find(
					getFilteringFormula(), UserFilterFormula.class, LookUp.SHALLOW
				)
			);
		}
		return userFilterFormula;
	}

	/**
	 * Returns the {@link #getFilterBy()} that is stripped of all {@link HierarchyWithin} and
	 * {@link HierarchyWithinRoot} sub-constraints. Result of this method is cached so that additional calls introduce no
	 * performance penalty.
	 */
	@Nullable
	public FilterBy getFilterByWithoutHierarchyFilter(@Nullable ReferenceSchemaContract referenceSchema) {
		if (filterByWithoutHierarchyFilter == null) {
			filterByWithoutHierarchyFilter = (FilterBy) ofNullable(getFilterBy())
				.map(it ->
					ConstraintCloneVisitor.clone(
						it,
						(visitor, constraint) -> {
							final Function<FilterConstraint, FilterConstraint> wrapper = referenceSchema == null ?
								Function.identity() :
								filter -> new FilterBy(new ReferenceHaving(referenceSchema.getName(), entityHaving(filter)));
							if (constraint instanceof HierarchyFilterConstraint hfc) {
								final FilterConstraint[] excludedChildrenFilter = hfc.getExcludedChildrenFilter();
								if (ArrayUtils.isEmpty(excludedChildrenFilter)) {
									return null;
								} else if (excludedChildrenFilter.length == 1){
									return wrapper.apply(not(excludedChildrenFilter[0]));
								} else {
									return wrapper.apply(not(and(excludedChildrenFilter)));
								}
							} else {
								return constraint;
							}
						}
					)
				)
				.orElseGet(FilterBy::new);

		}
		return filterByWithoutHierarchyFilter.isApplicable() ? filterByWithoutHierarchyFilter : null;
	}

	/**
	 * Returns the {@link #getFilterBy()} that is stripped of all {@link HierarchyWithin},
	 * {@link HierarchyWithinRoot} constraints and {@link UserFilter} parts. Result of this method is cached so that
	 * additional calls introduce no performance penalty.
	 */
	@Nullable
	public FilterBy getFilterByWithoutHierarchyAndUserFilter(@Nullable ReferenceSchemaContract referenceSchema) {
		if (filteringFormulaWithoutHierarchyAndUserFilter == null) {
			filteringFormulaWithoutHierarchyAndUserFilter = (FilterBy) ofNullable(getFilterBy())
				.map(it ->
					ConstraintCloneVisitor.clone(
						it,
						(visitor, constraint) -> {
							if (constraint instanceof HierarchyFilterConstraint hfc) {
								final Function<FilterConstraint, FilterConstraint> wrapper = referenceSchema == null ?
									Function.identity() :
									filter -> new FilterBy(new ReferenceHaving(referenceSchema.getName(), entityHaving(filter)));
								final FilterConstraint[] excludedChildrenFilter = hfc.getExcludedChildrenFilter();
								if (ArrayUtils.isEmpty(excludedChildrenFilter)) {
									return null;
								} else if (excludedChildrenFilter.length == 1){
									return wrapper.apply(not(excludedChildrenFilter[0]));
								} else {
									return wrapper.apply(not(and(excludedChildrenFilter)));
								}
							} else if (constraint instanceof UserFilter) {
								return null;
							} else {
								return constraint;
							}
						}
					)
				)
				.orElseGet(FilterBy::new);

		}
		return filteringFormulaWithoutHierarchyAndUserFilter.isApplicable() ? filteringFormulaWithoutHierarchyAndUserFilter : null;
	}

	/**
	 * Method creates the {@link Sorter} implementation that should be used for sorting {@link LevelInfo} inside
	 * the {@link io.evitadb.api.requestResponse.extraResult.Hierarchy} result object.
	 */
	@Nonnull
	public Sorter createSorter(
		@Nonnull ConstraintContainer<OrderConstraint> orderBy,
		@Nonnull EntityIndex entityIndex,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		try {
			queryContext.pushStep(
				QueryPhase.PLANNING_SORT,
				stepDescriptionSupplier
			);
			// crete a visitor
			final OrderByVisitor orderByVisitor = new OrderByVisitor(
				queryContext,
				prefetchRequirementCollector,
				filteringFormula
			);
			// now analyze the filter by in a nested context with exchanged primary entity index
			return orderByVisitor.executeInContext(
				entityIndex,
				new AttributeSchemaAccessor(queryContext),
				EntityAttributeExtractor.INSTANCE,
				() -> {
					for (OrderConstraint innerConstraint : orderBy.getChildren()) {
						innerConstraint.accept(orderByVisitor);
					}
					// create a deferred sorter that will log the execution time to query telemetry
					return new DeferredSorter(
						orderByVisitor.getLastUsedSorter(),
						sorter -> {
							try {
								queryContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE, stepDescriptionSupplier);
								return sorter.get();
							} finally {
								queryContext.popStep();
							}
						}
					);
				}
			);
		} finally {
			queryContext.popStep();
		}
	}

	/**
	 * Method finds sorter of specified type in the current {@link #sorter} or sorters that are chained in it as secondary
	 * or tertiary sorters.
	 */
	@Nullable
	public <T extends Sorter> T findSorter(@Nonnull Class<T> sorterType) {
		Sorter theSorter = this.sorter;
		while (theSorter != null) {
			if (sorterType.isInstance(theSorter)) {
				//noinspection unchecked
				return (T) theSorter;
			}
			theSorter = theSorter.getNextSorter();
		}
		return null;
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final RequireConstraint requireConstraint = (RequireConstraint) constraint;

		if (requireConstraint instanceof ExtraResultRequireConstraint) {
			@SuppressWarnings("unchecked") final RequireConstraintTranslator<RequireConstraint> translator =
				(RequireConstraintTranslator<RequireConstraint>) TRANSLATORS.get(requireConstraint.getClass());
			isPremiseValid(
				translator != null,
				"No translator found for query `" + requireConstraint.getClass() + "`!"
			);

			// if query is a container query
			if (requireConstraint instanceof ConstraintContainer) {
				@SuppressWarnings("unchecked") final ConstraintContainer<RequireConstraint> container = (ConstraintContainer<RequireConstraint>) requireConstraint;
				// process children constraints
				if (!(translator instanceof SelfTraversingTranslator)) {
					for (RequireConstraint child : container) {
						child.accept(this);
					}
				} else {
					registerProducer(translator.apply(requireConstraint, this));
				}
			} else if (requireConstraint instanceof ConstraintLeaf) {
				// process the leaf query
				registerProducer(translator.apply(requireConstraint, this));
			} else {
				// sanity check only
				throw new EvitaInternalError("Should never happen");
			}
		} else if (requireConstraint instanceof ConstraintContainer) {
			@SuppressWarnings("unchecked") final ConstraintContainer<RequireConstraint> container = (ConstraintContainer<RequireConstraint>) requireConstraint;
			for (RequireConstraint child : container) {
				child.accept(this);
			}
		}
	}

	/**
	 * Method registers the {@link ExtraResultProducer} instance.
	 */
	public void registerProducer(@Nullable ExtraResultProducer extraResultProducer) {
		ofNullable(extraResultProducer).ifPresent(extraResultProducers::add);
	}

}
