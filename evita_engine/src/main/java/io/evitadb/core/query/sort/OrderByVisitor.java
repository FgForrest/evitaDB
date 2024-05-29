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

package io.evitadb.core.query.sort;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.LocaleProvider;
import io.evitadb.core.query.PrefetchRequirementCollector;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.core.query.sort.attribute.translator.AttributeExtractor;
import io.evitadb.core.query.sort.attribute.translator.AttributeNaturalTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeSetExactTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeSetInFilterTranslator;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.core.query.sort.attribute.translator.ReferencePropertyTranslator;
import io.evitadb.core.query.sort.price.translator.PriceNaturalTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyExactTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyInFilterTranslator;
import io.evitadb.core.query.sort.primaryKey.translator.EntityPrimaryKeyNaturalTranslator;
import io.evitadb.core.query.sort.random.translator.RandomTranslator;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	private static final EntityIndex[] EMPTY_INDEX_ARRAY = new EntityIndex[0];

	/* initialize list of all FilterableConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = CollectionUtils.createHashMap(8);
		TRANSLATORS.put(OrderBy.class, new OrderByTranslator());
		TRANSLATORS.put(AttributeNatural.class, new AttributeNaturalTranslator());
		TRANSLATORS.put(ReferenceProperty.class, new ReferencePropertyTranslator());
		TRANSLATORS.put(Random.class, new RandomTranslator());
		TRANSLATORS.put(PriceNatural.class, new PriceNaturalTranslator());
		TRANSLATORS.put(EntityPrimaryKeyInFilter.class, new EntityPrimaryKeyInFilterTranslator());
		TRANSLATORS.put(EntityPrimaryKeyNatural.class, new EntityPrimaryKeyNaturalTranslator());
		TRANSLATORS.put(EntityPrimaryKeyExact.class, new EntityPrimaryKeyExactTranslator());
		TRANSLATORS.put(AttributeSetInFilter.class, new AttributeSetInFilterTranslator());
		TRANSLATORS.put(AttributeSetExact.class, new AttributeSetExactTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Getter @Delegate(excludes = LocaleProvider.class)
	private final QueryContext queryContext;
	/**
	 * Collection contains all alternative {@link TargetIndexes} sets that might already contain precalculated information
	 * related to {@link EntityIndex} that can be used to partially resolve input filter although the target index set
	 * is not used to resolve entire query filter.
	 */
	@Getter @Nonnull
	private final List<? extends TargetIndexes<?>> targetIndexes;
	/**
	 * Reference to the collector of requirements for entity prefetch phase.
	 */
	@Delegate
	private final PrefetchRequirementCollector prefetchRequirementCollector;
	/**
	 * Contains filtering formula tree that was used to produce results so that computed sub-results can be used for
	 * sorting.
	 */
	@Getter private final Formula filteringFormula;
	/**
	 * Contemporary stack for auxiliary data resolved for each level of the query.
	 */
	private final Deque<ProcessingScope> scope = new ArrayDeque<>(16);
	/**
	 * Contains the created sorter from the ordering query source tree.
	 */
	private final Deque<Sorter> sorters = new ArrayDeque<>(16);

	public OrderByVisitor(
		@Nonnull QueryContext queryContext,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull PrefetchRequirementCollector prefetchRequirementCollector,
		@Nonnull Formula filteringFormula
	) {
		this(
			queryContext, targetIndexes, prefetchRequirementCollector, filteringFormula,
			new AttributeSchemaAccessor(queryContext)
		);
	}

	public OrderByVisitor(
		@Nonnull QueryContext queryContext,
		@Nonnull List<? extends TargetIndexes<?>> targetIndexes,
		@Nonnull PrefetchRequirementCollector prefetchRequirementCollector,
		@Nonnull Formula filteringFormula,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor) {
		this.queryContext = queryContext;
		this.targetIndexes = targetIndexes;
		this.prefetchRequirementCollector = prefetchRequirementCollector;
		this.filteringFormula = filteringFormula;
		scope.push(
			new ProcessingScope(
				this.queryContext.getGlobalEntityIndexIfExists()
					.map(it -> new EntityIndex[]{it})
					.orElse(EMPTY_INDEX_ARRAY),
				this.queryContext.isEntityTypeKnown() ?
					this.queryContext.getSchema().getName() : null,
				null,
				null,
				attributeSchemaAccessor,
				EntityAttributeExtractor.INSTANCE
			)
		);
	}

	/**
	 * Returns the created sorter from the ordering query source tree or default {@link NoSorter} instance.
	 */
	@Nonnull
	public Sorter getSorter() {
		if (sorters.isEmpty()) {
			return NoSorter.INSTANCE;
		} else {
			boolean canUseCache = !queryContext.isDebugModeEnabled(DebugMode.VERIFY_POSSIBLE_CACHING_TREES) &&
				queryContext.isEntityTypeKnown();

			final CacheSupervisor cacheSupervisor = queryContext.getCacheSupervisor();
			Sorter composedSorter = null;
			final Iterator<Sorter> it = sorters.descendingIterator();
			while (it.hasNext()) {
				final Sorter nextSorter = it.next();
				final Sorter possiblyCachedSorter = canUseCache && nextSorter instanceof CacheableSorter cacheableSorter ?
					cacheSupervisor.analyse(
						queryContext.getEvitaSession(),
						queryContext.getSchema().getName(),
						cacheableSorter
					) : nextSorter;
				composedSorter = composedSorter == null ? possiblyCachedSorter : possiblyCachedSorter.andThen(composedSorter);
			}

			return composedSorter;
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
		@Nonnull AttributeExtractor attributeSchemaEntityAccessor,
		@Nonnull Supplier<T> lambda
	) {
		try {
			this.scope.push(
				new ProcessingScope(
					entityIndex,
					entityType,
					null,
					locale,
					attributeSchemaAccessor,
					attributeSchemaEntityAccessor
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
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable Locale locale,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull AttributeExtractor attributeSchemaEntityAccessor,
		@Nonnull Supplier<T> lambda
	) {
		try {
			this.scope.push(
				new ProcessingScope(
					entityIndex,
					referenceSchema.getReferencedEntityType(),
					referenceSchema,
					locale,
					attributeSchemaAccessor,
					attributeSchemaEntityAccessor
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
		if (scope.isEmpty()) {
			throw new GenericEvitaInternalError("Scope should never be empty");
		} else {
			return scope.peek();
		}
	}

	/**
	 * Returns index which is best suited for supplying {@link SortIndex}.
	 */
	public EntityIndex[] getIndexesForSort() {
		final ProcessingScope theScope = this.scope.peek();
		isPremiseValid(theScope != null, "Scope is unexpectedly empty!");
		return theScope.entityIndex();
	}

	/**
	 * Returns locale valid for this processing scope or the entire query context.
	 */
	@Override
	@Nonnull
	public Locale getLocale() {
		return ofNullable(getProcessingScope().locale()).orElseGet(queryContext::getLocale);
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
		currentSorters.forEach(this.sorters::add);
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link OrderingConstraintTranslator}
	 * implementations to exchange indexes that are being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 *
	 * @param entityIndex             contains index, that should be used for accessing {@link SortIndex}.
	 * @param entityType              contains entity type the context refers to
	 * @param referenceSchema         contains reference schema the scope relates to
	 * @param locale                  contains locale the context refers to
	 * @param attributeSchemaAccessor consumer verifies prerequisites in attribute schema via {@link AttributeSchemaContract}
	 * @param attributeEntityAccessor function provides access to the attribute content via {@link EntityContract}
	 */
	public record ProcessingScope(
		@Nonnull EntityIndex[] entityIndex,
		@Nullable String entityType,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable Locale locale,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull AttributeExtractor attributeEntityAccessor
	) {

		/**
		 * Returns attribute schema for attribute of passed name.
		 */
		@Nonnull
		public AttributeSchemaContract getAttributeSchema(@Nonnull String theAttributeName, @Nonnull AttributeTrait... attributeTraits) {
			return attributeSchemaAccessor.getAttributeSchema(theAttributeName, attributeTraits);
		}

		/**
		 * Returns sortable attribute compound or sortable attribute schema for attribute of passed name.
		 */
		@Nonnull
		public NamedSchemaContract getAttributeSchemaOrSortableAttributeCompound(@Nonnull String theAttributeName) {
			return attributeSchemaAccessor.getAttributeSchemaOrSortableAttributeCompound(theAttributeName);
		}

		/**
		 * Returns new attribute schema accessor that delegates lookup for attribute schema to appropriate reference
		 * schema.
		 */
		@Nonnull
		public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
			return attributeSchemaAccessor.withReferenceSchemaAccessor(referenceName);
		}

	}

}
