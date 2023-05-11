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

package io.evitadb.core.query.sort;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.order.Random;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.PrefetchRequirementCollector;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeExtractor;
import io.evitadb.core.query.sort.attribute.translator.AttributeNaturalTranslator;
import io.evitadb.core.query.sort.attribute.translator.EntityAttributeExtractor;
import io.evitadb.core.query.sort.attribute.translator.ReferencePropertyTranslator;
import io.evitadb.core.query.sort.price.translator.PriceNaturalTranslator;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.core.query.sort.translator.RandomTranslator;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.Supplier;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.ofNullable;

/**
 * This {@link ConstraintVisitor} translates tree of {@link OrderConstraint} to a composition of {@link Sorter}
 * Visitor represents the "planning" phase for the ordering resolution. The planning should be as light-weight as
 * possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class OrderByVisitor implements ConstraintVisitor {
	private static final Map<Class<? extends OrderConstraint>, OrderingConstraintTranslator<? extends OrderConstraint>> TRANSLATORS;

	/* initialize list of all FilterableConstraint handlers once for a lifetime */
	static {
		TRANSLATORS = CollectionUtils.createHashMap(8);
		TRANSLATORS.put(OrderBy.class, new OrderByTranslator());
		TRANSLATORS.put(AttributeNatural.class, new AttributeNaturalTranslator());
		TRANSLATORS.put(ReferenceProperty.class, new ReferencePropertyTranslator());
		TRANSLATORS.put(Random.class, new RandomTranslator());
		TRANSLATORS.put(PriceNatural.class, new PriceNaturalTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Getter @Delegate private final QueryContext queryContext;
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
	private final Deque<ProcessingScope> scope = new LinkedList<>();
	/**
	 * Contains the created sorter from the ordering query source tree.
	 */
	private Sorter sorter;

	public OrderByVisitor(
		@Nonnull QueryContext queryContext,
		@Nonnull PrefetchRequirementCollector prefetchRequirementCollector,
		@Nonnull Formula filteringFormula
	) {
		this(
			queryContext, prefetchRequirementCollector, filteringFormula,
			new AttributeSchemaAccessor(queryContext)
		);
	}

	public OrderByVisitor(
		@Nonnull QueryContext queryContext,
		@Nonnull PrefetchRequirementCollector prefetchRequirementCollector,
		@Nonnull Formula filteringFormula,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor) {
		this.queryContext = queryContext;
		this.prefetchRequirementCollector = prefetchRequirementCollector;
		this.filteringFormula = filteringFormula;
		scope.push(
			new ProcessingScope(
				this.queryContext.getGlobalEntityIndexIfExits(),
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
		return ofNullable(sorter).orElse(NoSorter.INSTANCE);
	}

	/**
	 * Returns last computed sorter. Method is targeted for internal usage by translators and is not expected to be
	 * called from anywhere else.
	 */
	@Nullable
	public Sorter getLastUsedSorter() {
		return sorter;
	}

	/**
	 * Sets different {@link EntityIndex} to be used in scope of lambda.
	 */
	public final <T> T executeInContext(
		@Nonnull EntityIndex entityIndex,
		@Nonnull AttributeSchemaAccessor attributeSchemaAccessor,
		@Nonnull AttributeExtractor attributeSchemaEntityAccessor,
		@Nonnull Supplier<T> lambda
	) {
		try {
			this.scope.push(
				new ProcessingScope(
					entityIndex,
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
			throw new EvitaInternalError("Scope should never be empty");
		} else {
			return scope.peek();
		}
	}

	/**
	 * Returns index which is best suited for supplying {@link SortIndex}.
	 */
	public EntityIndex getIndexForSort() {
		final ProcessingScope theScope = this.scope.peek();
		isPremiseValid(theScope != null, "Scope is unexpectedly empty!");
		return theScope.entityIndex();
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final OrderConstraint orderConstraint = (OrderConstraint) constraint;

		@SuppressWarnings("unchecked") final OrderingConstraintTranslator<OrderConstraint> translator =
			(OrderingConstraintTranslator<OrderConstraint>) TRANSLATORS.get(orderConstraint.getClass());
		isPremiseValid(
			translator != null,
			"No translator found for query `" + orderConstraint.getClass() + "`!"
		);

		// if query is a container query
		if (orderConstraint instanceof ConstraintContainer) {
			@SuppressWarnings("unchecked") final ConstraintContainer<OrderConstraint> container = (ConstraintContainer<OrderConstraint>) orderConstraint;
			// process children constraints
			if (!(translator instanceof SelfTraversingTranslator)) {
				for (OrderConstraint subConstraint : container) {
					subConstraint.accept(this);
				}
			}
			// process the container query itself
			sorter = translator.createSorter(orderConstraint, this);
		} else if (orderConstraint instanceof ConstraintLeaf) {
			// process the leaf query
			sorter = translator.createSorter(orderConstraint, this);
		} else {
			// sanity check only
			throw new EvitaInternalError("Should never happen");
		}
	}

	/**
	 * Processing scope contains contextual information that could be overridden in {@link OrderingConstraintTranslator}
	 * implementations to exchange indexes that are being used, suppressing certain query evaluation or accessing
	 * attribute schema information.
	 *
	 * @param entityIndex             Contains index, that should be used for accessing {@link SortIndex}.
	 * @param attributeSchemaAccessor consumer verifies prerequisites in attribute schema via {@link AttributeSchemaContract}
	 * @param attributeEntityAccessor function provides access to the attribute content via {@link EntityContract}
	 */
	public record ProcessingScope(
		@Nullable EntityIndex entityIndex,
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
		 * Returns new attribute schema accessor that delegates lookup for attribute schema to appropriate reference
		 * schema.
		 */
		@Nonnull
		public AttributeSchemaAccessor withReferenceSchemaAccessor(@Nonnull String referenceName) {
			return attributeSchemaAccessor.withReferenceSchemaAccessor(referenceName);
		}

	}

}
