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

package io.evitadb.core.query.filter;

import io.evitadb.api.exception.EntityCollectionRequiredException;
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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.exception.ReferenceNotFoundException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.ReferencedEntityFetcher;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.infra.SkipFormula;
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
import io.evitadb.core.query.filter.translator.facet.FacetInSetTranslator;
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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
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
public class FilterByVisitor implements ConstraintVisitor {
	private static final Formula[] EMPTY_INTEGER_FORMULA = new Formula[0];
	private static final Map<Class<? extends FilterConstraint>, FilteringConstraintTranslator<? extends FilterConstraint>> TRANSLATORS;

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
		TRANSLATORS.put(FacetInSet.class, new FacetInSetTranslator());
		TRANSLATORS.put(UserFilter.class, new UserFilterTranslator());
	}

	/**
	 * Contemporary stack for keeping results resolved for each level of the query.
	 */
	private final Deque<List<Formula>> stack = new LinkedList<>();
	/**
	 * Contemporary stack for keeping results resolved for each level of the query.
	 */
	@Getter(AccessLevel.PROTECTED)
	private final Deque<ProcessingScope> scope = new LinkedList<>();
	/**
	 * Contains list of registered post processors. Formula post processor is used to transform final {@link Formula}
	 * tree constructed in {@link FilterByVisitor} before computing the result. Post processors should analyze created
	 * tree and optimize it to achieve maximal impact of memoization process or limit the scope of processed records
	 * as soon as possible. We may take advantage of transitivity in boolean algebra to exchange formula placement
	 * the way it's most performant.
	 */
	private final List<FormulaPostProcessor> postProcessors = new LinkedList<>();
	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Nonnull
	@Delegate @Getter private final QueryContext queryContext;
	/**
	 * Collection contains all alternative {@link TargetIndexes} sets that might already contain precalculated information
	 * related to {@link EntityIndex} that can be used to partially resolve input filter although the target index set
	 * is not used to resolve entire query filter.
	 */
	@Nonnull
	private final List<TargetIndexes> targetIndexes;
	/**
	 * This instance contains the {@link EntityIndex} set that is used to resolve passed query filter.
	 */
	@Nonnull
	private final TargetIndexes indexSetToUse;
	/**
	 * Field is set to TRUE when it's already known that filtering query contains query that uses data from
	 * the {@link #indexSetToUse} - i.e. query implementing {@link IndexUsingConstraint}. This situation allows
	 * certain translators to entirely skip themselves because the query will be implicitly evaluated by the other
	 * constraints using already limited subset from the {@link #indexSetToUse}.
	 */
	@Getter private final boolean targetIndexQueriedByOtherConstraints;
	/**
	 * Contains the translated formula from the filtering query source tree.
	 */
	private Formula computedFormula;

	protected FilterByVisitor(
		@Nonnull ProcessingScope processingScope,
		@Nonnull QueryContext queryContext,
		@Nonnull List<TargetIndexes> targetIndexes,
		@Nonnull TargetIndexes indexSetToUse,
		boolean targetIndexQueriedByOtherConstraints
	) {
		this.stack.push(new LinkedList<>());
		this.scope.push(processingScope);
		this.queryContext = queryContext;
		this.targetIndexes = targetIndexes;
		this.indexSetToUse = indexSetToUse;
		this.targetIndexQueriedByOtherConstraints = targetIndexQueriedByOtherConstraints;
	}

	public FilterByVisitor(
		@Nonnull QueryContext queryContext,
		@Nonnull List<TargetIndexes> targetIndexes,
		@Nonnull TargetIndexes indexSetToUse,
		boolean targetIndexQueriedByOtherConstraints
	) {
		this(
			new ProcessingScope(
				indexSetToUse.getIndexes(),
				AttributeContent.ALL_ATTRIBUTES,
				null, null,
				new AttributeSchemaAccessor(queryContext),
				(entityContract, attributeName, locale) -> Stream.of(entityContract.getAttributeValue(attributeName, locale))
			),
			queryContext,
			targetIndexes,
			indexSetToUse,
			targetIndexQueriedByOtherConstraints
		);
	}

	/**
	 * Returns the computed formula that represents the filter query visited by this implementation.
	 */
	@Nonnull
	public Formula getFormula() {
		return ofNullable(this.computedFormula)
			.orElseGet(this::getSuperSetFormula);
	}

	/**
	 * Returns the computed formula that represents the filter query visited by this implementation.
	 * The computed formula is reset to NULL after this method is called so that the visitor instance can be reused.
	 */
	@Nonnull
	public Formula getFormulaAndClear() {
		final Formula result = ofNullable(this.computedFormula)
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
		return ofNullable(stack.peek())
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
	 * Returns attribute values from current scope.
	 */
	@Nonnull
	public Stream<Optional<AttributeValue>> getAttributeValueStream(@Nonnull EntityContract entity, @Nonnull String attributeName, @Nonnull Locale locale) {
		return getProcessingScope().getAttributeValueStream(entity, attributeName, locale);
	}

	/**
	 * Method returns true if parent of the currently examined query matches passed type.
	 */
	public boolean isParentConstraint(@Nonnull Class<? extends FilterConstraint> constraintType) {
		final ProcessingScope theScope = getProcessingScope();
		return ofNullable(theScope.getParentConstraint())
			.map(constraintType::isInstance)
			.orElse(false);
	}

	/**
	 * Method returns true if any of the siblings of the currently examined query matches any of the passed types.
	 */
	@SuppressWarnings("unchecked")
	public boolean isAnySiblingConstraintPresent(@Nonnull Class<? extends FilterConstraint>... constraintType) {
		final ProcessingScope theScope = getProcessingScope();
		final FilterConstraint parentConstraint = theScope.getParentConstraint();
		if (parentConstraint instanceof ConstraintContainer) {
			//noinspection unchecked
			for (FilterConstraint examinedType : (ConstraintContainer<FilterConstraint>) parentConstraint) {
				for (Class<? extends FilterConstraint> lookedUpType : constraintType) {
					if (lookedUpType.isInstance(examinedType)) {
						return true;
					}
				}
			}
		}
		return false;
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
	public <T extends FilterConstraint> T findInConjunctionTree(Class<T> constraintType) {
		final ProcessingScope theScope = getProcessingScope();
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
			"No translator found for query `" + filterConstraint.getClass() + "`!"
		);

		final ProcessingScope theScope = getProcessingScope();
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
				throw new EvitaInternalError("Should never happen");
			}

			// if current query is FilterBy we know we're at the top of the filtering query tree
			if (filterConstraint instanceof FilterBy) {
				// so we can assign the result of the visitor
				this.computedFormula = constructFinalFormula(constraintFormula);
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
	public void registerFormulaPostProcessorIfNotPresent(@Nonnull FormulaPostProcessor formulaPostProcessor) {
		this.postProcessors.add(formulaPostProcessor);
	}

	/**
	 * Returns extension of {@link ProcessingScope} that is set for current context.
	 *
	 * @see #executeInContext(List, EntityContentRequire, ReferenceSchemaContract, Function, EntityNestedQueryComparator, AttributeSchemaAccessor, TriFunction, Supplier, Class[])
	 */
	@Nonnull
	public ProcessingScope getProcessingScope() {
		final ProcessingScope processingScope;
		if (scope.isEmpty()) {
			throw new EvitaInternalError("Scope should never be empty");
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
	public List<EntityIndex> getReferencedRecordEntityIndexes(@Nonnull ReferenceHaving referenceHaving) {
		final String referenceName = referenceHaving.getReferenceName();
		final EntitySchemaContract entitySchema = getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, entitySchema));
		final boolean referencesHierarchicalEntity = isReferencingHierarchicalEntity(referenceSchema);
		final Formula referencedRecordIdFormula = getReferencedRecordIdFormula(referenceSchema, new FilterBy(referenceHaving.getChildren()));
		final Bitmap referencedRecordIds = referencedRecordIdFormula.compute();
		final List<EntityIndex> result = new ArrayList<>(referencedRecordIds.size());
		for (Integer referencedRecordId : referencedRecordIds) {
			ofNullable(getReferencedEntityIndex(referenceName, referencesHierarchicalEntity, referencedRecordId))
				.ifPresent(result::add);
		}
		return result;
	}

	/**
	 * Returns bitmap of primary keys ({@link EntityContract#getPrimaryKey()}) of referenced entities that satisfy
	 * the passed filtering constraint.
	 *
	 * @param referenceSchema that identifies the examined entities
	 * @param filterBy        the filtering constraint to satisfy
	 * @return bitmap with referenced entity ids
	 */
	@Nonnull
	public Formula getReferencedRecordIdFormula(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterBy filterBy
	) {
		final String referenceName = referenceSchema.getName();
		final EntitySchemaContract entitySchema = getSchema();
		isTrue(referenceSchema.isFilterable(), () -> new ReferenceNotIndexedException(referenceName, entitySchema));

		final EntityIndex entityIndex = getIndex(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName));
		if (entityIndex == null) {
			return EmptyFormula.INSTANCE;
		}

		final Formula resultFormula = executeInContext(
			Collections.singletonList(entityIndex),
			ReferenceContent.ALL_REFERENCES,
			referenceSchema,
			null, null,
			getProcessingScope().withReferenceSchemaAccessor(referenceSchema.getName()),
			(theEntity, attributeName, locale) -> theEntity.getReferences(referenceName).stream().map(it -> it.getAttributeValue(attributeName, locale)),
			() -> {
				filterBy.accept(this);
				return getFormulaAndClear();
			}
		);
		return resultFormula;
	}

	/**
	 * Returns the special index for passed {@link ReferenceSchemaContract#getName() reference name} that contains
	 * index of referenced entity ids instead of the primary entity primary keys.
	 *
	 * @param entitySchema    to be used in error message
	 * @param referenceSchema {@link ReferenceSchemaContract} to identify the reference
	 * @return the index
	 */
	@Nonnull
	public ReferencedTypeEntityIndex getReferencedEntityTypeIndex(@Nonnull EntitySchemaContract entitySchema, @Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceName = referenceSchema.getName();
		isTrue(referenceSchema.isFilterable(), () -> new ReferenceNotIndexedException(referenceName, entitySchema));

		return Objects.requireNonNull(
			this.queryContext.getIndex(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName))
		);
	}

	/**
	 * Returns stream of indexes that should be all considered for record lookup.
	 */
	@Nonnull
	public Stream<EntityIndex> getEntityIndexStream() {
		final Deque<ProcessingScope> scope = getScope();
		return scope.isEmpty() ? Stream.empty() : scope.peek().getIndexStream(EntityIndex.class);
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
	 * Returns true if passed `groupId` of `referenceName` facets are requested to be joined by conjunction (AND) instead
	 * of default disjunction (OR).
	 */
	public boolean isFacetGroupConjunction(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer groupId) {
		return groupId != null && getEvitaRequest().isFacetGroupConjunction(referenceSchema.getName(), groupId);
	}

	/**
	 * Returns true if passed `groupId` of `referenceName` is requested to be joined with other facet groups by
	 * disjunction (OR) instead of default conjunction (AND).
	 */
	public boolean isFacetGroupDisjunction(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer groupId) {
		return groupId != null && getEvitaRequest().isFacetGroupDisjunction(referenceSchema.getName(), groupId);
	}

	/**
	 * Returns true if passed `groupId` of `referenceName` facets are requested to be joined by negation (AND NOT) instead
	 * of default disjunction (OR).
	 */
	public boolean isFacetGroupNegation(@Nonnull ReferenceSchemaContract referenceSchema, @Nullable Integer groupId) {
		return groupId != null && getEvitaRequest().isFacetGroupNegation(referenceSchema.getName(), groupId);
	}

	/**
	 * Returns {@link EntityIndex} that contains indexed entities that reference `referenceName` and `referencedEntityId`.
	 * Argument `referencesHierarchicalEntity` should be evaluated first by {@link #isReferencingHierarchicalEntity(ReferenceSchemaContract)} method.
	 */
	@Nullable
	public EntityIndex getReferencedEntityIndex(@Nonnull ReferenceSchemaContract referenceSchema, int referencedEntityId) {
		return getReferencedEntityIndex(
			referenceSchema.getName(),
			isReferencingHierarchicalEntity(referenceSchema),
			referencedEntityId
		);
	}

	/**
	 * Returns {@link EntityIndex} that contains indexed entities that reference `referenceName` and `referencedEntityId`.
	 * Argument `referencesHierarchicalEntity` should be evaluated first by {@link #isReferencingHierarchicalEntity(ReferenceSchemaContract)} method.
	 */
	@Nullable
	public EntityIndex getReferencedEntityIndex(@Nonnull String referenceName, boolean referencesHierarchicalEntity, int referencedEntityId) {
		if (referencesHierarchicalEntity) {
			return getQueryContext().getIndex(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_HIERARCHY_NODE,
					new ReferenceKey(referenceName, referencedEntityId)
				)
			);
		} else {
			return getQueryContext().getIndex(
				new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY,
					new ReferenceKey(referenceName, referencedEntityId)
				)
			);
		}
	}

	/**
	 * Returns super-set formula - i.e. formula that contains all record ids from the filter visitor chooses the result
	 * from.
	 */
	@Nonnull
	public Formula getSuperSetFormula() {
		return applyOnIndexes(EntityIndex::getAllPrimaryKeysFormula);
	}

	/**
	 * Initializes new set of target {@link ProcessingScope} to be used in the visitor.
	 */
	@SafeVarargs
	public final <T> T executeInContext(
		@Nonnull List<EntityIndex> targetIndexes,
		@Nullable EntityContentRequire requirements,
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
				new ProcessingScope(
					targetIndexes,
					requirements,
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
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull Function<GlobalUniqueIndex, Formula> formulaFunction
	) {
		final CatalogIndex catalogIndex = getIndex(CatalogIndexKey.INSTANCE);
		final String attributeName = attributeDefinition.getName();
		if (catalogIndex == null) {
			throw new EntityCollectionRequiredException("filter by attribute `" + attributeName + "`");
		} else {
			final GlobalUniqueIndex globalUniqueIndex = catalogIndex.getGlobalUniqueIndex(attributeName);
			if (globalUniqueIndex == null) {
				return EmptyFormula.INSTANCE;
			}
			return formulaFunction.apply(globalUniqueIndex);
		}
	}

	/**
	 * Method executes the logic on unique index of certain attribute.
	 */
	@Nonnull
	public Formula applyOnUniqueIndexes(@Nonnull AttributeSchemaContract attributeDefinition, @Nonnull Function<UniqueIndex, Formula> formulaFunction) {
		return applyOnIndexes(entityIndex -> {
			final UniqueIndex uniqueIndex = entityIndex.getUniqueIndex(attributeDefinition.getName(), getLocale());
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
			final UniqueIndex uniqueIndex = entityIndex.getUniqueIndex(attributeDefinition.getName(), getLocale());
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
			final FilterIndex filterIndex = entityIndex.getFilterIndex(attributeDefinition.getName(), getLocale());
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
			final FilterIndex filterIndex = entityIndex.getFilterIndex(attributeDefinition.getName(), getLocale());
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
	public TargetIndexes findTargetIndexSet(@Nonnull FilterConstraint filterConstraint) {
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
		if (!postProcessors.isEmpty()) {
			final Set<FormulaPostProcessor> executedProcessors = CollectionUtils.createHashSet(postProcessors.size());
			for (FormulaPostProcessor postProcessor : postProcessors) {
				if (!executedProcessors.contains(postProcessor)) {
					postProcessor.visit(finalFormula);
					finalFormula = postProcessor.getPostProcessedFormula();
					executedProcessors.add(postProcessor);
				}
			}
		}
		return finalFormula;
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link FilteringConstraintTranslator}
	 * implementations to exchange indexes that are being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 */
	public static class ProcessingScope {
		/**
		 * Contains set of indexes, that should be used for accessing final indexes.
		 */
		@Nonnull
		private final List<Index<?>> indexes;
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
		private final Deque<FilterConstraint> processedConstraints = new LinkedList<>();
		/**
		 * Contains requirements to be passed for entity prefetch (if available).
		 */
		@Nullable
		private final EntityContentRequire requirements;
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
			@Nonnull List<? extends Index<?>> targetIndexes,
			@Nullable EntityContentRequire requirements,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this(
				targetIndexes,
				requirements,
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
			@Nonnull List<? extends Index<?>> targetIndexes,
			@Nullable EntityContentRequire requirements,
			@Nullable ReferenceSchemaContract referenceSchema,
			@Nullable Function<FilterConstraint, FilterConstraint> nestedQueryFormulaEnricher,
			@Nullable EntityNestedQueryComparator entityNestedQueryComparator,
			@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
			@Nonnull TriFunction<EntityContract, String, Locale, Stream<Optional<AttributeValue>>> attributeValueAccessor,
			@Nonnull Class<? extends FilterConstraint>... suppressedConstraints
		) {
			this.attributeSchemaAccessor = attributeSchemaAccessor;
			this.attributeValueAccessor = attributeValueAccessor;
			if (suppressedConstraints.length > 0) {
				this.suppressedConstraints = new HashSet<>(suppressedConstraints.length);
				this.suppressedConstraints.addAll(Arrays.asList(suppressedConstraints));
			} else {
				this.suppressedConstraints = Collections.emptySet();
			}
			this.requirements = requirements;
			this.referenceSchema = referenceSchema;
			this.nestedQueryFormulaEnricher = nestedQueryFormulaEnricher == null ? Function.identity() : nestedQueryFormulaEnricher;
			this.entityNestedQueryComparator = entityNestedQueryComparator;
			this.indexes = new ArrayList<>(targetIndexes.size());
			this.indexes.addAll(targetIndexes);
		}

		/**
		 * Returns stream of indexes that should be used for searching.
		 */
		public <S extends IndexKey, T extends Index<S>> Stream<T> getIndexStream(Class<T> indexType) {
			return indexes.stream().filter(indexType::isInstance).map(indexType::cast);
		}

		/**
		 * Returns true if passed query should be ignored.
		 */
		public boolean isSuppressed(Class<? extends FilterConstraint> constraint) {
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
		 * Returns parent of currently processed query.
		 */
		@Nullable
		public FilterConstraint getParentConstraint() {
			if (this.processedConstraints.size() > 1) {
				final Iterator<FilterConstraint> it = this.processedConstraints.iterator();
				it.next();
				return it.next();
			} else {
				return null;
			}
		}

		/**
		 * Method will apply `lambda` in a parent scope of currently processed {@link FilterConstraint} that shares same
		 * conjunction relation.
		 *
		 * @see FilterByVisitor#findInConjunctionTree(Class)
		 */
		public void doInConjunctionBlock(@Nonnull Consumer<FilterConstraint> lambda) {
			final Predicate<FilterConstraint> isConjunction = fc -> fc instanceof And || fc instanceof UserFilter;
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
	}

}
