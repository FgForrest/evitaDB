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

package io.evitadb.core.query.filter;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.PrefetchStrategyResolver;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.ReferencedEntityFetcher;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.translator.FilterByTranslator;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.filter.translator.UserFilterTranslator;
import io.evitadb.core.query.filter.translator.attribute.*;
import io.evitadb.core.query.filter.translator.bool.AndTranslator;
import io.evitadb.core.query.filter.translator.bool.NotTranslator;
import io.evitadb.core.query.filter.translator.bool.OrTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityLocaleEqualsTranslator;
import io.evitadb.core.query.filter.translator.entity.EntityPrimaryKeyInSetTranslator;
import io.evitadb.core.query.filter.translator.facet.FacetHavingTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinRootTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.core.query.filter.translator.price.PriceBetweenTranslator;
import io.evitadb.core.query.filter.translator.price.PriceInCurrencyTranslator;
import io.evitadb.core.query.filter.translator.price.PriceInPriceListsTranslator;
import io.evitadb.core.query.filter.translator.price.PriceValidInTranslator;
import io.evitadb.core.query.filter.translator.reference.EntityHavingTranslator;
import io.evitadb.core.query.filter.translator.reference.ReferenceHavingTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.attribute.translator.EntityNestedQueryComparator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.isTrue;
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
	private static final Formula[] EMPTY_INTEGER_FORMULA = new Formula[0];
	/**
	 * Contains index of all {@link FilterConstraint} to {@link Formula} translators.
	 */
	private static final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>> TRANSLATORS;
	/**
	 * Contains set of formulas that are considered conjunctive for purpose of this visitor.
	 */
	private static final Set<Class<? extends FilterConstraint>> CONJUNCTIVE_CONSTRAINTS;
	/**
	 * Contains set of formulas that are considered conjunctive for purpose of this visitor.
	 */
	private static final Set<Class<? extends Formula>> CONJUNCTIVE_FORMULAS;

	/* initialize list of all FilterableConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = createHashMap(40);
		TRANSLATORS.put(FilterBy.class, new FilterByTranslator());
		TRANSLATORS.put(And.class, new AndTranslator());
		TRANSLATORS.put(Or.class, new OrTranslator());
		TRANSLATORS.put(Not.class, new NotTranslator());
		TRANSLATORS.put(EntityPrimaryKeyInSet.class, new EntityPrimaryKeyInSetTranslator());
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
		TRANSLATORS.put(ReferenceHaving.class, new ReferenceHavingTranslator());
		TRANSLATORS.put(PriceInCurrency.class, new PriceInCurrencyTranslator());
		TRANSLATORS.put(PriceValidIn.class, new PriceValidInTranslator());
		TRANSLATORS.put(PriceInPriceLists.class, new PriceInPriceListsTranslator());
		TRANSLATORS.put(PriceBetween.class, new PriceBetweenTranslator());
		TRANSLATORS.put(HierarchyWithin.class, new HierarchyWithinTranslator());
		TRANSLATORS.put(HierarchyWithinRoot.class, new HierarchyWithinRootTranslator());
		TRANSLATORS.put(FacetHaving.class, new FacetHavingTranslator());
		TRANSLATORS.put(UserFilter.class, new UserFilterTranslator());

		CONJUNCTIVE_FORMULAS = new HashSet<>();
		CONJUNCTIVE_FORMULAS.add(AndFormula.class);
		CONJUNCTIVE_FORMULAS.add(UserFilterFormula.class);
		CONJUNCTIVE_FORMULAS.add(SelectionFormula.class);
		CONJUNCTIVE_FORMULAS.add(AttributeFormula.class);

		CONJUNCTIVE_CONSTRAINTS = new HashSet<>();
		CONJUNCTIVE_CONSTRAINTS.add(And.class);
		CONJUNCTIVE_CONSTRAINTS.add(UserFilter.class);
		CONJUNCTIVE_CONSTRAINTS.add(FilterBy.class);
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
	@Nonnull
	private final TargetIndexes<? extends Index<?>> indexSetToUse;
	/**
	 * Contains the translated formula from the filtering query source tree.
	 */
	private Formula computedFormula;

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
	 * @param queryContext            used for accessing global index, global cache and recording query telemetry
	 * @param filterBy                the filter constraints the entities must match
	 * @param entityType              the entity type of the entity that is looked up
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 * @return output {@link Formula} that is able to produce the matching entity primary keys
	 */
	@Nonnull
	public static Formula createFormulaForTheFilter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull FilterBy filterBy,
		@Nonnull String entityType,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		return createFormulaForTheFilter(
			queryContext,
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
	 * @param queryContext            used for accessing global index, global cache and recording query telemetry
	 * @param filterBy                the filter constraints the entities must match
	 * @param entityType              the entity type of the entity that is looked up
	 * @param stepDescriptionSupplier the message supplier for the query telemetry
	 * @return output {@link Formula} that is able to produce the matching entity primary keys
	 */
	@Nonnull
	public static Formula createFormulaForTheFilter(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull FilterBy filterBy,
		@Nullable FilterBy rootFilterBy,
		@Nonnull String entityType,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		final Formula theFormula;
		try {
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

			// now analyze the filter by in a nested context with exchanged primary entity index
			theFormula = queryContext.getGlobalEntityIndexIfExists(entityType)
				.map(
					entityIndex -> queryContext.analyse(
						theFilterByVisitor.executeInContextAndIsolatedFormulaStack(
							GlobalEntityIndex.class,
							() -> Collections.singletonList(entityIndex),
							null,
							queryContext.getSchema(entityType),
							null,
							null,
							null,
							new AttributeSchemaAccessor(queryContext.getCatalogSchema(), queryContext.getSchema(entityType)),
							(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale)),
							() -> {
								// initialize root constraint for the execution
								if (rootFilterBy != null) {
									// we don't need to pop it, because the filter by visitor is going to be discarded
									theFilterByVisitor.scope.peek().pushConstraint(rootFilterBy);
								}

								filterBy.accept(theFilterByVisitor);
								// get the result and clear the visitor internal structures
								return theFilterByVisitor.getFormulaAndClear();
							}
						)
					)
				).orElse(EmptyFormula.INSTANCE);
		} finally {
			queryContext.popStep();
		}
		return theFormula;
	}

	protected <T extends Index<?>> FilterByVisitor(
		@Nonnull ProcessingScope<T> processingScope,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes,
		@Nonnull TargetIndexes<T> indexSetToUse
	) {
		this.stack.push(new LinkedList<>());
		this.postProcessors.push(new LinkedHashMap<>(16));
		this.scope.push(processingScope);
		this.queryContext = queryContext;
		//I just can't get generic to work here
		//noinspection unchecked,rawtypes
		this.targetIndexes = (List) targetIndexes;
		this.indexSetToUse = indexSetToUse;
	}

	public <T extends Index<?>> FilterByVisitor(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<TargetIndexes<T>> targetIndexes,
		@Nonnull TargetIndexes<T> indexSetToUse
	) {
		this(
			new ProcessingScope<>(
				indexSetToUse.getIndexType(),
				indexSetToUse.getIndexes(),
				AttributeContent.ALL_ATTRIBUTES,
				queryContext.isEntityTypeKnown() ? queryContext.getSchema() : null,
				null, null,
				new AttributeSchemaAccessor(queryContext),
				(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale))
			),
			queryContext,
			targetIndexes,
			indexSetToUse
		);
	}

	/**
	 * Returns the computed formula that represents the filter query visited by this implementation.
	 */
	@Nonnull
	public Formula getFormula() {
		return ofNullable(this.computedFormula)
			.map(this::constructFinalFormula)
			.orElseGet(this::getSuperSetFormula);
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
	 * Returns true if `referenceName` points to hierarchical entity.
	 */
	public boolean isReferencingHierarchicalEntity(@Nonnull ReferenceSchemaContract referenceSchema) {
		return referenceSchema.isReferencedEntityTypeManaged() &&
			getSchema(referenceSchema.getReferencedEntityType()).isWithHierarchy();
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
	public AttributeSchemaContract getAttributeSchema(@Nonnull String attributeName, @Nonnull AttributeTrait... requiredTrait) {
		return getProcessingScope().getAttributeSchemaAccessor().getAttributeSchema(attributeName, requiredTrait);
	}

	/**
	 * Returns attribute definition from current scope using provided entity schema.
	 */
	@Nonnull
	public AttributeSchemaContract getAttributeSchema(@Nonnull EntitySchemaContract entitySchema, @Nonnull String attributeName, @Nonnull AttributeTrait... requiredTrait) {
		return getProcessingScope().getAttributeSchemaAccessor().getAttributeSchema(entitySchema, attributeName, requiredTrait);
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
			return foundConstraints.get(0);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final FilterConstraint filterConstraint = (FilterConstraint) constraint;

		final FilteringConstraintTranslator<FilterConstraint> translator =
			(FilteringConstraintTranslator<FilterConstraint>) TRANSLATORS.get(filterConstraint.getClass());
		isPremiseValid(
			translator != null,
			"No translator found for constraint `" + filterConstraint.getClass() + "`!"
		);

		final ProcessingScope<?> theScope = getProcessingScope();
		if (theScope.isSuppressed(filterConstraint.getClass())) {
			return;
		}

		try {
			theScope.pushConstraint(filterConstraint);
			final Formula constraintFormula;
			// if query is a container query
			if (filterConstraint instanceof ConstraintContainer) {
				@SuppressWarnings("unchecked") final ConstraintContainer<FilterConstraint> container = (ConstraintContainer<FilterConstraint>) filterConstraint;
				// initialize new level of the query
				stack.push(new LinkedList<>());
				if (!(translator instanceof SelfTraversingTranslator)) {
					// process children constraints
					for (FilterConstraint subConstraint : container) {
						subConstraint.accept(this);
					}
				}
				// process the container query itself
				constraintFormula = translator.translate(filterConstraint, this);
				// close the level
				stack.pop();
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
		final T value = formulaPostProcessorSupplier.get();
		this.postProcessors.peek().put(
			postProcessorType,
			value
		);
		return value;
	}

	/**
	 * Returns extension of {@link ProcessingScope} that is set for current context.
	 *
	 * @see #executeInContext(Class, List, EntityContentRequire, EntitySchemaContract, ReferenceSchemaContract, Function, EntityNestedQueryComparator, AttributeSchemaAccessor, TriFunction, Supplier, Class[])
	 */
	@Nonnull
	public ProcessingScope<? extends Index<?>> getProcessingScope() {
		final ProcessingScope<? extends Index<?>> processingScope;
		if (scope.isEmpty()) {
			throw new GenericEvitaInternalError("Scope should never be empty");
		} else {
			processingScope = scope.peek();
			isPremiseValid(processingScope != null, "Scope could never be null!");
		}
		return processingScope;
	}

	/**
	 * Method returns all {@link EntityIndex} that contain subset of data that satisfy the passed filtering constraint.
	 *
	 * @return entity indexes that contains parts of indexed data
	 */
	@Nonnull
	public List<ReducedEntityIndex> getReferencedRecordEntityIndexes(@Nonnull ReferenceHaving referenceHaving) {
		final String referenceName = referenceHaving.getReferenceName();
		final EntitySchemaContract entitySchema = getProcessingScope().getEntitySchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
		final boolean referencesHierarchicalEntity = isReferencingHierarchicalEntity(referenceSchema);
		final Formula referencedRecordIdFormula = getReferencedRecordIdFormula(entitySchema, referenceSchema, new FilterBy(referenceHaving.getChildren()));
		final Bitmap referencedRecordIds = referencedRecordIdFormula.compute();
		final List<ReducedEntityIndex> result = new ArrayList<>(referencedRecordIds.size());
		for (Integer referencedRecordId : referencedRecordIds) {
			getReferencedEntityIndex(entitySchema, referenceName, referencesHierarchicalEntity, referencedRecordId)
				.ifPresent(result::add);
		}
		return result;
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
		final String referenceName = referenceSchema.getName();
		isTrue(referenceSchema.isIndexed(), () -> new ReferenceNotIndexedException(referenceName, entitySchema));

		final Optional<ReferencedTypeEntityIndex> entityIndex = getIndex(
			entitySchema.getName(),
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName),
			ReferencedTypeEntityIndex.class
		);
		if (entityIndex.isEmpty()) {
			return EmptyFormula.INSTANCE;
		}

		final Formula resultFormula = executeInContextAndIsolatedFormulaStack(
			ReferencedTypeEntityIndex.class,
			() -> Collections.singletonList(entityIndex.get()),
			ReferenceContent.ALL_REFERENCES,
			entitySchema,
			referenceSchema,
			null, null,
			getProcessingScope().withReferenceSchemaAccessor(referenceSchema.getName()),
			(theEntity, attributeName, locale) -> theEntity.getReferences(referenceName).stream().map(it -> it.getAttributeValue(attributeName, locale)),
			() -> {
				filterBy.accept(this);
				return getFormulaAndClear();
			}
		);

		// we need to initialize formula here, because the result will be needed in internal phase
		resultFormula.initialize(this.getInternalExecutionContext());

		return resultFormula;
	}

	/**
	 * Returns stream of indexes that should be all considered for record lookup.
	 */
	@Nonnull
	public Stream<EntityIndex> getEntityIndexStream() {
		final Deque<ProcessingScope<? extends Index<?>>> scope = getScope();
		return scope.isEmpty() ?
			Stream.empty() :
			scope.peek()
				.getIndexStream()
				.filter(EntityIndex.class::isInstance)
				.map(EntityIndex.class::cast);
	}

	/**
	 * Returns TRUE when the query contains constraint that targets the reference reduced index. In other
	 * words - when {@link ReferenceHaving} constraint is part of the query. This situation must be taken into an
	 * account in hierarchy translators.
	 */
	public boolean isReferenceQueriedByOtherConstraints() {
		return this.targetIndexes.stream()
			.anyMatch(it -> it.getRepresentedConstraint() instanceof ReferenceHaving);
	}

	/**
	 * Returns {@link EntityIndex} that contains indexed entities that reference `referenceName` and `referencedEntityId`.
	 * Argument `referencesHierarchicalEntity` should be evaluated first by {@link #isReferencingHierarchicalEntity(ReferenceSchemaContract)} method.
	 */
	@Nonnull
	public Optional<ReducedEntityIndex> getReferencedEntityIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int referencedEntityId
	) {
		return getReferencedEntityIndex(
			entitySchema,
			referenceSchema.getName(),
			isReferencingHierarchicalEntity(referenceSchema),
			referencedEntityId
		);
	}

	/**
	 * Returns {@link EntityIndex} that contains indexed entities that reference `referenceName` and `referencedEntityId`.
	 * Argument `referencesHierarchicalEntity` should be evaluated first by {@link #isReferencingHierarchicalEntity(ReferenceSchemaContract)} method.
	 */
	@Nonnull
	public Optional<ReducedEntityIndex> getReferencedEntityIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		boolean referencesHierarchicalEntity,
		int referencedEntityId
	) {
		if (referencesHierarchicalEntity) {
			return getQueryContext().getIndex(
				entitySchema.getName(),
				new EntityIndexKey(
					EntityIndexType.REFERENCED_HIERARCHY_NODE,
					new ReferenceKey(referenceName, referencedEntityId)
				),
				ReducedEntityIndex.class
			);
		} else {
			return getQueryContext().getIndex(
				entitySchema.getName(),
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY,
					new ReferenceKey(referenceName, referencedEntityId)
				),
				ReducedEntityIndex.class
			);
		}
	}

	/**
	 * Returns super-set formula - i.e. formula that contains all record ids from the filter visitor chooses the result
	 * from.
	 */
	@Nonnull
	public Formula getSuperSetFormula() {
		return getProcessingScope().getSuperSetFormula();
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 */
	@SafeVarargs
	public final <T, S extends Index<?>> T executeInContextAndIsolatedFormulaStack(
		@Nonnull Class<S> indexType,
		@Nonnull Supplier<List<S>> targetIndexSupplier,
		@Nullable EntityContentRequire requirements,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
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
				nestedQueryFormulaEnricher,
				entityNestedQueryComparator,
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
		return this.scope.size() == 1 && this.queryContext.isPrefetchPossible();
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 */
	@SafeVarargs
	public final <T, S extends EntityIndex> T executeInContext(
		@Nonnull Class<S> indexType,
		@Nonnull List<S> targetIndexes,
		@Nullable EntityContentRequire requirements,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Supplier<T> lambda,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		try {
			this.scope.push(
				new ProcessingScope<>(
					indexType,
					targetIndexes,
					requirements,
					entitySchema,
					referenceSchema,
					nestedQueryFormulaEnricher,
					entityNestedQueryComparator,
					attributeSchemaAccessor,
					attributeValueAccessor,
					suppressedConstraints
				)
			);
			return lambda.get();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 */
	@SafeVarargs
	public final <T, S extends Index<?>> T executeInContext(
		@Nonnull Class<S> indexType,
		@Nonnull Supplier<List<S>> targetIndexSupplier,
		@Nullable EntityContentRequire requirements,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
		@Nonnull Supplier<T> lambda,
		@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
	) {
		try {
			this.scope.push(
				new ProcessingScope<>(
					indexType,
					targetIndexSupplier,
					requirements,
					entitySchema,
					referenceSchema,
					nestedQueryFormulaEnricher,
					entityNestedQueryComparator,
					attributeSchemaAccessor,
					attributeValueAccessor,
					suppressedConstraints
				)
			);
			return lambda.get();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Method executes the logic on the current entity set and returns collection of all formulas.
	 */
	@Nonnull
	public List<Formula> collectFromIndexes(@Nonnull Function<EntityIndex, Stream<? extends Formula>> formulaFunction) {
		return getEntityIndexStream().flatMap(formulaFunction).toList();
	}

	/**
	 * Method executes the logic on the current entity set.
	 */
	@Nonnull
	public Formula applyOnIndexes(@Nonnull Function<EntityIndex, Formula> formulaFunction) {
		return joinFormulas(getEntityIndexStream().map(formulaFunction));
	}

	/**
	 * Method executes the logic on the current entity set.
	 */
	@Nonnull
	public Formula applyStreamOnIndexes(@Nonnull Function<EntityIndex, Stream<Formula>> formulaFunction) {
		return joinFormulas(getEntityIndexStream().flatMap(formulaFunction));
	}

	/**
	 * Method executes the logic on unique index of certain attribute.
	 */
	@Nonnull
	public Formula applyOnGlobalUniqueIndex(
		@Nonnull GlobalAttributeSchemaContract attributeDefinition,
		@Nonnull Function<GlobalUniqueIndex, Formula> formulaFunction
	) {
		final EnumSet<Scope> allowedScopes = getEvitaRequest().getScopes();
		return FormulaFactory.or(
			Arrays.stream(Scope.values())
				.filter(allowedScopes::contains)
				.map(CatalogIndexKey::new)
				.map(this::getIndex)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.map(CatalogIndex.class::cast)
				.map(catalogIndex -> catalogIndex.getGlobalUniqueIndex(attributeDefinition, getLocale()))
				.filter(Objects::nonNull)
				.map(formulaFunction)
				.filter(it -> !(it instanceof EmptyFormula))
				.toArray(Formula[]::new)
		);
	}

	/**
	 * Method executes the logic on unique index of certain attribute.
	 */
	@Nonnull
	public Formula applyOnUniqueIndexes(@Nonnull AttributeSchemaContract attributeDefinition, @Nonnull Function<UniqueIndex, Formula> formulaFunction) {
		return applyOnIndexes(entityIndex -> {
			final UniqueIndex uniqueIndex = entityIndex.getUniqueIndex(attributeDefinition, getLocale());
			if (uniqueIndex == null) {
				return EmptyFormula.INSTANCE;
			}
			return formulaFunction.apply(uniqueIndex);
		});
	}

	/**
	 * Method executes the logic on unique index of certain attribute.
	 */
	@Nonnull
	public Formula applyStreamOnUniqueIndexes(@Nonnull AttributeSchemaContract attributeDefinition, @Nonnull Function<UniqueIndex, Stream<Formula>> formulaFunction) {
		return applyStreamOnIndexes(entityIndex -> {
			final UniqueIndex uniqueIndex = entityIndex.getUniqueIndex(attributeDefinition, getLocale());
			if (uniqueIndex == null) {
				return Stream.empty();
			}
			return formulaFunction.apply(uniqueIndex);
		});
	}

	/**
	 * Method executes the logic on filter index of certain attribute.
	 */
	@Nonnull
	public Formula applyOnFilterIndexes(@Nonnull AttributeSchemaContract attributeDefinition, @Nonnull Function<FilterIndex, Formula> formulaFunction) {
		return applyOnIndexes(entityIndex -> {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(
				attributeDefinition.getName(),
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
	public Formula applyStreamOnFilterIndexes(@Nonnull AttributeSchemaContract attributeDefinition, @Nonnull Function<FilterIndex, Stream<Formula>> formulaFunction) {
		return applyStreamOnIndexes(entityIndex -> {
			final FilterIndex filterIndex = entityIndex.getFilterIndex(
				attributeDefinition.getName(),
				attributeDefinition.isLocalized() ? getLocale() : null
			);
			if (filterIndex == null) {
				return Stream.empty();
			}
			return formulaFunction.apply(filterIndex);
		});
	}

	/**
	 * Method returns TRUE if target index fully represents the passed filtering query (i.e. disjunction
	 * of all {@link EntityIndex#getAllPrimaryKeys()} would produce the correct result for passed query).
	 */
	public boolean isTargetIndexRepresentingConstraint(@Nonnull FilterConstraint filterConstraint) {
		return indexSetToUse.getRepresentedConstraint() == filterConstraint;
	}

	/**
	 * Method returns variant of {@link TargetIndexes} that fully represents the passed filtering query (i.e. disjunction
	 * of all {@link EntityIndex#getAllPrimaryKeys()} would produce the correct result for passed query).
	 */
	@Nullable
	public TargetIndexes<?> findTargetIndexSet(@Nonnull FilterConstraint filterConstraint) {
		return targetIndexes
			.stream()
			.filter(it -> it.getRepresentedConstraint() == filterConstraint)
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
		final List<Formula> peekFormulas = stack.peek();
		isPremiseValid(peekFormulas != null, "Top formulas unexpectedly empty!");
		peekFormulas.add(formula);
	}

	/**
	 * Processes the result formula tree by applying all registered {@link #postProcessors}. When multiple identical
	 * (those with same {@link FormulaPostProcessor equals}) post processors are registered only first is
	 * executed.
	 */
	@Nonnull
	private Formula constructFinalFormula(@Nonnull Formula constraintFormula) {
		Formula finalFormula = constraintFormula;
		final LinkedHashMap<Class<? extends FormulaPostProcessor>, FormulaPostProcessor> thePostProcessors = this.postProcessors.peek();
		if (!thePostProcessors.isEmpty()) {
			final Set<FormulaPostProcessor> executedProcessors = CollectionUtils.createHashSet(thePostProcessors.size());
			for (FormulaPostProcessor postProcessor : thePostProcessors.values()) {
				if (!executedProcessors.contains(postProcessor)) {
					postProcessor.visit(finalFormula);
					finalFormula = postProcessor.getPostProcessedFormula();
					executedProcessors.add(postProcessor);
				}
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
		@Nonnull
		private final EntitySchemaContract entitySchema;
		/**
		 * Currently targeted reference schema.
		 */
		@Getter
		@Nullable
		private final ReferenceSchemaContract referenceSchema;
		/**
		 * Function that allows to crate a new constraint composition in case the {@link EntityHaving} constraint is
		 * processed and creates new nested query with its internal filtering constraint. This enricher is particularly
		 * required in {@link ReferencedEntityFetcher}.
		 */
		@Getter
		@Nonnull
		private final Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher;
		/**
		 * Comparator that holds information about requested ordering so that we can apply it during entity filtering
		 * (if it's performed) and pre-initialize it.
		 */
		@Getter
		@Nullable
		private final EntityNestedQueryComparator entityNestedQueryComparator;
		/**
		 * Contains set of indexes, that should be used for accessing final indexes.
		 */
		private List<T> indexes;
		/**
		 * Superset formula with all primary keys present in indexes.
		 */
		private Formula superSetFormula;

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
			@Nullable EntityContentRequire requirements,
			@Nonnull EntitySchemaContract entitySchema,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this(
				indexType,
				targetIndexes,
				requirements,
				entitySchema,
				referenceSchema,
				null,
				entityNestedQueryComparator,
				attributeSchemaAccessor,
				attributeValueAccessor,
				suppressedConstraints
			);
		}

		@SafeVarargs
		public ProcessingScope(
			@Nonnull Class<T> indexType,
			@Nonnull List<T> targetIndexes,
			@Nullable EntityContentRequire requirements,
			@Nonnull EntitySchemaContract entitySchema,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
			@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this.indexType = indexType;
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
			this.nestedQueryFormulaEnricher = nestedQueryFormulaEnricher == null ? Function.identity() : nestedQueryFormulaEnricher;
			this.entityNestedQueryComparator = entityNestedQueryComparator;
			this.indexSupplier = null;
			this.indexes = targetIndexes;
		}

		@SafeVarargs
		public ProcessingScope(
			@Nonnull Class<T> indexType,
			@Nonnull Supplier<List<T>> targetIndexSupplier,
			@Nullable EntityContentRequire requirements,
			@Nonnull EntitySchemaContract entitySchema,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
			@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this.indexType = indexType;
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
			this.nestedQueryFormulaEnricher = nestedQueryFormulaEnricher == null ? Function.identity() : nestedQueryFormulaEnricher;
			this.entityNestedQueryComparator = entityNestedQueryComparator;
			this.indexSupplier = targetIndexSupplier;
			this.indexes = null;
		}

		/**
		 * Returns indexes that should be used for searching.
		 */
		@Nonnull
		public List<T> getIndexes() {
			if (indexes == null) {
				this.indexes = this.indexSupplier.get();
			}
			return indexes;
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
			return requirements;
		}

		/**
		 * Returns attribute schema for attribute of passed name.
		 */
		@Nonnull
		public AttributeSchemaContract getAttributeSchema(@Nonnull String attributeName, @Nonnull AttributeTrait... attributeTraits) {
			return attributeSchemaAccessor.getAttributeSchema(attributeName, attributeTraits);
		}

		/**
		 * Returns attribute schema for attribute of passed name.
		 */
		@Nonnull
		public AttributeSchemaContract getAttributeSchema(@Nonnull EntitySchemaContract entitySchema, @Nonnull String attributeName, @Nonnull AttributeTrait... attributeTraits) {
			return attributeSchemaAccessor.getAttributeSchema(entitySchema, attributeName, attributeTraits);
		}

		/**
		 * Returns new attribute schema accessor that delegates lookup for attribute schema to appropriate reference
		 * schema.
		 */
		@Nonnull
		public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
			return attributeSchemaAccessor.withReferenceSchemaAccessor(referenceName);
		}

		/**
		 * Returns attribute value for attribute of passed name.
		 */
		@Nullable
		public Stream<Optional<AttributeValue>> getAttributeValueStream(@Nonnull EntityContract entitySchema, @Nonnull String attributeName, @Nullable Locale locale) {
			return attributeValueAccessor.apply(entitySchema, attributeName, locale);
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
	}

}
