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

package io.evitadb.core.query.sort;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.order.Random;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.LocaleProvider;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.attribute.translator.AttributeNaturalTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeSetExactTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeSetInFilterTranslator;
import io.evitadb.core.query.sort.attribute.translator.ReferencePropertyTranslator;
import io.evitadb.core.query.sort.price.translator.PriceDiscountTranslator;
import io.evitadb.core.query.sort.price.translator.PriceNaturalTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyExactTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyInFilterTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyNaturalTranslator;
import io.evitadb.core.query.sort.random.translator.RandomTranslator;
import io.evitadb.core.query.sort.segment.SegmentsTranslator;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.query.sort.translator.OrderInScopeTranslator;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.ofNullable;

/**
 * This {@link ConstraintVisitor} translates tree of {@link OrderConstraint} to a composition of {@link Sorter}
 * Visitor represents the "planning" phase for the ordering resolution. The planning should be as light-weight as
 * possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class OrderByVisitor implements ConstraintVisitor, LocaleProvider {
	private static final Map<Class<? extends OrderConstraint>, OrderingConstraintTranslator<? extends OrderConstraint>> TRANSLATORS;

	/* initialize list of all FilterableConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = CollectionUtils.createHashMap(8);
		TRANSLATORS.put(OrderBy.class, new OrderByTranslator());
		TRANSLATORS.put(AttributeNatural.class, new AttributeNaturalTranslator());
		TRANSLATORS.put(ReferenceProperty.class, new ReferencePropertyTranslator());
		TRANSLATORS.put(Random.class, new RandomTranslator());
		TRANSLATORS.put(PriceNatural.class, new PriceNaturalTranslator());
		TRANSLATORS.put(PriceDiscount.class, new PriceDiscountTranslator());
		TRANSLATORS.put(EntityPrimaryKeyInFilter.class, new EntityPrimaryKeyInFilterTranslator());
		TRANSLATORS.put(EntityPrimaryKeyNatural.class, new EntityPrimaryKeyNaturalTranslator());
		TRANSLATORS.put(EntityPrimaryKeyExact.class, new EntityPrimaryKeyExactTranslator());
		TRANSLATORS.put(AttributeSetInFilter.class, new AttributeSetInFilterTranslator());
		TRANSLATORS.put(AttributeSetExact.class, new AttributeSetExactTranslator());
		TRANSLATORS.put(Segments.class, new SegmentsTranslator());
		TRANSLATORS.put(OrderInScope.class, new OrderInScopeTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Getter @Delegate(excludes = LocaleProvider.class)
	private final QueryPlanningContext queryContext;
	/**
	 * Collection contains all alternative {@link TargetIndexes} sets that might already contain precalculated information
	 * related to {@link EntityIndex} that can be used to partially resolve input filter although the target index set
	 * is not used to resolve entire query filter.
	 */
	@Getter @Nonnull
	private final List<? extends TargetIndexes<?>> targetIndexes;
	/**
	 * Filter by visitor used for creating filtering formula.
	 */
	@Getter private final FilterByVisitor filterByVisitor;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Getter private final Formula filteringFormula;
	/**
	 * Contemporary stack for auxiliary data resolved for each level of the query.
	 */
	private final Deque<ProcessingScope> scope = new ArrayDeque<>(16);

	public OrderByVisitor(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Formula filteringFormula
	) {
		this(
			queryContext, targetIndexes, filterByVisitor, filteringFormula,
			new AttributeSchemaAccessor(queryContext)
		);
	}

	public OrderByVisitor(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Formula filteringFormula,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor) {
		this.queryContext = queryContext;
		this.targetIndexes = targetIndexes;
		this.filterByVisitor = filterByVisitor;
		this.filteringFormula = filteringFormula;
		final LinkedList<Set<Scope>> scopes = new LinkedList<>();
		final Set<Scope> requestedScopes = this.queryContext.getScopes();
		scopes.add(requestedScopes);
		this.scope.push(
			new ProcessingScope(
				scopes,
				Arrays.stream(Scope.values())
					.filter(requestedScopes::contains)
					.map(this.queryContext::getGlobalEntityIndexIfExists)
					.filter(Optional::isPresent)
					.map(Optional::get)
					.toArray(EntityIndex[]::new),
				this.queryContext.isEntityTypeKnown() ?
					this.queryContext.getSchema().getName() : null,
				null,
				null,
				attributeSchemaAccessor,
				new ArrayDeque<>(16)
			)
		);
	}

	/**
	 * Returns the created sorter from the ordering query source tree or default {@link NoSorter} instance.
	 */
	@Nonnull
	public Sorter getSorter() {
		final ProcessingScope currentScope = getProcessingScope();
		final Deque<Sorter> currentSorters = currentScope.sorters();
		if (currentSorters.isEmpty()) {
			return NoSorter.INSTANCE;
		} else {
			boolean canUseCache = !this.queryContext.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES) &&
				this.queryContext.isEntityTypeKnown();

			final CacheSupervisor cacheSupervisor = this.queryContext.getCacheSupervisor();
			Sorter composedSorter = null;
			final Iterator<Sorter> it = currentSorters.descendingIterator();
			while (it.hasNext()) {
				final Sorter nextSorter = it.next();
				final Sorter possiblyCachedSorter = canUseCache && nextSorter instanceof CacheableSorter cacheableSorter ?
					cacheSupervisor.analyse(
						this.queryContext.getEvitaSession(),
						this.queryContext.getSchema().getName(),
						cacheableSorter
					) : nextSorter;
				composedSorter = composedSorter == null ? possiblyCachedSorter : possiblyCachedSorter.andThen(composedSorter);
			}

			return composedSorter == null ? NoSorter.INSTANCE : composedSorter;
		}
	}

	/**
	 * Collects and isolates a new sorter within a new processing scope.
	 * It temporarily pushes a new {@link ProcessingScope} onto the current scope stack,
	 * assigns it the current context values, and subsequently pops it after the sorter is retrieved.
	 *
	 * @param lambda the lambda to execute within the isolated scope that creates and registers the sorter chain
	 * @return the created {@link Sorter} from the ordering query source tree or default {@link NoSorter} instance
	 */
	@Nonnull
	public final Sorter collectIsolatedSorter(@Nonnull Runnable lambda) {
		try {
			final ProcessingScope currentScope = getProcessingScope();
			final LinkedList<Set<Scope>> requestedScopes = new LinkedList<>();
			requestedScopes.push(currentScope.getScopes());
			this.scope.push(
				new ProcessingScope(
					// the requested scopes never change
					requestedScopes,
					currentScope.entityIndex(),
					currentScope.entityType(),
					currentScope.referenceSchema(),
					currentScope.locale(),
					currentScope.attributeSchemaAccessor(),
					new ArrayDeque<>(16)
				)
			);
			lambda.run();
			return getSorter();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Sets different {@link EntityIndex} to be used in scope of lambda.
	 */
	public final <T> T executeInContext(
		@Nonnull EntityIndex[] entityIndex,
		@Nullable String entityType,
		@Nullable Locale locale,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull Supplier<T> lambda
	) {
		try {
			final ProcessingScope processingScope = getProcessingScope();
			final LinkedList<Set<Scope>> requestedScopes = new LinkedList<>();
			requestedScopes.push(processingScope.getScopes());
			this.scope.push(
				new ProcessingScope(
					requestedScopes,
					entityIndex,
					entityType,
					null,
					locale,
					attributeSchemaAccessor,
					processingScope.sorters()
				)
			);
			return lambda.get();
		} finally {
			this.scope.pop();
		}
	}

	/**
	 * Sets different {@link EntityIndex} to be used in scope of lambda.
	 */
	public final <T> T executeInContext(
		@Nonnull EntityIndex[] entityIndex,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Locale locale,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull Supplier<T> lambda
	) {
		try {
			final ProcessingScope processingScope = getProcessingScope();
			final LinkedList<Set<Scope>> requestedScopes = new LinkedList<>();
			requestedScopes.push(processingScope.getScopes());
			this.scope.push(
				new ProcessingScope(
					requestedScopes,
					entityIndex,
					referenceSchema.getReferencedEntityType(),
					referenceSchema,
					locale,
					attributeSchemaAccessor,
					processingScope.sorters()
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
			return this.scope.peek();
		}
	}

	/**
	 * Returns index which is best suited for supplying {@link SortIndex}.
	 */
	@Nonnull
	public EntityIndex[] getIndexesForSort() {
		final ProcessingScope theScope = this.scope.peek();
		isPremiseValid(theScope != null, "Scope is unexpectedly empty!");
		return theScope.entityIndex();
	}

	/**
	 * Returns locale valid for this processing scope or the entire query context.
	 */
	@Override
	@Nullable
	public Locale getLocale() {
		return ofNullable(getProcessingScope().locale()).orElseGet(this.queryContext::getLocale);
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final OrderConstraint orderConstraint = (OrderConstraint) constraint;

		@SuppressWarnings("unchecked") final OrderingConstraintTranslator<OrderConstraint> translator =
			(OrderingConstraintTranslator<OrderConstraint>) TRANSLATORS.get(orderConstraint.getClass());
		isPremiseValid(
			translator != null,
			"No translator found for constraint `" + orderConstraint.getClass() + "`!"
		);

		// if query is a container query
		final Stream<Sorter> currentSorters;
		if (orderConstraint instanceof ConstraintContainer) {
			@SuppressWarnings("unchecked") final ConstraintContainer<OrderConstraint> container = (ConstraintContainer<OrderConstraint>) orderConstraint;
			// process children constraints
			if (!(translator instanceof SelfTraversingTranslator)) {
				for (OrderConstraint subConstraint : container) {
					subConstraint.accept(this);
				}
			}
			// process the container query itself
			currentSorters = translator.createSorter(orderConstraint, this);
		} else if (orderConstraint instanceof ConstraintLeaf) {
			// process the leaf query
			currentSorters = translator.createSorter(orderConstraint, this);
		} else {
			// sanity check only
			throw new GenericEvitaInternalError("Should never happen");
		}
		// compose sorters one after another
		final Deque<Sorter> sortersQueue = getProcessingScope().sorters();
		currentSorters.forEach(sortersQueue::add);
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link OrderingConstraintTranslator}
	 * implementations to exchange indexes that are being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 *
	 * @param requiredScopes          contains set of scopes that are requested in input query
	 * @param entityIndex             contains index, that should be used for accessing {@link SortIndex}.
	 * @param entityType              contains entity type the context refers to
	 * @param referenceSchema         contains reference schema the scope relates to
	 * @param locale                  contains locale the context refers to
	 * @param attributeSchemaAccessor consumer verifies prerequisites in attribute schema via {@link AttributeSchemaContract}
	 * @param sorters                 contains the stack of sorters that are being composed on particular level of the query
	 */
	public record ProcessingScope(
		@Nonnull Deque<Set<Scope>> requiredScopes,
		@Nonnull EntityIndex[] entityIndex,
		@Nullable String entityType,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable Locale locale,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull Deque<Sorter> sorters
	) {

		/**
		 * Returns attribute schema for attribute of passed name.
		 */
		@Nonnull
		public AttributeSchemaContract getAttributeSchema(@Nonnull String theAttributeName, @Nonnull AttributeTrait... attributeTraits) {
			return this.attributeSchemaAccessor.getAttributeSchema(theAttributeName, getScopes(), attributeTraits);
		}

		/**
		 * Returns sortable attribute compound or sortable attribute schema for attribute of passed name.
		 */
		@Nonnull
		public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(@Nonnull String theAttributeName) {
			return this.attributeSchemaAccessor.getAttributeSchemaOrSortableAttributeCompound(theAttributeName, getScopes());
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
