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

package io.evitadb.api.query.order;

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Sorts returned entities by values in attribute with name passed in the first argument
 * and optionally order direction in second. First argument must be of {@link String} type. Second argument must be one of
 * {@link OrderDirection} enum otherwise {@link OrderDirection#ASC} is the default.
 *
 * Ordering is executed by natural order of the {@link Comparable}
 * type.
 *
 * Example:
 *
 * ```
 * attribute('married')
 * attribute('age', ASC)
 * ```
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
    name = "natural",
    shortDescription = "The constraint sorts returned entities by natural ordering of the values in the specified attribute.",
    supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE },
    supportedValues = @ConstraintSupportedValues(allTypesSupported = true)
)
public class AttributeNatural extends AbstractOrderConstraintLeaf implements AttributeConstraint<OrderConstraint> {

    @Serial
    private static final long serialVersionUID = -7066718742077227665L;

    private AttributeNatural(Serializable... arguments) {
        super(arguments);
    }

    public AttributeNatural(@Nonnull String attributeName) {
        super(attributeName, OrderDirection.ASC);
    }

    @Creator
    public AttributeNatural(@Nonnull @Classifier String attributeName, @Nonnull @Value OrderDirection orderDirection) {
        super(attributeName, orderDirection);
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
