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

package io.evitadb.core.query.filter;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.exception.ReferencePartitionReindexRequiredException;
import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.PrefetchStrategyResolver;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.facet.FacetHavingFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.MultipleEntityFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.reference.IndexTaggedFormula;
import io.evitadb.core.query.algebra.reference.ReferencedEntityIndexPrimaryKeyTranslatingFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.translator.FilterByTranslator;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.attribute.*;
import io.evitadb.core.query.filter.translator.behavioral.FilterInScopeTranslator;
import io.evitadb.core.query.filter.translator.behavioral.UserFilterTranslator;
import io.evitadb.core.query.filter.translator.bool.AndTranslator;
import io.evitadb.core.query.filter.translator.bool.NotTranslator;
import io.evitadb.core.query.filter.translator.bool.OrTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityLocaleEqualsTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyBetweenTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyGreaterThanEqualsTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyGreaterThanTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyInSetTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyLessThanEqualsTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyLessThanTranslator;
import io.evitadb.core.query.filter.translator.entity.alternative.ScopeEntityToBitmapFilter;
import io.evitadb.core.query.filter.translator.facet.FacetHavingTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinRootTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.core.query.filter.translator.histogram.HistogramHavingTranslator;
import io.evitadb.core.query.filter.translator.histogram.ResolvedHistogramHaving;
import io.evitadb.core.query.filter.translator.price.PriceBetweenTranslator;
import io.evitadb.core.query.filter.translator.price.PriceInCurrencyTranslator;
import io.evitadb.core.query.filter.translator.price.PriceInPriceListsTranslator;
import io.evitadb.core.query.filter.translator.price.PriceValidInTranslator;
import io.evitadb.core.query.filter.translator.reference.EntityHavingTranslator;
import io.evitadb.core.query.filter.translator.reference.GroupHavingTranslator;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper.GlobalIndexAndFormula;
import io.evitadb.core.query.filter.translator.reference.ReferenceHavingTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.function.TriFunction;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.mutation.local.VirtualReferencePartitionWriter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Delegate;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.PrimitiveIterator.OfInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * This {@link ConstraintVisitor} translates tree of {@link FilterConstraint} to a tree of {@link Formula}.
 * Visitor represents the "planning" phase for the filtering resolution. The planning should be as light-weight as
 * possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FilterByVisitor implements ConstraintVisitor, PrefetchStrategyResolver {
	private static final Formula[] EMPTY_INTEGER_FORMULA = Formula.EMPTY_FORMULA_ARRAY;
	/**
	 * Contains index of all {@link FilterConstraint} to {@link Formula} translators.
	 */
	private static final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>> TRANSLATORS;
	/**
	 * The attribute constraints whose translator is not an {@link AttributeValueMatchingTranslator}:
	 *
	 * - {@link AttributeIs} asks whether the attribute holds any value at all, not whether one value satisfies a test
	 *   of it, so there is no per-value matcher to create.
	 */
	private static final Set<Class<? extends FilterConstraint>> ATTRIBUTE_CONSTRAINTS_WITHOUT_VALUE_MATCHER =
		Set.of(AttributeIs.class);
	/**
	 * Contains set of formulas that are considered conjunctive for purpose of this visitor.
	 */
	private static final Set<Class<? extends FilterConstraint>> CONJUNCTIVE_CONSTRAINTS;
	/**
	 * Contains set of formulas that are considered conjunctive for purpose of this visitor.
	 */
	private static final Set<Class<? extends Formula>> CONJUNCTIVE_FORMULAS;
	/**
	 * A no-op supplier that returns `null` for any missing reduced group entity index lookup.
	 */
	private static final BiFunction<EntitySchemaContract, EntityIndexKey, ReducedGroupEntityIndex>
		NO_OP_MISSING_RGEI_SUPPLIER = (entitySchema, entityIndexKey) -> null;

	/* initialize list of all FilterableConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = createHashMap(64);
		TRANSLATORS.put(FilterBy.class, new FilterByTranslator());
		TRANSLATORS.put(And.class, new AndTranslator());
		TRANSLATORS.put(Or.class, new OrTranslator());
		TRANSLATORS.put(Not.class, new NotTranslator());
		TRANSLATORS.put(EntityPrimaryKeyInSet.class, new EntityPrimaryKeyInSetTranslator());
		TRANSLATORS.put(EntityPrimaryKeyGreaterThan.class, new EntityPrimaryKeyGreaterThanTranslator());
		TRANSLATORS.put(EntityPrimaryKeyGreaterThanEquals.class, new EntityPrimaryKeyGreaterThanEqualsTranslator());
		TRANSLATORS.put(EntityPrimaryKeyLessThan.class, new EntityPrimaryKeyLessThanTranslator());
		TRANSLATORS.put(EntityPrimaryKeyLessThanEquals.class, new EntityPrimaryKeyLessThanEqualsTranslator());
		TRANSLATORS.put(EntityPrimaryKeyBetween.class, new EntityPrimaryKeyBetweenTranslator());
		TRANSLATORS.put(AttributeEquals.class, new AttributeEqualsTranslator());
		TRANSLATORS.put(AttributeLessThan.class, new AttributeLessThanTranslator());
		TRANSLATORS.put(AttributeLessThanEquals.class, new AttributeLessThanEqualsTranslator());
		TRANSLATORS.put(AttributeGreaterThan.class, new AttributeGreaterThanTranslator());
		TRANSLATORS.put(AttributeGreaterThanEquals.class, new AttributeGreaterThanEqualsTranslator());
		TRANSLATORS.put(AttributeBetween.class, new AttributeBetweenTranslator());
		TRANSLATORS.put(AttributeInRange.class, new AttributeInRangeTranslator());
		TRANSLATORS.put(AttributeInSet.class, new AttributeInSetTranslator());
		TRANSLATORS.put(AttributeIs.class, new AttributeIsTranslator());
		TRANSLATORS.put(AttributeStartsWith.class, new AttributeStartsWithTranslator());
		TRANSLATORS.put(AttributeEndsWith.class, new AttributeEndsWithTranslator());
		TRANSLATORS.put(AttributeContains.class, new AttributeContainsTranslator());
		TRANSLATORS.put(EntityLocaleEquals.class, new EntityLocaleEqualsTranslator());
		TRANSLATORS.put(EntityHaving.class, new EntityHavingTranslator());
		TRANSLATORS.put(GroupHaving.class, new GroupHavingTranslator());
		TRANSLATORS.put(ReferenceHaving.class, new ReferenceHavingTranslator());
		TRANSLATORS.put(PriceInCurrency.class, new PriceInCurrencyTranslator());
		TRANSLATORS.put(PriceValidIn.class, new PriceValidInTranslator());
		TRANSLATORS.put(PriceInPriceLists.class, new PriceInPriceListsTranslator());
		TRANSLATORS.put(PriceBetween.class, new PriceBetweenTranslator());
		TRANSLATORS.put(HierarchyWithin.class, new HierarchyWithinTranslator());
		TRANSLATORS.put(HierarchyWithinRoot.class, new HierarchyWithinRootTranslator());
		TRANSLATORS.put(FacetHaving.class, new FacetHavingTranslator());
		TRANSLATORS.put(HistogramHaving.class, new HistogramHavingTranslator());
		TRANSLATORS.put(UserFilter.class, new UserFilterTranslator());
		TRANSLATORS.put(FilterInScope.class, new FilterInScopeTranslator());
		TRANSLATORS.put(EntityScope.class, FilteringConstraintTranslator.noOpTranslator());

		CONJUNCTIVE_FORMULAS = new HashSet<>(16);
		CONJUNCTIVE_FORMULAS.add(AndFormula.class);
		CONJUNCTIVE_FORMULAS.add(UserFilterFormula.class);
		CONJUNCTIVE_FORMULAS.add(SelectionFormula.class);
		CONJUNCTIVE_FORMULAS.add(AttributeFormula.class);
		CONJUNCTIVE_FORMULAS.add(ScopeContainerFormula.class);
		CONJUNCTIVE_FORMULAS.add(FacetHavingFormula.class);

		CONJUNCTIVE_CONSTRAINTS = new HashSet<>(16);
		CONJUNCTIVE_CONSTRAINTS.add(And.class);
		CONJUNCTIVE_CONSTRAINTS.add(UserFilter.class);
		CONJUNCTIVE_CONSTRAINTS.add(FilterInScope.class);
		CONJUNCTIVE_CONSTRAINTS.add(FilterBy.class);

		verifyAttributeTranslators(TRANSLATORS);
	}

	/**
	 * Contemporary stack for keeping results resolved for each level of the query.
	 */
	private final Deque<List<Formula>> stack = new ArrayDeque<>(16);
	/**
	 * Contemporary stack for keeping results resolved for each level of the query.
	 */
	@Getter(AccessLevel.PROTECTED)
	private final Deque<ProcessingScope<? extends Index<?>>> scope = new ArrayDeque<>(8);
	/**
	 * Contains list of registered post processors. Formula post processor is used to transform final {@link Formula}
	 * tree constructed in {@link FilterByVisitor} before computing the result. Post processors should analyze created
	 * tree and optimize it to achieve maximal impact of memoization process or limit the scope of processed records
	 * as soon as possible. We may take advantage of transitivity in boolean algebra to exchange formula placement
	 * the way it's most performant.
	 */
	@Nonnull
	private final Deque<LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor>> postProcessors = new ArrayDeque<>(8);
	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Nonnull
	@Delegate(excludes = PrefetchStrategyResolver.class)
	@Getter private final QueryPlanningContext queryContext;
	/**
	 * Collection contains all alternative {@link TargetIndexes} sets that might already contain precalculated information
	 * related to {@link EntityIndex} that can be used to partially resolve input filter although the target index set
	 * is not used to resolve entire query filter.
	 */
	@Nonnull
	private final List<TargetIndexes<?>> targetIndexes;
	/**
	 * This instance contains the {@link EntityIndex} set that is used to resolve passed query filter.
	 */
	@Nonnull @Getter
	private final TargetIndexes<? extends Index<?>> indexSetToUse;
	/**
	 * Contains the translated formula from the filtering query source tree.
	 */
	@Nullable
	private Formula computedFormula;
	/**
	 * Plan-time registry of resolved `histogramHaving` carriers populated by
	 * {@link HistogramHavingTranslator} during this visitor's filter translation pass and consumed
	 * by `ReferenceHistogramStatisticsTranslator` during extra-result planning of the same plan.
	 *
	 * The registry lives on the visitor (not on the shared {@link QueryPlanningContext}) because
	 * {@link io.evitadb.core.query.QueryPlanner#createFilterFormula} constructs a fresh
	 * {@link FilterByVisitor} per eligible {@link io.evitadb.core.query.indexSelection.TargetIndexes}
	 * — every alternative plan re-translates the filter tree and would otherwise append duplicate
	 * entries to a shared registry. Per-visitor scoping ensures each plan's extra-result phase reads
	 * exactly its own pass's resolved tuples.
	 */
	@Nonnull
	private final List<ResolvedHistogramHaving> resolvedHistogramHavings = new ArrayList<>(4);
	/**
	 * True when this visitor translates a filter only to check it against the schemas and its formula is thrown away
	 * - see {@link #createConstraintCheckVisitor(QueryPlanningContext)}. Such a visitor looks into no index holding
	 * data: the type index of a reference and the global index of an entity type a nested `entityHaving` or
	 * `groupHaving` query targets are replaced by empty indexes, so the check costs one translation and never
	 * evaluates anything. The nested filter of such a query is checked by a visitor that only checks as well, so the
	 * reference constraints nested in it at any depth look into no index holding data either.
	 */
	@Getter private final boolean constraintCheckOnly;

	/**
	 * Method returns true for all {@link FilterConstraint} types that are conjunctive.
	 */
	public static boolean isConjunctiveFormula(@Nonnull Class<? extends Formula> clazz) {
		return CONJUNCTIVE_FORMULAS.contains(clazz);
	}

	/**
	 * Method creates a new formula that looks for entity primary keys in global index of `entityType` collection that
	 * match the `filterBy` constraint.
	 *
	 * The schema capabilities the filter requests are recorded in the passed context - see
	 * {@link #createFormulaForTheFilter(QueryPlanningContext, Class, List, FilterBy, FilterBy, EntitySchemaContract,
	 * Supplier, boolean)}.
	 *
	 * @param queryContext            used for accessing global index, global cache, recording query telemetry and the
	 *                                capabilities the filter requests
	 * @param requestedScopes         the scopes the filter is planned in
	 * @param filterBy                the filter constraints the entities must match
	 * @param entityType              the entity type of the entity that is looked up
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 * @return output {@link Formula} that is able to produce the matching entity primary keys
	 */
	@Nonnull
	public static Formula createFormulaForTheFilter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull FilterBy filterBy,
		@Nonnull String entityType,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		return createFormulaForTheFilter(
			queryContext,
			requestedScopes,
			filterBy,
			null,
			entityType,
			stepDescriptionSupplier
		);
	}

	/**
	 * Method creates a new formula that looks for entity primary keys in global index of `entityType` collection that
	 * match the `filterBy` constraint.
	 *
	 * The filter is planned in every requested scope. An entity type that holds no entity of a scope has no index of
	 * that scope, and the part of the filter restricted to a scope with no index to look into would never be
	 * translated - an empty index in its place lets the translation check the filter against the entity schema in
	 * every requested scope exactly as it does when the entities exist, so that the query does not fail or pass
	 * depending on the data. The empty index matches nothing.
	 *
	 * @param queryContext            used for accessing global index, global cache and recording query telemetry
	 * @param requestedScopes         the scopes the filter is planned in
	 * @param filterBy                the filter constraints the entities must match
	 * @param rootFilterBy            the filter of the enclosing query, NULL when there is none
	 * @param entityType              the entity type of the entity that is looked up
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 * @return output {@link Formula} that is able to produce the matching entity primary keys
	 */
	@Nonnull
	public static Formula createFormulaForTheFilter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull FilterBy filterBy,
		@Nullable FilterBy rootFilterBy,
		@Nonnull String entityType,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		return createFormulaForTheFilter(
			queryContext,
			GlobalEntityIndex.class,
			// now analyze the filter by in a nested context with exchanged primary entity index
			getGlobalIndexesOrEmpty(queryContext, requestedScopes, entityType),
			filterBy,
			rootFilterBy,
			queryContext.getSchema(entityType),
			stepDescriptionSupplier
		);
	}

	/**
	 * Returns the global index of the entity type in every requested scope, or an empty one in a scope the entity type
	 * holds no entity of - see {@link #createFormulaForTheFilter(QueryPlanningContext, Set, FilterBy, FilterBy, String,
	 * Supplier)} for why a scope without data is planned as well.
	 *
	 * @param queryContext    used for accessing the global indexes
	 * @param requestedScopes the scopes the filter is planned in
	 * @param entityType      the entity type of the entity that is looked up
	 * @return one global index per requested scope
	 */
	@Nonnull
	private static List<GlobalEntityIndex> getGlobalIndexesOrEmpty(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Set<Scope> requestedScopes,
		@Nonnull String entityType
	) {
		return requestedScopes
			.stream()
			.map(
				scope -> queryContext.getGlobalEntityIndexIfExists(entityType, scope)
					.orElseGet(() -> GlobalEntityIndex.createEmptyIndex(entityType, scope))
			)
			.toList();
	}

	/**
	 * Method creates a new formula that looks for entity primary keys in global index of `entityType` collection that
	 * match the `filterBy` constraint.
	 *
	 * The filter is processed in the scopes of `indexesToUse` only, not in all scopes of the query: a constraint that
	 * discovers further indexes by the processing scopes would otherwise reach the entities of a scope the caller did
	 * not ask about.
	 *
	 * @param queryContext            used for accessing global index, global cache and recording query telemetry
	 * @param filterBy                the filter constraints the entities must match
	 * @param entitySchema            the entity schema of the entity that is looked up
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 * @return output {@link Formula} that is able to produce the matching entity primary keys
	 */
	@Nonnull
	public static <T extends EntityIndex> Formula createFormulaForTheFilter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Class<T> indexType,
		@Nonnull List<T> indexesToUse,
		@Nonnull FilterBy filterBy,
		@Nullable FilterBy rootFilterBy,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		return createFormulaForTheFilter(
			queryContext, indexType, indexesToUse, filterBy, rootFilterBy, entitySchema, stepDescriptionSupplier, false
		);
	}

	/**
	 * Method creates a new formula that looks for entity primary keys in the passed indexes that match the `filterBy`
	 * constraint - see {@link #createFormulaForTheFilter(QueryPlanningContext, Class, List, FilterBy, FilterBy,
	 * EntitySchemaContract, Supplier)} - by a visitor that only checks the filter when `constraintCheckOnly` is true
	 * (see {@link #isConstraintCheckOnly()}). A check of a nested filter passes true, so that the check stays one
	 * however deep the filter nests further reference constraints.
	 *
	 * The schema capabilities the filter requests are recorded in the passed context, whether the filter is only checked
	 * or evaluated, and whichever entity type it selects - the context counts each of them on the registry of the
	 * collection declaring it. The context counts them when it builds a plan; a caller translating a filter in a context
	 * that builds none - a check, or a filter no plan of its own evaluates - hands what the context accumulated to the
	 * query the filter belongs to. A filter translated in the context of the query itself (the parent filter of a
	 * `hierarchyWithin`, the filter of a segment, the group selector of a `histogramHaving`) needs no hand-over: the
	 * query counts it with the rest of its plan.
	 *
	 * @param queryContext            used for accessing global index, global cache and recording query telemetry
	 * @param indexType               the type of the indexes to use
	 * @param indexesToUse            the indexes the filter is translated over
	 * @param filterBy                the filter constraints the entities must match
	 * @param rootFilterBy            the filter of the enclosing query, NULL when there is none
	 * @param entitySchema            the entity schema of the entity that is looked up
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 * @param constraintCheckOnly     true when the filter is translated only to be checked and its formula is thrown
	 *                                away
	 * @return output {@link Formula} that is able to produce the matching entity primary keys
	 */
	@Nonnull
	public static <T extends EntityIndex> Formula createFormulaForTheFilter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull Class<T> indexType,
		@Nonnull List<T> indexesToUse,
		@Nonnull FilterBy filterBy,
		@Nullable FilterBy rootFilterBy,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull Supplier<String> stepDescriptionSupplier,
		boolean constraintCheckOnly
	) {
		final Formula theFormula;
		try {
			queryContext.pushStep(
				QueryPhase.PLANNING_FILTER_NESTED_QUERY,
				stepDescriptionSupplier
			);
			// create a visitor
			final FilterByVisitor theFilterByVisitor = new FilterByVisitor(
				createRootProcessingScope(queryContext, TargetIndexes.EMPTY),
				queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY,
				constraintCheckOnly
			);

			// now analyze the filter by in a nested context with exchanged primary entity index
			if (indexesToUse.isEmpty()) {
				return EmptyFormula.INSTANCE;
			} else {
				// the nested evaluation is processed in the scopes of the searched indexes only - the translators that
				// discover further indexes by the processing scopes (`referenceHaving`, `entityHaving`, unique lookups,
				// a nested hierarchy constraint) must not reach the entities of the other queried scopes
				final Set<Scope> indexScopes = EnumSet.noneOf(Scope.class);
				for (final T index : indexesToUse) {
					indexScopes.add(index.getIndexKey().scope());
				}
				theFormula = theFilterByVisitor.getProcessingScope().doWithScope(
					indexScopes,
					() -> queryContext.analyse(
						theFilterByVisitor.executeInContextAndIsolatedFormulaStack(
							indexType,
							() -> indexesToUse,
							null,
							entitySchema,
							null,
							// the filter is a part of the query whichever plan evaluates it, so it records what it names -
							// whether the context counts it or hands it over is up to the caller
							new AttributeSchemaAccessor(
								queryContext.getCatalogSchema(), entitySchema, null, queryContext
							),
							(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale)),
							() -> {
								// initialize root constraint for the execution
								if (rootFilterBy != null) {
									// we don't need to pop it, because the filter by visitor is going to be discarded
									getProcessingScope(theFilterByVisitor.scope).pushConstraint(rootFilterBy);
								}

								filterBy.accept(theFilterByVisitor);
								// get the result and clear the visitor internal structures
								return theFilterByVisitor.getFormulaAndClear();
							}
						)
					)
				);
			}
		} finally {
			queryContext.popStep();
		}
		return theFormula;
	}

	/**
	 * Creates a visitor that translates filters only to check them against the schemas and throws their formulas away.
	 * It reads no index holding data - see {@link #isConstraintCheckOnly()} - so it suits a check made while planning,
	 * before it is known whether there is any data the filter will be evaluated over.
	 *
	 * @param queryContext planning context of the query the checked filter belongs to
	 * @return a visitor checking filters only
	 */
	@Nonnull
	public static FilterByVisitor createConstraintCheckVisitor(@Nonnull QueryPlanningContext queryContext) {
		return new FilterByVisitor(
			createRootProcessingScope(queryContext, TargetIndexes.EMPTY),
			queryContext,
			Collections.emptyList(),
			TargetIndexes.EMPTY,
			true
		);
	}

	/**
	 * Creates the processing scope a visitor starts in: the indexes of the passed set, the scopes and the schema of
	 * the query, and the attributes of the queried entity itself.
	 *
	 * @param queryContext  planning context of the query
	 * @param indexSetToUse the indexes the filter is translated over
	 * @return the root processing scope
	 */
	@Nonnull
	private static <T extends Index<?>> ProcessingScope<T> createRootProcessingScope(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull TargetIndexes<T> indexSetToUse
	) {
		return new ProcessingScope<>(
			indexSetToUse.getIndexType(),
			indexSetToUse.getIndexes(),
			queryContext.getScopes(),
			AttributeContent.ALL_ATTRIBUTES,
			queryContext.isEntityTypeKnown() ? queryContext.getSchema() : null,
			null,
			new AttributeSchemaAccessor(queryContext),
			(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale))
		);
	}

	protected <T extends Index<?>> FilterByVisitor(
		@Nonnull ProcessingScope<T> processingScope,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes,
		@Nonnull TargetIndexes<T> indexSetToUse
	) {
		this(processingScope, queryContext, targetIndexes, indexSetToUse, false);
	}

	/**
	 * Creates a visitor starting in the passed processing scope.
	 *
	 * @param processingScope     the scope the visitor starts in
	 * @param queryContext        planning context of the query
	 * @param targetIndexes       all alternative index sets of the query
	 * @param indexSetToUse       the index set the filter is translated over
	 * @param constraintCheckOnly true when the visitor only checks the filters - see {@link #isConstraintCheckOnly()}
	 */
	private <T extends Index<?>> FilterByVisitor(
		@Nonnull ProcessingScope<T> processingScope,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes,
		@Nonnull TargetIndexes<T> indexSetToUse,
		boolean constraintCheckOnly
	) {
		this.stack.push(new LinkedList<>());
		this.postProcessors.push(new LinkedHashMap<>(16));
		this.scope.push(processingScope);
		this.queryContext = queryContext;
		//I just can't get generic to work here
		//noinspection unchecked,rawtypes
		this.targetIndexes = (List) targetIndexes;
		this.indexSetToUse = indexSetToUse;
		this.constraintCheckOnly = constraintCheckOnly;
	}

	public <T extends Index<?>> FilterByVisitor(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes,
		@Nonnull TargetIndexes<T> indexSetToUse
	) {
		this(
			createRootProcessingScope(queryContext, indexSetToUse), queryContext, targetIndexes, indexSetToUse, false
		);
	}

	/**
	 * Returns the computed formula that represents the filter query visited by this implementation.
	 */
	@Nonnull
	public Formula getFormula(@Nonnull FormulaPostProcessor... additionalPostProcessors) {
		return ofNullable(this.computedFormula)
			.map(formula -> this.constructFinalFormula(formula, additionalPostProcessors))
			.orElseGet(this::getSuperSetFormula);
	}

	/**
	 * Records a `histogramHaving` carrier resolved by {@link HistogramHavingTranslator} during this
	 * visitor's filter translation pass. Called once per `histogramHaving` constraint in the filter
	 * tree; alternative-plan re-walks (a fresh {@link FilterByVisitor} per
	 * {@link io.evitadb.core.query.indexSelection.TargetIndexes}) maintain their own independent
	 * registries, so duplicate appends are impossible.
	 *
	 * @param entry the fully resolved tuple describing the histogram slot and its `[from, to]` range
	 */
	public void registerResolvedHistogramHaving(@Nonnull ResolvedHistogramHaving entry) {
		this.resolvedHistogramHavings.add(entry);
	}

	/**
	 * Returns an unmodifiable view of all `histogramHaving` carriers resolved during this visitor's
	 * filter translation pass. Consumers filter the list by their own `(referenceName, histogramName)`
	 * tuple to locate the slot(s) they care about.
	 *
	 * @return the resolved carriers in document order; empty when the query has no `histogramHaving`
	 */
	@Nonnull
	public List<ResolvedHistogramHaving> getResolvedHistogramHavings() {
		return Collections.unmodifiableList(this.resolvedHistogramHavings);
	}

	/**
	 * Returns the computed formula that represents the filter query visited by this implementation.
	 * The computed formula is reset to NULL after this method is called so that the visitor instance can be reused.
	 */
	@Nonnull
	public Formula getFormulaAndClear() {
		final Formula result = ofNullable(this.computedFormula)
			.map(this::constructFinalFormula)
			.orElseGet(this::getSuperSetFormula);
		this.computedFormula = null;
		return result;
	}

	/**
	 * Returns the moment when the query is requested.
	 */
	@Nonnull
	public OffsetDateTime getNow() {
		return getEvitaRequest().getAlignedNow();
	}

	/**
	 * Method is expected to be used in {@link FilteringConstraintTranslator} to get collection of formulas
	 * in the current "level" of the filtering query.
	 */
	@Nonnull
	public Formula[] getCollectedFormulasOnCurrentLevel() {
		return ofNullable(this.stack.peek())
			.map(it -> it.toArray(Formula[]::new))
			.orElse(EMPTY_INTEGER_FORMULA);
	}

	/**
	 * Returns attribute definition from current scope.
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... requiredTrait
	) {
		return getProcessingScope()
			.getAttributeSchemaAccessor()
			.getAttributeSchema(attributeName, this.getProcessingScope().getScopes(), requiredTrait);
	}

	/**
	 * Returns attribute definition from current scope using provided entity schema.
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nonnull AttributeTrait... requiredTrait
	) {
		return getProcessingScope()
			.getAttributeSchemaAccessor()
			.getAttributeSchema(entitySchema, attributeName, this.getProcessingScope().getScopes(), requiredTrait);
	}

	/**
	 * Returns `true` when the currently translated constraint has **no** {@link UserFilter} ancestor in
	 * the processing stack — i.e. it is being translated outside any `userFilter` block. Used by
	 * price-filter translators to decide whether a
	 * {@link io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula} they're about
	 * to construct should collect the per-inner-record histogram side-output — the rule is "outer LP
	 * only", so an LP being built under `userFilter(priceBetween(...))` must remain un-flagged to avoid
	 * double-counting in the histogram bypass.
	 */
	public boolean isOutsideUserFilter() {
		for (FilterConstraint ancestor : getProcessingScope().getProcessedConstraintParents()) {
			if (ancestor instanceof UserFilter) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns `true` when the {@link io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula}
	 * being built by a price-filter translator should collect the per-inner-record histogram side-output.
	 *
	 * The side-output is required precisely when both conditions hold:
	 *
	 * - the planner asked for a `priceHistogram` extra result
	 *   (see {@link EvitaRequest#isPriceHistogramRequested()}), and
	 * - the current translation site is outside any `userFilter` block
	 *   (see {@link #isOutsideUserFilter()}) — the "outer LP only" rule, otherwise an LP built under
	 *   `userFilter(priceBetween(...))` would double-count inner records in the histogram bypass.
	 *
	 * Consolidates a predicate previously duplicated across every price-filter translator.
	 */
	public boolean isHistogramSideOutputApplicable() {
		return getEvitaRequest().isPriceHistogramRequested() && isOutsideUserFilter();
	}

	/**
	 * Method returns true if any of the siblings of the currently examined query matches any of the passed types.
	 */
	@SuppressWarnings("unchecked")
	public boolean isAnyConstraintPresentInConjunctionScopeExcludingUserFilter(@Nonnull Class<? extends FilterConstraint>... constraintType) {
		final ProcessingScope<?> theScope = getProcessingScope();
		final AtomicBoolean result = new AtomicBoolean();
		theScope.doInConjunctionBlock(theConstraint -> {
				if (Arrays.stream(constraintType).anyMatch(it -> it.isInstance(theConstraint))) {
					result.set(true);
				}
			},
			fc -> !(fc instanceof UserFilter) && CONJUNCTIVE_CONSTRAINTS.contains(fc.getClass())
		);
		return result.get();
	}

	/**
	 * Method will apply `lambda` in a parent scope of currently processed {@link FilterConstraint} that shares same
	 * conjunction relation. For example in this formula:
	 *
	 *
	 * <pre>
	 * AND
	 *    USER_FILTER
	 *       LESS_THAN()
	 *       IN_CURRENCY
	 *    OR
	 *       EQ()
	 *       GREATER_THAN()
	 * </pre>
	 *
	 * Conjunction block for `IN_CURRENCY` is: LESS_THAN, USER_FILTER, AND, OR
	 * Conjunction block for `USER_FILTER` is: AND,OR
	 * Conjunction block for `EQ` is none.
	 */
	@Nullable
	public <T extends FilterConstraint> T findInConjunctionTree(@Nonnull Class<T> constraintType) {
		final ProcessingScope<?> theScope = getProcessingScope();
		final List<T> foundConstraints = new LinkedList<>();
		theScope.doInConjunctionBlock(theConstraint -> {
			if (constraintType.isInstance(theConstraint)) {
				//noinspection unchecked
				foundConstraints.add((T) theConstraint);
			}
		});
		if (foundConstraints.isEmpty()) {
			return null;
		} else {
			return foundConstraints.getFirst();
		}
	}

	/**
	 * Returns the translator registered for the class of the constraint.
	 *
	 * @param constraintClass the class of the constraint
	 * @return the translator, NULL when none is registered for the class
	 */
	@Nullable
	public static FilteringConstraintTranslator<? extends FilterConstraint> getTranslator(
		@Nonnull Class<? extends FilterConstraint> constraintClass
	) {
		return TRANSLATORS.get(constraintClass);
	}

	/**
	 * Verifies that every attribute constraint of the registry has a translator that can create the per-value matcher
	 * of it, so a new attribute constraint cannot be registered without one. The constraints named by
	 * {@link #ATTRIBUTE_CONSTRAINTS_WITHOUT_VALUE_MATCHER} are the exceptions.
	 *
	 * @param translators the registry of the translators by the class of the constraint they translate
	 * @throws GenericEvitaInternalError when an attribute constraint is translated by a translator that does not
	 *                                   implement {@link AttributeValueMatchingTranslator}
	 */
	static void verifyAttributeTranslators(
		@Nonnull Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>>
			translators
	) {
		translators.forEach((constraintClass, translator) -> {
			if (AttributeConstraint.class.isAssignableFrom(constraintClass) &&
				!ATTRIBUTE_CONSTRAINTS_WITHOUT_VALUE_MATCHER.contains(constraintClass) &&
				!(translator instanceof AttributeValueMatchingTranslator<?>)
			) {
				throw new GenericEvitaInternalError(
					"The translator `" + translator.getClass().getName() + "` of the attribute constraint `" +
						constraintClass.getName() + "` does not implement `" +
						AttributeValueMatchingTranslator.class.getSimpleName() + "`, which every attribute " +
						"constraint translator must, unless the constraint is listed in " +
						"`ATTRIBUTE_CONSTRAINTS_WITHOUT_VALUE_MATCHER`."
				);
			}
		});
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final FilterConstraint filterConstraint = (FilterConstraint) constraint;

		final ProcessingScope<?> theScope = getProcessingScope();
		if (theScope.isSuppressed(filterConstraint.getClass())) {
			return;
		}

		final FilteringConstraintTranslator<FilterConstraint> translator =
			(FilteringConstraintTranslator<FilterConstraint>) TRANSLATORS.get(filterConstraint.getClass());
		isPremiseValid(
			translator != null,
			"No translator found for constraint `" + filterConstraint.getClass() + "`!"
		);

		try {
			theScope.pushConstraint(filterConstraint);
			final Formula constraintFormula;
			// if query is a container query
			if (filterConstraint instanceof ConstraintContainer) {
				@SuppressWarnings("unchecked") final ConstraintContainer<FilterConstraint> container = (ConstraintContainer<FilterConstraint>) filterConstraint;
				// initialize new level of the query
				this.stack.push(new LinkedList<>());
				if (!(translator instanceof SelfTraversingTranslator)) {
					// process children constraints
					for (FilterConstraint subConstraint : container) {
						subConstraint.accept(this);
					}
				}
				// process the container query itself
				constraintFormula = translator.translate(filterConstraint, this);
				// close the level
				this.stack.pop();
			} else if (filterConstraint instanceof ConstraintLeaf) {
				// process the leaf query
				constraintFormula = translator.translate(filterConstraint, this);
			} else {
				// sanity check only
				throw new GenericEvitaInternalError("Should never happen");
			}

			// if current query is FilterBy we know we're at the top of the filtering query tree
			if (filterConstraint instanceof FilterBy) {
				// so we can assign the result of the visitor
				this.computedFormula = constraintFormula;
			} else if (!(constraintFormula instanceof SkipFormula)) {
				// we add the formula to the current level in the query stack
				addFormula(constraintFormula);
			}
		} finally {
			theScope.popConstraint();
		}
	}

	/**
	 * Registers new {@link FormulaPostProcessor} to the list of processors that will be called just before
	 * IndexFilterByVisitor hands the result of its work to the calling logic.
	 */
	public <T extends FormulaPostProcessor> T registerFormulaPostProcessor(
		@Nonnull Class<T> postProcessorType,
		@Nonnull Supplier<T> formulaPostProcessorSupplier
	) {
		final LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> postProcessors = getPostProcessors();
		final FormulaPostProcessor existingPP = postProcessors.get(postProcessorType);
		if (existingPP == null) {
			final T pp = formulaPostProcessorSupplier.get();
			postProcessors.put(postProcessorType, pp);
			return pp;
		} else {
			//noinspection unchecked
			return (T) existingPP;
		}
	}

	/**
	 * Registers new {@link FormulaPostProcessor} to the list of processors that will be called just before
	 * IndexFilterByVisitor hands the result of its work to the calling logic.
	 */
	public <T extends FormulaPostProcessor> T registerFormulaPostProcessorBefore(
		@Nonnull Class<T> postProcessorType,
		@Nonnull Supplier<T> formulaPostProcessorSupplier,
		@Nonnull Class<? extends FormulaPostProcessor> beforeProcessorType
	) {
		final LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> postProcessors = getPostProcessors();
		final FormulaPostProcessor existingPP = postProcessors.get(postProcessorType);
		if (existingPP == null) {
			final T pp = formulaPostProcessorSupplier.get();
			final FormulaPostProcessor beforePP = postProcessors.remove(beforeProcessorType);
			postProcessors.put(postProcessorType, pp);
			if (beforePP != null) {
				postProcessors.put(beforeProcessorType, beforePP);
			}
			return pp;
		} else {
			//noinspection unchecked
			return (T) existingPP;
		}
	}

	/**
	 * Registers new {@link FormulaPostProcessor} to the list of processors that will be called just before
	 * IndexFilterByVisitor hands the result of its work to the calling logic.
	 */
	public <T extends FormulaPostProcessor> T registerFormulaPostProcessorAfter(
		@Nonnull Class<T> postProcessorType,
		@Nonnull Supplier<T> formulaPostProcessorSupplier,
		@Nonnull Class<? extends FormulaPostProcessor> afterProcessorType
	) {
		final LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> postProcessors = getPostProcessors();
		final FormulaPostProcessor existingPP = postProcessors.get(postProcessorType);
		if (existingPP == null) {
			final T pp = formulaPostProcessorSupplier.get();
			final FormulaPostProcessor afterPP = postProcessors.remove(afterProcessorType);
			if (afterPP != null) {
				postProcessors.put(afterProcessorType, afterPP);
			}
			postProcessors.put(postProcessorType, pp);
			return pp;
		} else {
			//noinspection unchecked
			return (T) existingPP;
		}
	}

	/**
	 * Retrieves a map of post processors, ensuring that it is not null.
	 *
	 * @return a LinkedHashMap of post processor classes to their corresponding instances
	 */
	@Nonnull
	private LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> getPostProcessors() {
		final LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> postProcessors = this.postProcessors.peek();
		Assert.isPremiseValid(postProcessors != null, "Post processors should never be null!");
		return postProcessors;
	}

	/**
	 * Returns extension of {@link ProcessingScope} that is set for current context.
	 *
	 * @see #executeInContext(Class, Supplier, EntityContentRequire, EntitySchemaContract, ReferenceSchemaContract, NegationResolution, AttributeSchemaAccessor, TriFunction, Supplier, Class[])
	 */
	@Nonnull
	public ProcessingScope<? extends Index<?>> getProcessingScope() {
		return getProcessingScope(this.scope);
	}

	/**
	 * Retrieves the current processing scope from the provided deque.
	 *
	 * @param scopeDeque A deque representing the stack of processing scopes.
	 *                   This deque must not be empty.
	 * @return The current processing scope from the deque.
	 * @throws GenericEvitaInternalError if the deque is empty.
	 */
	@Nonnull
	private static ProcessingScope<? extends Index<?>> getProcessingScope(@Nonnull Deque<ProcessingScope<? extends Index<?>>> scopeDeque) {
		final ProcessingScope<? extends Index<?>> processingScope;
		if (scopeDeque.isEmpty()) {
			throw new GenericEvitaInternalError("Scope should never be empty");
		} else {
			processingScope = scopeDeque.peek();
			isPremiseValid(processingScope != null, "Scope could never be null!");
		}
		return processingScope;
	}

	/**
	 * Discovers which reduced indexes could answer the passed `referenceHaving` WITHOUT resolving them into
	 * index objects.
	 *
	 * The expensive half of answering a `referenceHaving` is the resolution: one index object per partition the
	 * reference advertises, which on a production catalog runs to six figures and dominates the cost of planning a
	 * bare `referenceHaving`. The cheap half - computing which partition primary keys qualify - is a single formula
	 * evaluation, and it is enough to answer how many candidates there are, which is what index selection needs to
	 * decide whether the candidate is worth planning at all.
	 *
	 * The split exists so a candidate that is going to be rejected never pays the resolution. A route that reads the
	 * candidates calls {@link ReducedIndexCandidates#resolveSplit()}, which keeps a registered single-owner
	 * partition as a row of the split instead of resolving it; a route that needs the index objects of every candidate
	 * calls {@link ReducedIndexCandidates#resolve()} instead.
	 *
	 * A missing referenced type index raises, and the nested query of an {@link EntityHaving} inside
	 * `referenceHaving` is planned over the whole referenced collection.
	 *
	 * @param referenceHaving the constraint whose reference is being discovered
	 * @param scope           scopes to discover in
	 * @return the candidate partitions and the means to resolve them
	 */
	@Nonnull
	public ReducedIndexCandidates getReferencedRecordEntityIndexCandidates(
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull Set<Scope> scope
	) {
		final ReferenceSchemaContract referenceSchema = resolveReferenceSchema(
			getProcessingScope().getEntitySchema(), referenceHaving
		);
		final ProcessingScope<? extends Index<?>> processingScope = getProcessingScope();
		final EntitySchemaContract entitySchema = Objects.requireNonNull(processingScope.getEntitySchema());

		final Formula reducedIndexPksFormula = processingScope.doWithScope(
			scope,
			() -> getReducedIndexPrimaryKeyFormula(
				entitySchema,
				referenceSchema,
				new FilterBy(referenceHaving.getChildren()),
				EntityIndexType.REFERENCED_ENTITY_TYPE,
				// every consumer of this method re-evaluates the body against each candidate afterwards
				NegationResolution.PER_ROW
			)
		);

		final IntFunction<ReferencePartition> partitionAccessor;
		if (this.getSchema().getName().equals(entitySchema.getName())) {
			// the queried collection - its index snapshot, leniently; a miss is caught by `resolve`
			partitionAccessor = this::resolvePartitionIfExists;
		} else {
			// another collection - its live indexes (dirty buffer first), leniently; a miss is caught by `resolve`
			final EntityCollection targetCollection = getEntityCollectionOrThrowException(
				entitySchema.getName(),
				"locate referenced record entity indexes"
			);
			partitionAccessor = targetCollection::resolvePartitionIfExists;
		}
		// the split reads the collection the candidates come from in every scope they were discovered in
		final String entityType = entitySchema.getName();
		final String referenceName = referenceSchema.getName();
		final Scope[] scopes = scope.toArray(Scope[]::new);
		final QueryPlanningContext theQueryContext = this.queryContext;
		final Function<Bitmap, ReferencePartitionSplit> splitter =
			candidates -> theQueryContext.splitPartitionsForRead(entityType, referenceName, candidates, scopes);
		// a candidate without an index object may be a row the reference left in the registry while it was indexed
		// for filtering only - the registry is consulted only then, so a candidate with an index object costs nothing
		final IntConsumer missingPartitionGuard = partitionPrimaryKey -> {
			final ReferencePartitionSplit split = splitter.apply(new BaseBitmap(partitionPrimaryKey));
			if (split.getVirtualCount() > 0) {
				final Scope rowScope = split.getVirtualTypeIndex(0).getIndexKey().scope();
				if (!VirtualReferencePartitionWriter.isWrittenVirtually(referenceSchema, rowScope)) {
					throw ReferencePartitionReindexRequiredException.forQuery(entityType, referenceName, rowScope);
				}
			}
		};

		return new ReducedIndexCandidates(
			reducedIndexPksFormula.compute(), partitionAccessor, splitter, missingPartitionGuard
		);
	}

	/**
	 * The reduced indexes that could answer one `referenceHaving`, named but not yet resolved.
	 *
	 * Every member is immutable and is captured at discovery time, which is what makes deferring the resolution safe:
	 * {@link #resolve()} reads nothing that could have moved on since, so a caller that resolves later in the same query
	 * gets the list discovery would have produced, and {@link #resolveSplit()} reads the registry of the query's own
	 * snapshot.
	 *
	 * @param primaryKeys           primary keys of the candidate reduced indexes
	 * @param partitionAccessor     resolves one candidate primary key into its partition, as an index object, `null`
	 *                              when it has none
	 * @param splitter              splits candidate primary keys by representation - see
	 *                              {@link QueryPlanningContext#splitPartitionsForRead}
	 * @param missingPartitionGuard receives a candidate primary key {@link #resolve()} found no index object for, and
	 *                              refuses the query with {@link ReferencePartitionReindexRequiredException} when the
	 *                              candidate is a registry row of a reference indexed for partitioning since the row
	 *                              was written - a row holds nothing of what that indexing keeps in an index object
	 */
	public record ReducedIndexCandidates(
		@Nonnull Bitmap primaryKeys,
		@Nonnull IntFunction<ReferencePartition> partitionAccessor,
		@Nonnull Function<Bitmap, ReferencePartitionSplit> splitter,
		@Nonnull IntConsumer missingPartitionGuard
	) {

		/**
		 * Returns how many reduced indexes qualify, without resolving any of them.
		 *
		 * @return the candidate count
		 */
		public int size() {
			return this.primaryKeys.size();
		}

		/**
		 * Resolves every candidate into the index object the query body channel consumes - for the routes that need
		 * the object of every candidate. A route that reads virtual partitions calls {@link #resolveSplit()} instead.
		 *
		 * @return the resolved indexes, in candidate primary key order
		 * @throws io.evitadb.exception.GenericEvitaInternalError when a candidate primary key resolves to no partition
		 */
		@Nonnull
		public List<ReducedEntityIndex> resolve() {
			return resolve(this.primaryKeys);
		}

		/**
		 * Splits the candidates by representation and resolves only those read through their reduced entity indexes.
		 * Every candidate the registry of single-owner partitions holds a row for stays a row of the split and is
		 * never resolved to its index object; when the registry holds none of them the split holds no virtual
		 * candidate and the indexes are those {@link #resolve()} returns.
		 *
		 * The split costs one merge of the candidates with the registry rows (see
		 * {@link io.evitadb.index.ReferencePartitions#split}); the resolution costs what {@link #resolve()} costs for
		 * the materialized candidates alone.
		 *
		 * @return the materialized candidates resolved and the split carrying the virtual ones
		 * @throws io.evitadb.exception.GenericEvitaInternalError when a materialized candidate resolves to no partition
		 */
		@Nonnull
		public SplitReducedIndexCandidates resolveSplit() {
			return resolveSplit(this::resolve);
		}

		/**
		 * Splits the candidates by representation as {@link #resolveSplit()} does, taking the index objects of all
		 * candidates from the given supplier when no candidate is read virtually - so a caller already holding them,
		 * memoized in a {@link io.evitadb.core.query.indexSelection.TargetIndexes}, does not resolve them twice.
		 *
		 * @param allIndexes supplies the resolved indexes of every candidate, as {@link #resolve()} returns them
		 * @return the materialized candidates resolved and the split carrying the virtual ones
		 * @throws io.evitadb.exception.GenericEvitaInternalError when a materialized candidate resolves to no partition
		 */
		@Nonnull
		public SplitReducedIndexCandidates resolveSplit(@Nonnull Supplier<List<ReducedEntityIndex>> allIndexes) {
			final ReferencePartitionSplit split = this.splitter.apply(this.primaryKeys);
			return new SplitReducedIndexCandidates(
				split.getVirtualCount() == 0 ? allIndexes.get() : resolve(split.getMaterializedPrimaryKeys()),
				split
			);
		}

		/**
		 * Resolves the given candidates into the index objects the query body channel consumes.
		 *
		 * @param candidates the candidate primary keys to resolve
		 * @return the resolved indexes, in candidate primary key order
		 */
		@Nonnull
		private List<ReducedEntityIndex> resolve(@Nonnull Bitmap candidates) {
			final List<ReducedEntityIndex> result = new ArrayList<>(candidates.size());
			final OfInt it = candidates.iterator();
			while (it.hasNext()) {
				final int partitionPk = it.nextInt();
				final ReferencePartition partition = this.partitionAccessor.apply(partitionPk);
				if (partition == null) {
					// a row of a reference indexed for partitioning since it was written refuses the query, any other
					// miss is a defect reported below
					this.missingPartitionGuard.accept(partitionPk);
				}
				// supplier, not concatenation - this loop runs once per reduced index, which is once per
				// referenced entity of the whole collection
				Assert.isPremiseValid(
					partition != null,
					() -> "Reference partition with primary key " + partitionPk + " was unexpectedly not found!"
				);
				result.add(ReferencePartitions.toReducedEntityIndex(partition));
			}
			return result;
		}

	}

	/**
	 * The candidates of one `referenceHaving` split by representation - see
	 * {@link ReducedIndexCandidates#resolveSplit()}.
	 *
	 * @param materializedIndexes the candidates read through their reduced entity indexes, resolved, in candidate
	 *                            primary key order
	 * @param split               the split the virtual candidates are read from; its virtual candidates are absent
	 *                            from `materializedIndexes`
	 */
	public record SplitReducedIndexCandidates(
		@Nonnull List<ReducedEntityIndex> materializedIndexes,
		@Nonnull ReferencePartitionSplit split
	) {
	}

	/**
	 * Returns primary keys of the entities of the target entity type matching the passed filter - the filter planned
	 * as a nested query against the global index of the target collection in every passed scope, the answers of the
	 * scopes united. The filter names the attributes of the target entity, so only a nested query over its collection
	 * can answer it, exactly as {@link HavingTranslatorHelper#planNestedQuery} answers it for an `entityHaving` or a
	 * `groupHaving` reached through the ordinary filter translation.
	 *
	 * A visitor that only checks the filter ({@link #isConstraintCheckOnly()}) plans no nested query: the filter is
	 * checked against the target schema in the scopes the nested query would use, the schema capabilities the check
	 * requested are handed to the context of this visitor, and the result is empty.
	 *
	 * @param targetEntityType        the entity type whose entities the filter selects
	 * @param filter                  the filter to evaluate, unwrapped from its `entityHaving` or `groupHaving`
	 *                                container
	 * @param scopes                  the scopes to evaluate in
	 * @param taskDescriptionSupplier supplier of the description of the nested query for the telemetry and the messages
	 * @return primary keys of the matching target entities
	 */
	@Nonnull
	public Bitmap getMatchingEntityPrimaryKeys(
		@Nonnull String targetEntityType,
		@Nonnull FilterConstraint filter,
		@Nonnull Set<Scope> scopes,
		@Nonnull Supplier<String> taskDescriptionSupplier
	) {
		final Formula matchingFormula = getProcessingScope().doWithScope(
			scopes,
			() -> FormulaFactory.or(
				HavingTranslatorHelper.planNestedQuery(targetEntityType, filter, this, taskDescriptionSupplier)
					.stream()
					.map(GlobalIndexAndFormula::filter)
					.toArray(Formula[]::new)
			)
		);

		// the result is consumed during reference fetching, which runs after the planning phase
		matchingFormula.initialize(getInternalExecutionContext());
		final Bitmap matchingPks = matchingFormula.compute();
		return matchingPks.isEmpty() ? EmptyBitmap.INSTANCE : matchingPks;
	}

	/**
	 * Resolves the {@link ReferenceSchemaContract} for the given {@link ReferenceHaving} constraint
	 * using the current processing scope's entity schema.
	 *
	 * @param entitySchema    the entity schema to resolve the reference from
	 * @param referenceHaving the constraint identifying the reference by name
	 * @return the resolved reference schema
	 * @throws ReferenceNotFoundException if the reference is not found in the entity schema
	 */
	@Nonnull
	private static ReferenceSchemaContract resolveReferenceSchema(
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull ReferenceHaving referenceHaving
	) {
		final String referenceName = referenceHaving.getReferenceName();
		return ofNullable(entitySchema)
			.flatMap(it -> it.getReference(referenceName))
			.orElseThrow(() -> entitySchema == null
				? new ReferenceNotFoundException(referenceName)
				: new ReferenceNotFoundException(referenceName, entitySchema));
	}

	/**
	 * Returns bitmap of primary keys ({@link EntityContract#getPrimaryKey()}) of referenced entities that satisfy
	 * the passed filtering constraint.
	 *
	 * @param entitySchema    that identifies the examined entities
	 * @param referenceSchema that identifies the examined entities
	 * @param filterBy        the filtering constraint to satisfy
	 * @return bitmap with referenced entity ids
	 */
	@Nonnull
	public Formula getReferencedRecordIdFormula(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterBy filterBy
	) {
		final Formula reducedEntityIndexPrimaryKeys = getReducedIndexPrimaryKeyFormula(
			entitySchema, referenceSchema, filterBy,
			EntityIndexType.REFERENCED_ENTITY_TYPE,
			// this formula IS the answer - it is computed right here and translated straight into referenced
			// entity primary keys, with nothing re-examining the rows afterwards
			NegationResolution.IN_PLACE
		);
		// we need to translate entity index primary keys to referenced entity primary keys - a partition read
		// virtually names its target in its registry row, so only the others are resolved to their index objects
		final ReferencePartitionSplit split = splitPartitionsForRead(
			entitySchema.getName(), referenceSchema.getName(), reducedEntityIndexPrimaryKeys.compute(),
			getProcessingScope().getScopes().toArray(Scope[]::new)
		);
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final OfInt it = split.getMaterializedPrimaryKeys().iterator();
		while (it.hasNext()) {
			writer.add(resolvePartition(it.nextInt()).getReferenceKey().primaryKey());
		}
		final int virtualCount = split.getVirtualCount();
		for (int i = 0; i < virtualCount; i++) {
			writer.add(split.getVirtualTargetPrimaryKey(i));
		}
		final PersistentRoaringBitmap bitmap = writer.get();
		return bitmap.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(bitmap));
	}

	/**
	 * Answers a reference body exactly for rows of registered single-owner partitions of one scope and returns the
	 * partition primary keys of the rows that satisfy it.
	 *
	 * The body is evaluated once against the referenced-type entity index holding the rows, with the rows installed on
	 * its processing scope (see {@link VirtualPartitionRows}) and every negation settled in place
	 * ({@link NegationResolution#IN_PLACE}). A single-owner partition holds exactly one row, so the type-level answer
	 * of the attribute comparisons, of `attributeIs`, of `entityHaving` and of a subtraction is the per-partition one;
	 * the leaves where the two differ answer the installed rows instead. Intersected with the rows, the result is the
	 * set of partitions whose single row satisfies the body - the answer their reduced entity indexes give.
	 *
	 * Costs one evaluation of the body against the referenced-type entity index - the evaluation the candidate
	 * discovery already performs, settled in place - plus the leaf overrides, each one pass over the rows at most.
	 *
	 * @param entitySchema            the schema of the entity owning the reference
	 * @param referenceSchema         the reference
	 * @param filterBy                the body
	 * @param rows                    the rows the body is answered for
	 * @param attributeSchemaAccessor resolves the attributes of the body - those of the reference
	 * @return the partition primary keys of the rows satisfying the body, possibly empty
	 */
	@Nonnull
	public Bitmap getMatchingVirtualPartitions(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterBy filterBy,
		@Nonnull VirtualPartitionRows rows,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor
	) {
		// only `facetHaving` sets the expansion function, and it never reads virtual rows - a set one here would
		// widen the type-level answer the rows are read from
		Assert.isPremiseValid(
			getProcessingScope().getReferencedEntityExpansionFunction() == null,
			"Virtual partition rows cannot be answered with a referenced entity expansion function!"
		);
		final String referenceName = referenceSchema.getName();
		final ReferencedTypeEntityIndex typeIndex = rows.getTypeIndex();
		final Formula matching = executeInContextAndIsolatedFormulaStack(
			ReferencedTypeEntityIndex.class,
			() -> Collections.singletonList(typeIndex),
			ReferenceContent.ALL_REFERENCES,
			entitySchema,
			referenceSchema,
			// the formula is the answer for every row - nothing re-examines the rows afterwards
			NegationResolution.IN_PLACE,
			attributeSchemaAccessor,
			(theEntity, attributeName, locale) ->
				theEntity.getReferences(referenceName)
					.stream()
					.map(it -> it.getAttributeValue(attributeName, locale)),
			() -> getProcessingScope().doWithVirtualPartitionRows(
				rows,
				() -> {
					filterBy.accept(this);
					return getFormulaAndClear();
				}
			),
			FacetIncludingChildren.class,
			FacetIncludingChildrenExcept.class
		);
		matching.initialize(this.getInternalExecutionContext());
		final Bitmap matchingPartitions = matching.compute();
		if (matchingPartitions.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		// the complement of a negation spans every partition of the type index, so only the rows are kept
		final PersistentRoaringBitmap result = PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(matchingPartitions),
			RoaringBitmapBackedBitmap.getRoaringBitmap(rows.getPartitionPrimaryKeys())
		);
		return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

	/**
	 * Shared implementation for evaluating a filter against a reference type-level index. The `indexType`
	 * parameter controls whether entity-level ({@link EntityIndexType#REFERENCED_ENTITY_TYPE}) or
	 * group-level ({@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE}) indexes are used.
	 *
	 * The filter is translated in every scope to look up, also in a scope where the reference is indexed but no
	 * entity references anything by it, so there is no type index - an empty index stands in for it, the filter is
	 * checked against the reference schema there, and the scope contributes nothing to the result.
	 *
	 * @param entitySchema                       the schema of the entity being queried
	 * @param referenceSchema                    the reference schema
	 * @param filterBy                           the filter constraint to evaluate
	 * @param indexType                          the type of reference index to use
	 * @param negationResolution                 where a negation inside `filterBy` is settled; PER_ROW lets it
	 *                                           widen to the super set because the caller re-examines every row
	 *                                           afterwards, IN_PLACE keeps it a real subtraction - see
	 *                                           {@link NegationResolution}
	 * @return formula computing matching index primary keys
	 */
	@Nonnull
	private Formula getReducedIndexPrimaryKeyFormula(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterBy filterBy,
		@Nonnull EntityIndexType indexType,
		@Nonnull NegationResolution negationResolution
	) {
		final String referenceName = referenceSchema.getName();
		final Set<Scope> scopesToLookUp = this.getProcessingScope().getScopes();
		final Set<Scope> queriedScopes = this.queryContext.getScopes();
		final Formula resultFormula = FormulaFactory.or(
			scopesToLookUp
				.stream()
				.map(scope -> {
					// a scope indexed without the entity component has no type index, and the branch below would answer
					// it with an empty formula - indistinguishable from a scope that simply holds no rows. Only a scope
					// the query returns rows from is refused: a scope looked up for another reason holds no owners
					// the query returns, so an empty answer from it loses none of them
					if (indexType == EntityIndexType.REFERENCED_ENTITY_TYPE && queriedScopes.contains(scope)) {
						HavingTranslatorHelper.assertEntityComponentIndexed(
							entitySchema, referenceSchema, scope, queriedScopes
						);
					}
					final EntityIndexKey entityIndexKey = new EntityIndexKey(indexType, scope, referenceName);
					final Optional<ReferencedTypeEntityIndex> entityIndex = getEntityIndex(
						entitySchema.getName(),
						entityIndexKey,
						ReferencedTypeEntityIndex.class
					);

					ReferencedTypeEntityIndex targetReferencedTypeIndex;
					// a visitor that only checks the filter reads no type index either - the empty one stands in for
					// an existing index as well, and the formula over it is thrown away like the one below
					final boolean referencesMissing = (entityIndex.isEmpty() || this.constraintCheckOnly) &&
						referenceSchema.isIndexedInScope(scope);
					if (referencesMissing) {
						// no entity of the scope references anything by the reference, so there is no type index -
						// an empty one in its place lets the translation check the filter against the reference
						// schema exactly as it does when the references exist, so that the query does not fail or
						// pass depending on the data; nothing can match there, whatever the filter says
						targetReferencedTypeIndex = new ReferencedTypeEntityIndex(
							-1, entitySchema.getName(), entityIndexKey
						);
					} else if (entityIndex.isEmpty()) {
						// we need to behave like if the index existed - we never know where in the filtering
						// constraint the client might have used InScope container limiting the scope of the query
						// a stub that throws ReferenceNotIndexedException from every method it does not handle, so that
						// a filter reaching into a scope which does not index the reference is refused rather than
						// answered with nothing
						targetReferencedTypeIndex = ReferencedTypeEntityIndex.createThrowingStub(entitySchema, entityIndexKey);
					} else {
						targetReferencedTypeIndex = entityIndex.get();
					}

					final Formula scopeFormula = executeInContextAndIsolatedFormulaStack(
						ReferencedTypeEntityIndex.class,
						() -> Collections.singletonList(targetReferencedTypeIndex),
						ReferenceContent.ALL_REFERENCES,
						entitySchema,
						referenceSchema,
						negationResolution,
						getProcessingScope().withReferenceSchemaAccessor(referenceSchema.getName()),
						(theEntity, attributeName, locale) ->
							theEntity.getReferences(referenceName)
								.stream()
								.map(it -> it.getAttributeValue(attributeName, locale)),
						() -> {
							filterBy.accept(this);
							return getFormulaAndClear();
						},
						FacetIncludingChildren.class,
						FacetIncludingChildrenExcept.class
					);
					return referencesMissing ? EmptyFormula.INSTANCE : scopeFormula;
				})
				.filter(it -> it != EmptyFormula.INSTANCE)
				.toArray(Formula[]::new)
		);

		// we need to initialize formula here, because the result will be needed in internal phase
		resultFormula.initialize(this.getInternalExecutionContext());

		return resultFormula;
	}

	/**
	 * Returns stream of indexes that should be all considered for record lookup.
	 *
	 * With several scopes in play the indexes come in the order `scope(...)` requested the scopes in, which is what
	 * lets the first-match lookups ({@link #applyOnFirstUniqueIndex}) prefer the scope listed first.
	 */
	@Nonnull
	public Stream<EntityIndex> getEntityIndexStream() {
		final ProcessingScope<? extends Index<?>> processingScope = getProcessingScope();
		final Set<Scope> allowedScopes = processingScope.getScopes();
		// when the processing scope carries partitions every element is one, so an element that is not an entity
		// index would be a partition this stream silently lost - it is a premise there, and a legitimate filter
		// elsewhere
		final Predicate<Index<?>> isEntityIndex =
			ReferencePartition.class.isAssignableFrom(processingScope.getIndexType()) ?
				FilterByVisitor::requirePartitionIsEntityIndex : EntityIndex.class::isInstance;
		if (allowedScopes.isEmpty()) {
			return Stream.empty();
		} else if (allowedScopes.size() == 1) {
			// hoisted out of the filter lambda - this stream is walked once per reduced entity index, i.e. once
			// per referenced entity of the whole collection, and the lambda allocated a fresh Iterator per element
			final Scope theOnlyScope = allowedScopes.iterator().next();
			return processingScope.getIndexStream()
				.filter(ix -> ix.getIndexKey().scope() == theOnlyScope)
				.filter(isEntityIndex)
				.map(EntityIndex.class::cast);
		} else {
			return Arrays.stream(this.queryContext.getEvitaRequest().getScopesAsArray())
				.flatMap(theScope -> processingScope.getIndexStream().filter(ix -> ix.getIndexKey().scope() == theScope))
				.filter(isEntityIndex)
				.map(EntityIndex.class::cast);
		}
	}

	/**
	 * Asserts that an element of a processing scope whose index type is a {@link ReferencePartition} is an entity
	 * index - every partition such a scope carries is one, and one that is not must fail loudly rather than drop
	 * out of the stream.
	 *
	 * @param index the element of the processing scope
	 * @return always `true`
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the element is not an entity index
	 */
	private static boolean requirePartitionIsEntityIndex(@Nonnull Index<?> index) {
		isPremiseValid(
			index instanceof EntityIndex,
			() -> "The processing scope over partitions carries `" + index.getClass() + "`, which is not an entity index!"
		);
		return true;
	}

	/**
	 * Returns TRUE when the query contains constraint that targets the reference reduced index. In other
	 * words - when {@link ReferenceHaving} constraint is part of the query. This situation must be taken into an
	 * account in hierarchy translators.
	 */
	public boolean isReferenceNotQueriedByOtherConstraints() {
		return this.targetIndexes.stream()
			.noneMatch(it -> it.getRepresentedConstraint() instanceof ReferenceHaving);
	}

	/**
	 * Returns the partitions of the reference that point to any of the targets in each scope of the processing scope
	 * (the default scope when none is set), split by representation - see
	 * {@link QueryPlanningContext#resolvePartitionsOfTargetsForRead}. This is how a hierarchy constraint nested where
	 * index selection does not reach finds the owners of its nodes: the registered single-owner partitions of the nodes
	 * contribute their owners read out of the registry, the rest their index objects.
	 *
	 * @param entitySchema       the entity schema owning the reference
	 * @param referenceSchema    the reference whose partitions of the targets are returned
	 * @param targetPrimaryKeys  the primary keys of the target entities
	 * @return the partitions of the targets split by representation
	 * @throws io.evitadb.core.exception.ReferenceComponentNotIndexedException when a processing scope indexes the
	 *         reference without the `REFERENCED_ENTITY` component
	 */
	@Nonnull
	public SplitTargetPartitions getReferencedEntityPartitions(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Bitmap targetPrimaryKeys
	) {
		final Set<Scope> scopes = getProcessingScope().getScopes();
		return getQueryContext().resolvePartitionsOfTargetsForRead(
			entitySchema, referenceSchema, scopes.isEmpty() ? EnumSet.of(Scope.DEFAULT_SCOPE) : scopes,
			targetPrimaryKeys
		);
	}

	/**
	 * Returns the target's partitions of the reference in each scope of the processing scope (the default scope
	 * when none is set), as index objects. The stream never contains `null`.
	 *
	 * @param entitySchema       the entity schema owning the reference
	 * @param referenceSchema    the reference whose partitions of the target are returned
	 * @param referencedEntityId the primary key of the target entity
	 * @return the target's partitions, one per processing scope that holds any
	 * @throws io.evitadb.core.exception.ReferenceComponentNotIndexedException when a processing scope indexes the
	 *         reference without the `REFERENCED_ENTITY` component, propagated from
	 *         {@link io.evitadb.core.query.QueryPlanningContext#resolvePartitionsOfTarget}
	 */
	@Nonnull
	public Stream<AbstractReducedEntityIndex> getReferencedEntityIndexes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int referencedEntityId
	) {
		final Set<Scope> scopes = getProcessingScope().getScopes();
		return (scopes.isEmpty() ? Stream.of(Scope.DEFAULT_SCOPE) : scopes.stream())
			.flatMap(
				scope -> Arrays.stream(
					getQueryContext().resolvePartitionsOfTarget(entitySchema, referenceSchema, scope, referencedEntityId)
				)
			)
			// the consumers read the partitions as indexes of the query body channel
			.<AbstractReducedEntityIndex>map(ReferencePartitions::toReducedEntityIndex);
	}

	/**
	 * Returns {@link EntityIndex} that contains indexed entities whose references have
	 * group entity `referenceName` and `groupEntityId`. This method mirrors
	 * {@link #getReferencedEntityIndexes(EntitySchemaContract, ReferenceSchemaContract, int)} but uses
	 * group-specific index types.
	 */
	@Nonnull
	public Stream<AbstractReducedEntityIndex> getReferencedGroupEntityIndexes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int groupEntityId
	) {
		final Set<Scope> scopes = getProcessingScope().getScopes();
		return (scopes.isEmpty() ? Stream.of(Scope.DEFAULT_SCOPE) : scopes.stream())
			.flatMap(
				scope -> getQueryContext().getReducedGroupEntityIndexes(
					scope, groupEntityId, entitySchema, referenceSchema, NO_OP_MISSING_RGEI_SUPPLIER
				)
			);
	}

	/**
	 * Returns super-set formula - i.e. formula that contains all record ids from the filter visitor chooses the result
	 * from.
	 *
	 * @return super-set formula
	 */
	@Nonnull
	public Formula getSuperSetFormula() {
		return getProcessingScope().getSuperSetFormula();
	}

	/**
	 * Generates a superset formula containing all relevant primary keys for the indices that fall within the specified
	 * scope. This method tries to find first {@link TargetIndexes} set that contain at least one index that falls within
	 * the specified scope, combines all primary keys from this set of indexes and returns the resulting formula.
	 *
	 * It tries to optimize formula in a way, that it uses the formula with the lowest estimated cost. A set reading
	 * some of its partitions from registry rows is never consulted - see {@link #holdsIndexObjectsOnly(TargetIndexes)}.
	 *
	 * A query naming no collection searches the catalog index, which holds no set of all the entities of a scope, so
	 * its superset of the scope is the entities its lookups found that live in the scope, told apart on their prefetched
	 * bodies - an empty formula would make an `inScope` container answer nothing.
	 *
	 * @param scope the scope within which to filter and compute the superset formula
	 * @return the resulting superset formula combining primary keys of the relevant indices;
	 *         returns an EmptyFormula instance if no relevant indices are found.
	 */
	@Nonnull
	public Formula getSuperSetFormula(@Nonnull Scope scope) {
		if (!isEntityTypeKnown()) {
			return new EntityFilteringFormula(
				"the entities of the scope `" + scope + "`", new ScopeEntityToBitmapFilter(scope)
			);
		}
		final ProcessingScope<? extends Index<?>> processingScope = getProcessingScope();
		final boolean indexesInScopeMatch = processingScope.getIndexStream().anyMatch(it -> it.getIndexKey().scope().equals(scope));
		// prefer indexes i current scope (this is required for ReferencedEntityFetcher and index selection)
		if (indexesInScopeMatch) {
			return FormulaFactory.or(
				processingScope.getIndexStream()
					.filter(index -> index.getIndexKey().scope().equals(scope))
					.filter(EntityIndex.class::isInstance)
					.map(EntityIndex.class::cast)
					.map(EntityIndex::getAllPrimaryKeysFormula)
					.filter(formula -> !(formula instanceof EmptyFormula))
					.toArray(Formula[]::new)
			);
		} else {
			// but fallback to global scope
			return this.targetIndexes.stream()
				// must precede the scope test: `getIndexes` resolves the index objects of a set lazily, and fails on a
				// set whose partitions include registry rows
				.filter(FilterByVisitor::holdsIndexObjectsOnly)
				.filter(it -> it.getIndexes().stream().anyMatch(index -> index.getIndexKey().scope().equals(scope)))
				.map(
					it -> FormulaFactory.or(
						it.getIndexes()
							.stream()
							.filter(index -> index.getIndexKey().scope().equals(scope))
							.filter(EntityIndex.class::isInstance)
							.map(EntityIndex.class::cast)
							.map(EntityIndex::getAllPrimaryKeysFormula)
							.filter(formula -> !(formula instanceof EmptyFormula))
							.toArray(Formula[]::new)
					)
				)
				.min(Comparator.comparingLong(Formula::getEstimatedCost))
				.orElse(EmptyFormula.INSTANCE);
		}
	}

	/**
	 * Tells whether every partition of an index set has an index object, so that its indexes are the whole set. A
	 * `referenceHaving` or hierarchy option whose partitions include registered single-owner ones has no index object
	 * for those - resolving its indexes would fail on them, and its index objects alone would miss their owners.
	 *
	 * @param targetIndexes the index set
	 * @return TRUE when no partition of the set is a registry row
	 */
	private static boolean holdsIndexObjectsOnly(@Nonnull TargetIndexes<?> targetIndexes) {
		final SplitTargetPartitions nodePartitions = targetIndexes.getSplitTargetPartitions();
		if (nodePartitions != null) {
			return nodePartitions.virtualCount() == 0;
		}
		final ReducedIndexCandidates candidates = targetIndexes.getReducedIndexCandidates();
		return candidates == null || candidates.splitter().apply(candidates.primaryKeys()).getVirtualCount() == 0;
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 */
	@SafeVarargs
	public final <T, S extends Index<?>> T executeInContextAndIsolatedFormulaStack(
		@Nonnull Class<S> indexType,
		@Nonnull Supplier<List<S>> targetIndexSupplier,
		@Nullable EntityContentRequire requirements,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Supplier<T> lambda,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		return executeInContextAndIsolatedFormulaStack(
			indexType,
			targetIndexSupplier,
			requirements,
			entitySchema,
			referenceSchema,
			NegationResolution.IN_PLACE,
			attributeSchemaAccessor,
			attributeValueAccessor,
			lambda,
			suppressedConstraints
		);
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 *
	 * @param negationResolution where a negation emitted in the new scope is settled - see
	 *                               {@link NegationResolution}
	 */
	@SafeVarargs
	public final <T, S extends Index<?>> T executeInContextAndIsolatedFormulaStack(
		@Nonnull Class<S> indexType,
		@Nonnull Supplier<List<S>> targetIndexSupplier,
		@Nullable EntityContentRequire requirements,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull NegationResolution negationResolution,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Supplier<T> lambda,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		try {
			this.stack.push(new LinkedList<>());
			this.postProcessors.push(new LinkedHashMap<>(16));
			return executeInContext(
				indexType,
				targetIndexSupplier,
				requirements,
				entitySchema,
				referenceSchema,
				negationResolution,
				attributeSchemaAccessor,
				attributeValueAccessor,
				lambda,
				suppressedConstraints
			);
		} finally {
			this.stack.pop();
			this.postProcessors.poll();
		}
	}

	@Override
	public boolean isPrefetchPossible() {
		// when we are in lower scopes, the indexes are exchanged and we cannot use the top-level prefetched entities
		// and always prefer the provided indexes
		// the prefetch is also possible only in conjunctive scope
		return this.scope.size() == 1 && this.queryContext.isPrefetchPossible();
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 */
	@SafeVarargs
	public final <T, S extends Index<?>> T executeInContext(
		@Nonnull Class<S> indexType,
		@Nonnull Supplier<List<S>> targetIndexSupplier,
		@Nullable EntityContentRequire requirements,
		@Nullable EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull NegationResolution negationResolution,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Supplier<T> lambda,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		try {
			final ProcessingScope<? extends Index<?>> currentProcessingScope = this.getProcessingScope();
			this.scope.push(
				new ProcessingScope<>(
					indexType,
					targetIndexSupplier,
					currentProcessingScope.getScopes(),
					requirements,
					entitySchema,
					referenceSchema,
					negationResolution,
					attributeSchemaAccessor,
					attributeValueAccessor,
					currentProcessingScope.getReferencedEntityExpansionFunction(),
					suppressedConstraints
				)
			);
			return lambda.get();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Method executes the logic on the current entity set.
	 */
	@Nonnull
	public Formula applyOnIndexes(@Nonnull Function<EntityIndex, Formula> formulaFunction) {
		return joinFormulas(
			getEntityIndexStream().map(it -> tagWithProducingIndex(it, formulaFunction.apply(it)))
		);
	}

	/**
	 * Resolves the {@link PriceSuperIndex} that owns the memory-expensive price records behind the price indexes of
	 * the passed entity index.
	 *
	 * A reduced entity index keeps no pointer to the super price indexes backing it, so a price formula built over one
	 * must be handed the super index explicitly. Resolving it here - from the query context, which is pinned to a single
	 * catalog version for the whole query - is what makes that hand-over version-correct by construction: the reduced
	 * index and the GLOBAL it is paired with are read out of the same catalog snapshot, so they cannot straddle
	 * a version boundary.
	 *
	 * @param entityIndex the entity index a price formula is being built over
	 * @return the price index of the GLOBAL entity index of the same collection and scope
	 */
	@Nonnull
	public PriceSuperIndex getSuperPriceIndex(@Nonnull EntityIndex entityIndex) {
		// a GLOBAL index owns its super price indexes outright - no lookup needed
		if (entityIndex instanceof GlobalEntityIndex globalEntityIndex) {
			return globalEntityIndex.getPriceIndex();
		}
		final String entityType = getProcessingScope().getEntitySchemaOrThrowException().getName();
		final Scope scope = entityIndex.getIndexKey().scope();
		return getQueryContext()
			.getGlobalEntityIndexIfExists(entityType, scope)
			.orElseThrow(
				() -> new GenericEvitaInternalError(
					"Global index of entity `" + entityType + "` in scope `" + scope +
						"` unexpectedly not found while resolving super price index!"
				)
			)
			.getPriceIndex();
	}

	/**
	 * Method executes the logic on the current entity set.
	 */
	@Nonnull
	public Formula applyStreamOnIndexes(@Nonnull Function<EntityIndex, Stream<Formula>> formulaFunction) {
		return joinFormulas(
			getEntityIndexStream()
				.flatMap(it -> formulaFunction.apply(it).map(formula -> tagWithProducingIndex(it, formula)))
		);
	}

	/**
	 * Looks a value of a unique attribute up in the first requested scope that holds it, and returns the first
	 * non-empty answer.
	 *
	 * The scopes are walked in the order `scope(...)` requested them in, so a value that several requested scopes hold
	 * resolves to the scope listed first, and a negation of the lookup complements that answer - it may return the
	 * entity in the other scope that carries the very value. Each scope is looked up the way it declares the
	 * uniqueness:
	 *
	 * - in the unique index of the catalog of that scope, where the attribute is a catalog attribute globally unique
	 *   there - the only lookup available when the queried collection is not known. That index spans every collection,
	 *   so when the filter targets one collection, an entity of another collection holding the value is no match: its
	 *   primary key would be read as a key of the targeted collection, and the scope is treated as not holding the
	 *   value. The targeted collection is the one of the {@link ProcessingScope} - the referenced collection for a
	 *   nested filter planned on this query context, such as the node filter of `hierarchyWithin`, and the queried one
	 *   otherwise;
	 * - otherwise in the unique indexes of the entity indexes of that scope, where the attribute is unique within the
	 *   collection there;
	 * - a scope declaring neither is passed over.
	 *
	 * A result of a collection unique index is tagged with the index that produced it, like every formula
	 * {@link #applyOnIndexes(Function)} builds: inside a `referenceHaving` body an untagged leaf reads as
	 * index-independent, so the row-scoping rebuild would apply the match to every row of the owner -
	 * `not(attributeEquals(u, x1))` then drops an owner whose other row carries a different value. Outside a reference
	 * body no tag is attached. A catalog attribute is never resolved inside a reference body.
	 *
	 * @param referenceSchema     the reference schema the attribute belongs to, or NULL for an entity attribute
	 * @param attributeDefinition the schema definition of the attribute being looked up
	 * @param globalLookup        the lookup in the unique index of a catalog, answering the entity that holds the value
	 * @param collectionLookup    the lookup in a unique index of an entity index
	 * @return the first non-empty answer, or {@link EmptyFormula} when no requested scope holds the value
	 */
	@Nonnull
	public Formula applyOnFirstUniqueIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull Function<GlobalUniqueIndex, Optional<EntityReferenceWithLocale>> globalLookup,
		@Nonnull Function<UniqueIndex, Formula> collectionLookup
	) {
		final Set<Scope> allowedScopes = getProcessingScope().getScopes();
		final Scope[] scopesInOrder = allowedScopes.size() == 1 ?
			new Scope[]{allowedScopes.iterator().next()} :
			Arrays.stream(this.queryContext.getEvitaRequest().getScopesAsArray())
				.filter(allowedScopes::contains)
				.toArray(Scope[]::new);
		// the collection the filter is planned for - a nested filter planned on this query context (the node filter of
		// a `hierarchyWithin`, say) targets the referenced collection, not the queried one
		final String targetEntityType = ofNullable(getProcessingScope().getEntitySchema())
			.map(EntitySchemaContract::getName)
			.orElse(isEntityTypeKnown() ? getEntityType() : null);
		for (Scope scope : scopesInOrder) {
			final Formula answer;
			if (attributeDefinition instanceof GlobalAttributeSchemaContract globalAttributeSchema &&
				globalAttributeSchema.isUniqueGloballyInScope(scope)
			) {
				// a scope the catalog has no index of holds no value - looking it up must not create the index
				answer = this.queryContext.getCatalogIndexIfExists(scope)
					.map(catalogIndex -> catalogIndex.getGlobalUniqueIndex(globalAttributeSchema, getLocale()))
					.flatMap(
						globalUniqueIndex -> globalLookup.apply(globalUniqueIndex)
							.filter(holder -> targetEntityType == null || holder.type().equals(targetEntityType))
							.map(
								holder -> (Formula) new MultipleEntityFormula(
									new long[]{globalUniqueIndex.getId()},
									toPrimaryKeyOfTargetCollection(holder, targetEntityType)
								)
							)
					)
					.orElse(EmptyFormula.INSTANCE);
			} else if (attributeDefinition.isUniqueInScope(scope) && isEntityTypeKnown()) {
				answer = getProcessingScope().getIndexStream()
					.filter(ix -> ix.getIndexKey().scope() == scope)
					.filter(EntityIndex.class::isInstance)
					.map(EntityIndex.class::cast)
					.map(
						entityIndex -> {
							final UniqueIndex uniqueIndex = entityIndex.getUniqueIndex(
								referenceSchema, attributeDefinition, getLocale()
							);
							return uniqueIndex == null ?
								EmptyFormula.INSTANCE :
								tagWithProducingIndex(entityIndex, collectionLookup.apply(uniqueIndex));
						}
					)
					.filter(it -> !(it instanceof EmptyFormula))
					.findFirst()
					.orElse(EmptyFormula.INSTANCE);
			} else {
				continue;
			}
			if (!(answer instanceof EmptyFormula)) {
				return answer;
			}
		}
		return EmptyFormula.INSTANCE;
	}

	/**
	 * Returns the key under which the entity holding a globally unique value enters the formula tree of the
	 * collection the filter is planned for.
	 *
	 * In a filter over the queried collection - or over no collection at all - the entity goes through
	 * {@link QueryPlanningContext#translateEntityReference}, which masks keys of several collections apart and
	 * remembers the reference, so the matched entity later takes the locale of the value. A nested filter over another
	 * collection is planned on the same query context, yet its result is a set of keys of that collection only: the key
	 * is used as is, because registering it would pair a key of the queried collection with another collection's
	 * entity.
	 *
	 * @param holder           the entity holding the value, already known to belong to `targetEntityType`
	 * @param targetEntityType the collection the filter is planned for, or NULL when the query targets no collection
	 * @return bitmap with the single key of the holder
	 */
	@Nonnull
	private Bitmap toPrimaryKeyOfTargetCollection(
		@Nonnull EntityReferenceWithLocale holder,
		@Nullable String targetEntityType
	) {
		return targetEntityType == null || (isEntityTypeKnown() && targetEntityType.equals(getEntityType())) ?
			translateEntityReference(holder) :
			new BaseBitmap(holder.primaryKey());
	}

	/**
	 * Method executes the logic on filter index of certain attribute.
	 */
	@Nonnull
	public Formula applyOnFilterIndexes(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull Function<FilterIndex, Formula> formulaFunction
	) {
		return applyOnIndexes(entityIndex -> {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(
				referenceSchema,
				attributeDefinition,
				attributeDefinition.isLocalized() ? getLocale() : null
			);
			if (filterIndex == null) {
				return EmptyFormula.INSTANCE;
			}
			return formulaFunction.apply(filterIndex);
		});
	}

	/**
	 * Method executes the logic on filter index of certain attribute.
	 */
	@Nonnull
	public Formula applyStreamOnFilterIndexes(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Function<FilterIndex, Stream<Formula>> formulaFunction
	) {
		return applyStreamOnIndexes(entityIndex -> {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(
				referenceSchema,
				attributeSchema,
				attributeSchema.isLocalized() ? getLocale() : null
			);
			if (filterIndex == null) {
				return Stream.empty();
			}
			return formulaFunction.apply(filterIndex);
		});
	}

	/**
	 * Method returns TRUE if target index fully represents the passed filtering query (i.e. disjunction
	 * of all {@link EntityIndex#getAllPrimaryKeys()} would produce the correct result for passed query) in the current
	 * processing scopes - see {@link TargetIndexes#represents(FilterConstraint, Set)}.
	 */
	public boolean isTargetIndexRepresentingConstraint(@Nonnull FilterConstraint filterConstraint) {
		return this.indexSetToUse.represents(filterConstraint, getProcessingScope().getScopes());
	}

	/**
	 * Method returns variant of {@link TargetIndexes} that fully represents the passed filtering query (i.e. disjunction
	 * of all {@link EntityIndex#getAllPrimaryKeys()} would produce the correct result for passed query) in the current
	 * processing scopes.
	 *
	 * The scopes matter when one constraint instance is placed in several `inScope` containers: index selection
	 * registers one variant per container, and matching the instance alone would hand every container the variant
	 * of the first one - see {@link TargetIndexes#represents(FilterConstraint, Set)}.
	 */
	@Nullable
	public TargetIndexes<?> findTargetIndexSet(@Nonnull FilterConstraint filterConstraint) {
		final Set<Scope> processingScopes = getProcessingScope().getScopes();
		return this.targetIndexes
			.stream()
			.filter(it -> it.represents(filterConstraint, processingScopes))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Returns {@link ReferenceSchemaContract} that is relevant for currently examined constraint scope or empty result.
	 */
	@Nonnull
	public Optional<ReferenceSchemaContract> getReferenceSchema() {
		return ofNullable(getProcessingScope().getReferenceSchema());
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Records which index produced a per-index leaf, while a `referenceHaving` body is being translated.
	 *
	 * Outside such a body the tag would be noise - there is nothing to rebuild per index - so it is attached only
	 * when a reference schema is in scope, which is what "inside a `referenceHaving` body" actually means. The
	 * index type alone does not say that: when index selection picks the reduced-index option for the whole
	 * query, a top-level constraint is translated in a reduced-index scope too.
	 *
	 * An {@link EmptyFormula} is left untagged because it carries no contribution to attribute to an index, and
	 * {@link #joinFormulas(Stream)} drops it immediately afterwards regardless.
	 *
	 * @param entityIndex the index the formula was produced from
	 * @param formula     the produced formula
	 * @return the formula, tagged when a tag is meaningful here
	 */
	@Nonnull
	private Formula tagWithProducingIndex(@Nonnull EntityIndex entityIndex, @Nonnull Formula formula) {
		return formula instanceof EmptyFormula || getProcessingScope().getReferenceSchema() == null ?
			formula :
			new IndexTaggedFormula(entityIndex.getPrimaryKey(), formula);
	}

	/**
	 * Joins formulas into one OR formula.
	 * @param formulaStream stream of formulas
	 * @return joined formula
	 */
	@Nonnull
	private Formula joinFormulas(@Nonnull Stream<Formula> formulaStream) {
		final Formula[] formulas = formulaStream
			.filter(it -> !(it instanceof EmptyFormula))
			.toArray(Formula[]::new);
		return switch (formulas.length) {
			case 0 -> EmptyFormula.INSTANCE;
			case 1 -> formulas[0];
			default -> FormulaFactory.or(this::getSuperSetFormula, formulas);
		};
	}

	/**
	 * Registers another formula to the current level of the formula calculation tree.
	 */
	private void addFormula(@Nonnull Formula formula) {
		final List<Formula> peekFormulas = this.stack.peek();
		isPremiseValid(peekFormulas != null, "Top formulas unexpectedly empty!");
		if (!(formula instanceof SkipFormula)) {
			peekFormulas.add(formula);
		}
	}

	/**
	 * Processes the result formula tree by applying all registered {@link #postProcessors}. When multiple identical
	 * (those with same {@link FormulaPostProcessor equals}) post processors are registered only first is
	 * executed.
	 */
	@Nonnull
	private Formula constructFinalFormula(
		@Nonnull Formula constraintFormula,
		@Nonnull FormulaPostProcessor... additionalPostProcessors
	) {
		Formula finalFormula = constraintFormula;
		final LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> thePostProcessors = getPostProcessors();
		final Set<FormulaPostProcessor> executedProcessors;
		if (!thePostProcessors.isEmpty()) {
			executedProcessors = CollectionUtils.createHashSet(thePostProcessors.size());
			for (FormulaPostProcessor postProcessor : thePostProcessors.values()) {
				if (!executedProcessors.contains(postProcessor)) {
					postProcessor.visit(finalFormula);
					finalFormula = postProcessor.getPostProcessedFormula();
					executedProcessors.add(postProcessor);
				}
			}
		} else {
			executedProcessors = Collections.emptySet();
		}
		for (FormulaPostProcessor postProcessor : additionalPostProcessors) {
			if (!executedProcessors.contains(postProcessor)) {
				postProcessor.visit(finalFormula);
				finalFormula = postProcessor.getPostProcessedFormula();
			}
		}
		final FormulaDeduplicator deduplicator = new FormulaDeduplicator(finalFormula);
		deduplicator.visit(finalFormula);
		return deduplicator.getPostProcessedFormula();
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link FilteringConstraintTranslator}
	 * implementations to exchange indexes that are being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 */
	public static class ProcessingScope<T extends Index<?>> {
		/**
		 * Contains the type of {@link Index} that is being used in the current scope in {@link #indexes} list.
		 * All indexes must be assignable to this type
		 */
		@Nonnull @Getter
		private final Class<T> indexType;
		/**
		 * Allows to lazily compute and access the list of {@link #indexes} and avoid paying a performance penalty if
		 * the list is not necessary.
		 * Might be null if the list of {@link #indexes} is known since the start.
		 */
		@Nullable
		private final Supplier<List<T>> indexSupplier;
		/**
		 * Set of scopes requested in {@link EvitaRequest#getScopes()}.
		 */
		private final Deque<Set<Scope>> requiredScopes;
		/**
		 * Suppressed constraints contains set of {@link FilterConstraint} that will not be evaluated by this visitor
		 * in current scope.
		 */
		@Nonnull
		private final Set<Class<? extends FilterConstraint>> suppressedConstraints;
		/**
		 * Function provides access to the attribute schema in {@link EntitySchema}
		 */
		@Getter @Nonnull
		private final AttributeSchemaAccessor attributeSchemaAccessor;
		/**
		 * Function provides access to the attribute value in {@link EntityContract} body.
		 */
		@Nonnull
		private final TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor;
		/**
		 * This stack contains parent chain of the current query.
		 */
		@Nonnull
		private final Deque<FilterConstraint> processedConstraints = new ArrayDeque<>(16);
		/**
		 * Contains requirements to be passed for entity prefetch (if available).
		 */
		@Nullable
		private final EntityContentRequire requirements;
		/**
		 * Currently targeted entity schema.
		 */
		@Getter
		@Nullable
		private final EntitySchemaContract entitySchema;
		/**
		 * Currently targeted reference schema.
		 */
		@Getter
		@Nullable
		private final ReferenceSchemaContract referenceSchema;
		/**
		 * Where a negation emitted in this scope is settled - see {@link NegationResolution} for why the answer
		 * belongs to whoever consumes the formula rather than to the index that produced it.
		 *
		 * The same distinction lets `groupHaving` narrow the candidate set by the matching groups' reduced group
		 * indexes, which yields a superset of the accepted rows rather than the answer - so it narrows only under
		 * {@link NegationResolution#PER_ROW}, and keeps the whole reference family where the set is the answer
		 * (`facetHaving`).
		 *
		 * It is carried by the scope rather than derived from {@link #indexType} on purpose: the index type says what
		 * is being read, never whether anyone re-evaluates the result, and several callers funnel into the single site
		 * that establishes a type-level scope.
		 */
		@Getter
		@Nonnull
		private final NegationResolution negationResolution;
		/**
		 * Contains set of indexes, that should be used for accessing final indexes.
		 */
		@Nullable
		private List<T> indexes;
		/**
		 * Superset formula with all primary keys present in indexes.
		 */
		@Nullable
		private Formula superSetFormula;
		/**
		 * Function that allows to expand referenced entity primary keys from the input bitmap of base primary keys.
		 * Used for hierarchical entity structures.
		 */
		@Nullable
		@Getter private UnaryOperator<Bitmap> referencedEntityExpansionFunction;
		/**
		 * The rows of registered single-owner partitions the body translated in this scope is answered for exactly, or
		 * `null` - see {@link VirtualPartitionRows}. Installed only on the scope of a referenced-type entity index by
		 * {@link #doWithVirtualPartitionRows} and never inherited by a scope pushed inside it, so a nested query
		 * planned below - the one of `entityHaving` or `groupHaving` - reads its own collection as it always does.
		 */
		@Nullable
		@Getter private VirtualPartitionRows virtualPartitionRows;

		/**
		 * Recursively examines all children of the specified parent constraint using the provided lambda function.
		 *
		 * @param lambda the consumer function to be applied to each child constraint
		 * @param isConjunction the predicate to determine if a constraint is a conjunction
		 * @param parentConstraint the parent constraint whose children are to be examined
		 */
		private static void examineChildren(
			@Nonnull Consumer<FilterConstraint> lambda,
			@Nonnull Predicate<FilterConstraint> isConjunction,
			@Nonnull ConstraintContainer<FilterConstraint> parentConstraint
		) {
			for (FilterConstraint children : parentConstraint.getChildren()) {
				lambda.accept(children);
				if (isConjunction.test(children)) {
					//noinspection unchecked
					examineChildren(lambda, isConjunction, (ConstraintContainer<FilterConstraint>) children);
				}
			}
		}

		@SafeVarargs
		public ProcessingScope(
			@Nonnull Class<T> indexType,
			@Nonnull List<T> targetIndexes,
			@Nonnull Set<Scope> requiredScopes,
			@Nullable EntityContentRequire requirements,
			@Nullable EntitySchemaContract entitySchema,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this.indexType = indexType;
			this.requiredScopes = new LinkedList<>();
			this.requiredScopes.push(requiredScopes);
			this.attributeSchemaAccessor = attributeSchemaAccessor;
			this.attributeValueAccessor = attributeValueAccessor;
			if (suppressedConstraints.length > 0) {
				this.suppressedConstraints = new HashSet<>(suppressedConstraints.length);
				this.suppressedConstraints.addAll(Arrays.asList(suppressedConstraints));
			} else {
				this.suppressedConstraints = Collections.emptySet();
			}
			this.requirements = requirements;
			this.entitySchema = entitySchema;
			this.referenceSchema = referenceSchema;
			this.indexSupplier = null;
			this.indexes = targetIndexes;
			// a scope built around a known index list is never the reference type-level discovery pass
			this.negationResolution = NegationResolution.IN_PLACE;
		}

		@SafeVarargs
		public ProcessingScope(
			@Nonnull Class<T> indexType,
			@Nonnull Supplier<List<T>> targetIndexSupplier,
			@Nonnull Set<Scope> requiredScopes,
			@Nullable EntityContentRequire requirements,
			@Nullable EntitySchemaContract entitySchema,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nonnull NegationResolution negationResolution,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nullable UnaryOperator<Bitmap> referencedEntityExpansionFunction,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this.indexType = indexType;
			this.requiredScopes = new LinkedList<>();
			this.requiredScopes.push(requiredScopes);
			this.attributeSchemaAccessor = attributeSchemaAccessor;
			this.attributeValueAccessor = attributeValueAccessor;
			if (suppressedConstraints.length > 0) {
				this.suppressedConstraints = new HashSet<>(suppressedConstraints.length);
				this.suppressedConstraints.addAll(Arrays.asList(suppressedConstraints));
			} else {
				this.suppressedConstraints = Collections.emptySet();
			}
			this.requirements = requirements;
			this.entitySchema = entitySchema;
			this.referenceSchema = referenceSchema;
			this.negationResolution = negationResolution;
			this.indexSupplier = targetIndexSupplier;
			this.referencedEntityExpansionFunction = referencedEntityExpansionFunction;
			this.indexes = null;
		}

		/**
		 * Returns indexes that should be used for searching.
		 */
		@Nonnull
		public List<T> getIndexes() {
			if (this.indexes == null) {
				Assert.isPremiseValid(this.indexSupplier != null, "Indexes or index supplier should never be null!");
				this.indexes = this.indexSupplier.get();
			}
			return this.indexes;
		}

		/**
		 * Returns stream of indexes that should be used for searching.
		 */
		@Nonnull
		public Stream<T> getIndexStream() {
			return getIndexes().stream();
		}

		/**
		 * Returns true if passed query should be ignored.
		 */
		public boolean isSuppressed(@Nonnull Class<? extends FilterConstraint> constraint) {
			return this.suppressedConstraints.contains(constraint);
		}

		/**
		 * Adds currently processed query to the parent chain.
		 * Remember to call {@link #popConstraint()} at the end of processing this query.
		 */
		public void pushConstraint(@Nonnull FilterConstraint parent) {
			this.processedConstraints.push(parent);
		}

		/**
		 * Removes query from the list of currently processed constraints.
		 */
		public void popConstraint() {
			this.processedConstraints.pop();
		}

		/**
		 * Returns an {@link Iterable} over the parent chain of the currently processed constraint,
		 * ordered from innermost parent to outermost. The currently processed constraint itself
		 * (the head of the processed-constraints stack) is NOT included in the iteration — callers
		 * only observe its ancestors.
		 *
		 * Intended for translators that must validate their constraint's nesting context at plan
		 * time, e.g. to detect pathological parent combinations that cannot be caught at
		 * constraint-construction time because they depend on the surrounding container.
		 *
		 * @return iterable over the parent chain, innermost first, excluding the current constraint
		 */
		@Nonnull
		public Iterable<FilterConstraint> getProcessedConstraintParents() {
			return () -> {
				final Iterator<FilterConstraint> it = this.processedConstraints.iterator();
				if (it.hasNext()) {
					// skip the currently processed constraint — callers want ancestors only
					it.next();
				}
				return it;
			};
		}

		/**
		 * Method will apply `lambda` in a parent scope of currently processed {@link FilterConstraint} that shares same
		 * conjunction relation.
		 *
		 * @see FilterByVisitor#findInConjunctionTree(Class)
		 */
		public void doInConjunctionBlock(@Nonnull Consumer<FilterConstraint> lambda) {
			final Predicate<FilterConstraint> isConjunction = fc -> CONJUNCTIVE_CONSTRAINTS.contains(fc.getClass());
			doInConjunctionBlock(lambda, isConjunction);
		}

		/**
		 * Method will apply `lambda` in a parent scope of currently processed {@link FilterConstraint} that shares same
		 * conjunction relation.
		 *
		 * @see FilterByVisitor#findInConjunctionTree(Class)
		 */
		public void doInConjunctionBlock(@Nonnull Consumer<FilterConstraint> lambda, @Nonnull Predicate<FilterConstraint> isConjunction) {
			final Iterator<FilterConstraint> it = this.processedConstraints.iterator();
			if (it.hasNext()) {
				// this will get rid of "this" query and first examines its parent
				it.next();
				while (it.hasNext()) {
					final FilterConstraint parentConstraint = it.next();
					if (isConjunction.test(parentConstraint)) {
						//noinspection unchecked
						examineChildren(lambda, isConjunction, (ConstraintContainer<FilterConstraint>) parentConstraint);
					} else {
						break;
					}
				}
			}
		}

		/**
		 * Returns requirements to be passed for entity prefetch (if available).
		 */
		@Nullable
		public EntityContentRequire getRequirements() {
			return this.requirements;
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
		 * Retrieves the entity schema for the current processing scope.
		 * This method will throw an exception if the entity schema is not set.
		 *
		 * @return The entity schema for the current processing scope.
		 * @throws IllegalStateException if the entity schema is not set.
		 */
		@Nonnull
		public EntitySchemaContract getEntitySchemaOrThrowException() {
			Assert.isTrue(this.entitySchema != null, "Entity schema is not set in the processing scope!");
			return this.entitySchema;
		}

		/**
		 * Returns attribute schema for attribute of passed name.
		 */
		@Nonnull
		public AttributeSchemaContract getAttributeSchema(
			@Nonnull String attributeName,
			@Nonnull AttributeTrait... attributeTraits
		) {
			return this.attributeSchemaAccessor.getAttributeSchema(attributeName, getScopes(), attributeTraits);
		}

		/**
		 * Returns attribute schema for attribute of passed name.
		 */
		@Nonnull
		public AttributeSchemaContract getAttributeSchema(@Nonnull EntitySchemaContract entitySchema, @Nonnull String attributeName, @Nonnull AttributeTrait... attributeTraits) {
			return this.attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, getScopes(), attributeTraits);
		}

		/**
		 * Returns new attribute schema accessor that delegates lookup for attribute schema to appropriate reference
		 * schema.
		 */
		@Nonnull
		public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
			return this.attributeSchemaAccessor.withReferenceSchemaAccessor(referenceName);
		}

		/**
		 * Returns attribute value for attribute of passed name.
		 */
		@Nullable
		public Stream<Optional<AttributeValue>> getAttributeValueStream(
			@Nonnull EntityContract entitySchema,
			@Nonnull String attributeName,
			@Nullable Locale locale
		) {
			//noinspection DataFlowIssue
			return this.attributeValueAccessor.apply(entitySchema, attributeName, locale);
		}

		/**
		 * Calculates and returns super-set formula (i.e. formula containing all primary keys from involved indexes)
		 * for the current scope of the execution.
		 *
		 * @return super set formula
		 */
		@Nonnull
		public Formula getSuperSetFormula() {
			if (this.superSetFormula == null) {
				this.superSetFormula = FormulaFactory.or(
					getIndexStream()
						.filter(EntityIndex.class::isInstance)
						.map(EntityIndex.class::cast)
						.map(EntityIndex::getAllPrimaryKeysFormula)
						.filter(it -> !(it instanceof EmptyFormula))
						.toArray(Formula[]::new)
				);
			}
			return this.superSetFormula;
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
		public <S> S doWithScope(@Nonnull Set<Scope> scopeToUse, @Nonnull Supplier<S> lambda) {
			try {
				this.requiredScopes.push(scopeToUse);
				return lambda.get();
			} finally {
				this.requiredScopes.pop();
			}
		}

		/**
		 * Executes the given supplier within the context of the specified referenced entity expansion function.
		 * This method ensures that the specified function is applied for the duration of the supplier's execution
		 * and then restores the previous function afterwards.
		 *
		 * The function is used when {@link ReferencedEntityIndexPrimaryKeyTranslatingFormula} translates original
		 * referenced entity primary keys to list of available indexes. This is necessary for correct handling of
		 * hierarchical entity structures, that should take their children into an account.
		 *
		 * @param referencedEntityExpansionFunction function that expands referenced entity primary keys
		 * @param lambda the supplier function to be executed within the specified context
		 * @return the result produced by the supplier
		 * @param <S> type of result returned by the supplier
		 */
		public <S> S doWithReferencedEntityExpansionFunction(
			@Nonnull UnaryOperator<Bitmap> referencedEntityExpansionFunction,
			@Nonnull Supplier<S> lambda
		) {
			try {
				Assert.isPremiseValid(
					this.referencedEntityExpansionFunction == null,
					"Referenced entity expansion function is already set!"
				);
				this.referencedEntityExpansionFunction = referencedEntityExpansionFunction;
				return lambda.get();
			} finally {
				this.referencedEntityExpansionFunction = null;
			}
		}

		/**
		 * Executes the given supplier with the rows of registered single-owner partitions installed on this scope, so
		 * that the leaves translated meanwhile answer them exactly - see {@link VirtualPartitionRows}. The rows are
		 * removed again afterwards.
		 *
		 * @param virtualPartitionRows the rows the body is answered for
		 * @param lambda               the supplier translating the body
		 * @param <S>                  type of result returned by the supplier
		 * @return the result produced by the supplier
		 */
		public <S> S doWithVirtualPartitionRows(
			@Nonnull VirtualPartitionRows virtualPartitionRows,
			@Nonnull Supplier<S> lambda
		) {
			Assert.isPremiseValid(
				this.virtualPartitionRows == null,
				"Virtual partition rows are already installed!"
			);
			try {
				this.virtualPartitionRows = virtualPartitionRows;
				return lambda.get();
			} finally {
				this.virtualPartitionRows = null;
			}
		}

	}

}
