/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.RequireInScopeTranslator;
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
import io.evitadb.core.query.extraResult.translator.reference.AssociatedDataContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.AttributeContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.EntityFetchTranslator;
import io.evitadb.core.query.extraResult.translator.reference.EntityGroupFetchTranslator;
import io.evitadb.core.query.extraResult.translator.reference.HierarchyContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.PriceContentTranslator;
import io.evitadb.core.query.extraResult.translator.reference.ReferenceContentTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.DeferredSorter;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
		TRANSLATORS.put(EntityFetch.class, new EntityFetchTranslator());
		TRANSLATORS.put(EntityGroupFetch.class, new EntityGroupFetchTranslator());
		TRANSLATORS.put(HierarchyContent.class, new HierarchyContentTranslator());
		TRANSLATORS.put(ReferenceContent.class, new ReferenceContentTranslator());
		TRANSLATORS.put(PriceContent.class, new PriceContentTranslator());
		TRANSLATORS.put(AttributeContent.class, new AttributeContentTranslator());
		TRANSLATORS.put(AssociatedDataContent.class, new AssociatedDataContentTranslator());
		TRANSLATORS.put(RequireInScope.class, new RequireInScopeTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate @Getter private final QueryPlanningContext queryContext;
	/**
	 * This instance contains the {@link io.evitadb.index.Index} set that is used to resolve passed query filter.
	 */
	@Getter private final TargetIndexes<?> indexSetToUse;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Getter private final Formula filteringFormula;
	/**
	 * Reference to {@link FilterByVisitor} used for creating filterFormula.
	 */
	@Getter private final FilterByVisitor filterByVisitor;
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
	 * Contemporary stack for auxiliary data resolved for each level of the query.
	 */
	private final Deque<ProcessingScope> scope = new ArrayDeque<>(32);
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
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull TargetIndexes<?> indexSetToUse,
		@Nonnull Formula filteringFormula,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nullable Sorter sorter
	) {
		this.queryContext = queryContext;
		this.indexSetToUse = indexSetToUse;
		this.filteringFormula = filteringFormula;
		this.filterByVisitor = filterByVisitor;
		this.sorter = sorter;
		this.attributeSchemaAccessor = new AttributeSchemaAccessor(queryContext);
		final LinkedList<Set<Scope>> requestedScopes = new LinkedList<>();
		requestedScopes.add(queryContext.getScopes());
		this.scope.push(
			new ProcessingScope(
				null,
				requestedScopes,
				() -> null,
				() -> null
			)
		);
	}

	/**
	 * Returns superset of all possible results without any filtering.
	 * @return formula that represents the superset of all possible results.
	 */
	@Nonnull
	public Formula getSuperSetFormula() {
		return this.filterByVisitor.getSuperSetFormula();
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
			filteringFormulaWithoutUserFilter = ofNullable(
				FormulaCloner.clone(
				filteringFormula,
				formula -> formula instanceof UserFilterFormula ? null : formula
			)).orElseGet(this::getSuperSetFormula);
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
								final FilterConstraint[] havingChildrenFilter = hfc.getHavingChildrenFilter();
								if (ArrayUtils.isEmpty(excludedChildrenFilter)) {
									if (ArrayUtils.isEmpty(havingChildrenFilter)) {
										return null;
									} else if (havingChildrenFilter.length == 1) {
										return wrapper.apply(havingChildrenFilter[0]);
									} else {
										return wrapper.apply(and(havingChildrenFilter));
									}
								} else if (excludedChildrenFilter.length == 1) {
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
								final FilterConstraint[] havingChildrenFilter = hfc.getHavingChildrenFilter();
								if (ArrayUtils.isEmpty(excludedChildrenFilter)) {
									if (ArrayUtils.isEmpty(havingChildrenFilter)) {
										return null;
									} else if (havingChildrenFilter.length == 1) {
										return wrapper.apply(havingChildrenFilter[0]);
									} else {
										return wrapper.apply(and(havingChildrenFilter));
									}
								} else if (excludedChildrenFilter.length == 1) {
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
	public NestedContextSorter createSorter(
		@Nonnull ConstraintContainer<OrderConstraint> orderBy,
		@Nullable Locale locale,
		@Nonnull EntityCollection entityCollection,
		@Nonnull String entityType,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		try {
			this.queryContext.pushStep(
				QueryPhase.PLANNING_SORT,
				stepDescriptionSupplier
			);
			// we have to create and trap the nested query context here to carry it along with the sorter
			// otherwise the sorter will target and use the incorrectly originally queried (prefetched) entities
			final QueryPlanningContext nestedQueryContext = entityCollection.createQueryContext(
				this.queryContext,
				this.queryContext.getEvitaRequest().deriveCopyWith(
					entityType,
					null,
					new OrderBy(orderBy.getChildren()),
					this.queryContext.getLocale()
				),
				this.queryContext.getEvitaSession()
			);

			final Set<Scope> scopes = getProcessingScope().getScopes();
			final GlobalEntityIndex[] entityIndexes = scopes.stream()
				.map(scope -> entityCollection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope)))
				.map(GlobalEntityIndex.class::cast)
				.toArray(GlobalEntityIndex[]::new);
			final Sorter sorter;
			if (entityIndexes.length == 0) {
				sorter = NoSorter.INSTANCE;
			} else {
				// create a visitor
				final OrderByVisitor orderByVisitor = new OrderByVisitor(
					nestedQueryContext,
					Collections.emptyList(),
					this.filterByVisitor,
					this.filteringFormula
				);
				// now analyze the filter by in a nested context with exchanged primary entity index
				sorter = orderByVisitor.executeInContext(
					entityIndexes,
					entityType,
					locale,
					new AttributeSchemaAccessor(nestedQueryContext.getCatalogSchema(), entityCollection.getSchema()),
					EntityAttributeExtractor.INSTANCE,
					() -> {
						for (OrderConstraint innerConstraint : orderBy.getChildren()) {
							innerConstraint.accept(orderByVisitor);
						}
						// create a deferred sorter that will log the execution time to query telemetry
						return new DeferredSorter(
							orderByVisitor.getSorter(),
							theSorter -> {
								try {
									nestedQueryContext.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE, stepDescriptionSupplier);
									return theSorter.getAsInt();
								} finally {
									nestedQueryContext.popStep();
								}
							}
						);
					}
				);
			}
			return new NestedContextSorter(
				nestedQueryContext.createExecutionContext(), sorter
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
				"No translator found for constraint `" + requireConstraint.getClass() + "`!"
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
					registerProducer(translator.createProducer(requireConstraint, this));
				}
			} else if (requireConstraint instanceof ConstraintLeaf) {
				// process the leaf query
				registerProducer(translator.createProducer(requireConstraint, this));
			} else {
				// sanity check only
				throw new GenericEvitaInternalError("Should never happen");
			}
		} else {
			@SuppressWarnings("unchecked") final RequireConstraintTranslator<RequireConstraint> translator =
				(RequireConstraintTranslator<RequireConstraint>) TRANSLATORS.get(requireConstraint.getClass());

			if (translator != null) {
				translator.createProducer(requireConstraint, this);
			}

			if (requireConstraint instanceof ConstraintContainer && !(translator instanceof SelfTraversingTranslator)) {
				@SuppressWarnings("unchecked") final ConstraintContainer<RequireConstraint> container = (ConstraintContainer<RequireConstraint>) requireConstraint;
				for (RequireConstraint child : container) {
					child.accept(this);
				}
			}
		}
	}

	/**
	 * Method registers the {@link ExtraResultProducer} instance.
	 */
	public void registerProducer(@Nullable ExtraResultProducer extraResultProducer) {
		ofNullable(extraResultProducer).ifPresent(extraResultProducers::add);
	}

	/**
	 * Sets different {@link EntityIndex} to be used in scope of lambda.
	 */
	public final <T> T executeInContext(
		@Nonnull RequireConstraint requirement,
		@Nonnull Supplier<ReferenceSchemaContract> referenceSchemaSupplier,
		@Nonnull Supplier<EntitySchemaContract> entitySchemaSupplier,
		@Nonnull Supplier<T> lambda
	) {
		try {
			final LinkedList<Set<Scope>> scopes = new LinkedList<>();
			scopes.add(getProcessingScope().getScopes());
			this.scope.push(
				new ProcessingScope(
					requirement,
					scopes,
					referenceSchemaSupplier,
					entitySchemaSupplier
				)
			);
			return lambda.get();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Returns current processing scope.
	 */
	@Nonnull
	public ProcessingScope getProcessingScope() {
		if (this.scope.isEmpty()) {
			throw new GenericEvitaInternalError("Scope should never be empty");
		} else {
			return Objects.requireNonNull(this.scope.peek());
		}
	}

	/**
	 * Return the {@link ReferenceSchemaContract} valid for current context or null.
	 */
	@Nonnull
	public Stream<RequireConstraint> getEntityContentRequireChain(@Nonnull EntityContentRequire current) {
		return Stream.concat(
			StreamSupport
				.stream(
					Spliterators.spliteratorUnknownSize(this.scope.descendingIterator(), Spliterator.ORDERED),
					false
				)
				.map(ProcessingScope::requirement)
				.filter(Objects::nonNull),
			Stream.of(current)
		);
	}

	/**
	 * Return the {@link ReferenceSchemaContract} valid for current context or null.
	 */
	@Nonnull
	public Optional<ReferenceSchemaContract> getCurrentReferenceSchema() {
		return getProcessingScope().getReferenceSchema();
	}

	/**
	 * Return the {@link EntitySchemaContract} valid for current context.
	 */
	@Nonnull
	public Optional<EntitySchemaContract> getCurrentEntitySchema() {
		return getProcessingScope().getEntitySchema();
	}

	/**
	 * Returns true if the scope relates to top entity.
	 */
	public boolean isScopeOfQueriedEntity() {
		return scope.size() <= 1;
	}

	/**
	 * Returns true if the scope relates to top entity.
	 *
	 * @return true if the scope relates to top entity
	 */
	public boolean isRootScope() {
		return this.scope.size() == 1;
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link RequireConstraintTranslator}
	 * implementations to exchange schema that is being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 */
	public record ProcessingScope(
		@Nullable RequireConstraint requirement,
		@Nonnull Deque<Set<Scope>> requiredScopes,
		@Nonnull Supplier<ReferenceSchemaContract> referenceSchemaAccessor,
		@Nonnull Supplier<EntitySchemaContract> entitySchemaAccessor
	) {

		/**
		 * Returns reference schema if any.
		 */
		@Nonnull
		public Optional<ReferenceSchemaContract> getReferenceSchema() {
			return ofNullable(referenceSchemaAccessor.get());
		}

		/**
		 * Returns entity schema.
		 */
		@Nonnull
		public Optional<EntitySchemaContract> getEntitySchema() {
			return ofNullable(entitySchemaAccessor.get());
		}

		/**
		 * Retrieves the set of requested scopes from the processing context.
		 *
		 * @return A non-null set of {@link Scope} that are required for the current processing context.
		 */
		@Nonnull
		public Set<Scope> getScopes() {
			return Objects.requireNonNull(this.requiredScopes.peek());
		}

		/**
		 * Executes the given supplier within the context of the specified scope. This method ensures that
		 * the specified scope is applied for the duration of the supplier's execution and then restores
		 * the previous scope afterwards.
		 *
		 * @param scopeToUse the scope to be applied during the execution of the supplier
		 * @param lambda the supplier function to be executed within the specified scope
		 * @return the result produced by the supplier
		 */
		public <S> S doWithScope(@Nonnull Scope scopeToUse, @Nonnull Supplier<S> lambda) {
			try {
				this.requiredScopes.push(EnumSet.of(scopeToUse));
				return lambda.get();
			} finally {
				this.requiredScopes.pop();
			}
		}

	}

}
