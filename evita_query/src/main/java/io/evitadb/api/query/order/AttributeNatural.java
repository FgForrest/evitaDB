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

package io.evitadb.api.query.order;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint allows output entities to be sorted by their attributes in their natural order (numeric, alphabetical,
 * temporal). It requires specification of a single attribute and the direction of the ordering (default ordering is
 * {@link OrderDirection#ASC}).
 *
 * Ordering is executed by natural order of the {@link Comparable} type.
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     orderBy(
 *         attributeNatural("orderedQuantity", DESC)
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code", "orderedQuantity")
 *         )
 *     )
 * )
 * </pre>
 *
 * If you want to sort products by their name, which is a localized attribute, you need to specify the {@link EntityLocaleEquals}
 * constraint in the {@link FilterBy} part of the query. The correct {@link java.text.Collator} is used to
 * order the localized attribute string, so that the order is consistent with the national customs of the language.
 *
 * The sorting mechanism of evitaDB is somewhat different from what you might be used to. If you sort entities by two
 * attributes in an `orderBy` clause of the query, evitaDB sorts them first by the first attribute (if present) and then
 * by the second (but only those where the first attribute is missing). If two entities have the same value of the first
 * attribute, they are not sorted by the second attribute, but by the primary key (in ascending order).
 *
 * If we want to use fast "pre-sorted" indexes, there is no other way to do it, because the secondary order would not be
 * known until a query time. If you want to sort by multiple attributes in the conventional way, you need to define the
 * sortable attribute compound in advance and use its name instead of the default attribute name. The sortable attribute
 * compound will cover multiple attributes and prepares a special sort index for this particular combination of
 * attributes, respecting the predefined order and NULL values behaviour. In the query, you can then use the compound
 * name instead of the default attribute name and achieve the expected results.
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/comparable#attribute-natural">Visit detailed user documentation</a></p>
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
    name = "natural",
    shortDescription = "The constraint sorts returned entities by natural ordering of the values in the specified attribute.",
    userDocsLink = "/documentation/query/ordering/comparable#attribute-natural",
    supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
    supportedValues = @ConstraintSupportedValues(allTypesSupported = true, compoundsSupported = true)
)
public class AttributeNatural extends AbstractOrderConstraintLeaf implements AttributeConstraint<OrderConstraint> {

    @Serial
    private static final long serialVersionUID = -7066718742077227665L;

    private AttributeNatural(Serializable... arguments) {
        super(arguments);
    }

    public AttributeNatural(@Nonnull String attributeName) {
        super(new Serializable[] { attributeName, OrderDirection.ASC });
    }

    @Creator
    public AttributeNatural(@Nonnull @Classifier String attributeName, @Nonnull OrderDirection orderDirection) {
        super(new Serializable[] { attributeName, orderDirection });
    }

    @Override
    public boolean isApplicable() {
        return isArgumentsNonNull() && getArguments().length == 2;
    }

    /**
     * Returns attribute name that needs to be examined.
     */
    @Nonnull
    public String getAttributeName() {
        return (String) getArguments()[0];
    }

    /**
     * Returns order direction of how the attribute values must be sorted.
     */
    @Nonnull
    public OrderDirection getOrderDirection() {
        return (OrderDirection) getArguments()[1];
    }

    @Nonnull
    @Override
    public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
        return new AttributeNatural(newArguments);
    }
}
