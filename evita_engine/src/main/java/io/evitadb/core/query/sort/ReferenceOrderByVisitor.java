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
import io.evitadb.api.query.order.EntityGroupProperty;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.attribute.translator.AttributeNaturalTranslator;
import io.evitadb.core.query.sort.attribute.translator.EntityGroupPropertyTranslator;
import io.evitadb.core.query.sort.attribute.translator.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.attribute.translator.EntityPropertyTranslator;
import io.evitadb.core.query.sort.translator.OrderByTranslator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.ofNullable;

/**
 * This {@link ConstraintVisitor} translates tree of {@link OrderConstraint} to a {@link OrderingDescriptor} record
 * allowing to access the comparator for reference attributes and for referenced entity as well. The visitor is used
 * only from {@link ReferenceFetcher} implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceOrderByVisitor implements ConstraintVisitor {
	private static final Map<Class<? extends OrderConstraint>, ReferenceOrderingConstraintTranslator<? extends OrderConstraint>> TRANSLATORS;

	/* initialize list of all OrderConstraints handlers once for a lifetime */
	static {
		TRANSLATORS = CollectionUtils.createHashMap(8);
		TRANSLATORS.put(OrderBy.class, new OrderByTranslator());
		TRANSLATORS.put(AttributeNatural.class, new AttributeNaturalTranslator());
		TRANSLATORS.put(EntityProperty.class, new EntityPropertyTranslator());
		TRANSLATORS.put(EntityGroupProperty.class, new EntityGroupPropertyTranslator());
	}

	/**
	 * Reference to the query context that allows to access entity bodies, indexes, original request and much more.
	 */
	@Delegate private final QueryContext queryContext;
	/**
	 * Pre-initialized comparator initialized during entity filtering (if it's performed) allowing to order references
	 * by sorter defined on referenced entity (requiring nested query).
	 */
	private EntityNestedQueryComparator nestedQueryComparator;
	/**
	 * Contains the created comparator from the ordering query source tree.
	 */
	private ReferenceComparator comparator;

	/**
	 * Extracts {@link OrderingDescriptor} from the passed `orderBy` constraint using passed `queryContext` for
	 * extraction.
	 */
	@Nonnull
	public static OrderingDescriptor getComparator(
		@Nonnull QueryContext queryContext,
		@Nonnull OrderConstraint orderBy
	) {
		final ReferenceOrderByVisitor orderVisitor = new ReferenceOrderByVisitor(queryContext);
		orderBy.accept(orderVisitor);
		return orderVisitor.getComparator();
	}

	/**
	 * Returns the created sorter from the ordering query source tree or default {@link ReferenceComparator#DEFAULT}
	 * instance.
	 */
	@Nonnull
	public OrderingDescriptor getComparator() {
		return new OrderingDescriptor(
			ofNullable(comparator).orElse(ReferenceComparator.DEFAULT),
			nestedQueryComparator
		);
	}

	/**
	 * Method returns a nested query comparator for sorting along properties of referenced entity or group.
	 * @return nested query comparator or null if no nested query comparator was created
	 */
	@Nonnull
	public EntityNestedQueryComparator getOrCreateNestedQueryComparator() {
		if (this.nestedQueryComparator == null) {
			this.nestedQueryComparator = new EntityNestedQueryComparator();
			this.addComparator(this.nestedQueryComparator);
		}
		return this.nestedQueryComparator;
	}

	/**
	 * Method appends a comparator for comparing the attributes.
	 * @param comparator comparator to be appended
	 */
	public void addComparator(@Nonnull ReferenceComparator comparator) {
		if (this.comparator == null) {
			this.comparator = comparator;
		} else {
			this.comparator = this.comparator.andThen(comparator);
		}
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final OrderConstraint orderConstraint = (OrderConstraint) constraint;

		@SuppressWarnings("unchecked") final ReferenceOrderingConstraintTranslator<OrderConstraint> translator =
			(ReferenceOrderingConstraintTranslator<OrderConstraint>) TRANSLATORS.get(orderConstraint.getClass());
		isPremiseValid(
			translator != null,
			"No translator found for constraint `" + orderConstraint.getClass() + "`!"
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
			translator.createComparator(orderConstraint, this);
		} else if (orderConstraint instanceof ConstraintLeaf) {
			// process the leaf query
			translator.createComparator(orderConstraint, this);
		} else {
			// sanity check only
			throw new GenericEvitaInternalError("Should never happen");
		}
	}

	/**
	 * DTO record enveloping comparators both for attributes on reference itself and the referenced entity.
	 * Currently, the sorting allows to use only simple ordering constraints either on reference attributes or
	 * the referenced entity itself. It doesn't allow to combine them or create more complex orderings.
	 * This is the work in progress ...
	 */
	public record OrderingDescriptor(
		@Nonnull ReferenceComparator comparator,
		@Nullable EntityNestedQueryComparator nestedQueryComparator
	) {
	}

}
