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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.ConstraintSupportedValues;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Filters entities where a named attribute value matches at least one value from a provided set of comparable values.
 * This constraint is functionally equivalent to a disjunction (logical OR) of multiple {@link AttributeEquals}
 * constraints, but offers significantly better performance and more concise syntax when filtering against multiple
 * discrete values.
 *
 * The constraint performs type-safe equality comparison via {@link EvitaDataTypes} conversion.
 * The attribute value must be convertible to the type of at least one value in the provided set, otherwise that value
 * is skipped. If no values are type-compatible, the constraint evaluates to false. String comparisons are
 * case-sensitive. Range types compare using left boundary first, then right boundary. Boolean values are treated as
 * numeric (true=1, false=0).
 *
 * **EvitaQL syntax:**
 *
 * ```
 * attributeInSet(attributeName:string!, value1:comparable!, value2:comparable!, ...)
 * ```
 *
 * **Constraint classification:**
 *
 * - Implements {@link FilterConstraint} - usable in filterBy clauses
 * - Implements {@link io.evitadb.api.query.AttributeConstraint} - operates on named attributes
 * - Supported in: {@link ConstraintDomain#ENTITY}, {@link ConstraintDomain#REFERENCE},
 *   {@link ConstraintDomain#INLINE_REFERENCE}
 *
 * **Array attribute handling:**
 *
 * When the attribute is array-typed, the constraint matches if **any** element in the attribute array equals **any**
 * value in the query set (set intersection is non-empty). For example, given `code=["A", "B", "C"]`, these constraints
 * match:
 *
 * ```
 * attributeInSet("code", "A", "D")     // "A" matches
 * attributeInSet("code", "A", "B")     // Both "A" and "B" match
 * attributeInSet("code", "D", "E")     // No match (would return false)
 * ```
 *
 * **Common use cases:**
 *
 * - Multi-select filtering: `attributeInSet("category", "Electronics", "Computers", "Phones")`
 * - Status filtering: `attributeInSet("status", "PUBLISHED", "FEATURED", "PROMOTED")`
 * - Tag-based filtering: `attributeInSet("tags", "sale", "clearance", "discount")`
 * - Enum value matching: `attributeInSet("size", "SMALL", "MEDIUM")`
 * - Discrete numeric ranges: `attributeInSet("priority", 1, 2, 3)`
 *
 * **Advantages over multiple AttributeEquals:**
 *
 * Instead of:
 *
 * ```
 * or(
 *     attributeEquals("status", "PUBLISHED"),
 *     attributeEquals("status", "FEATURED"),
 *     attributeEquals("status", "PROMOTED")
 * )
 * ```
 *
 * Use:
 *
 * ```
 * attributeInSet("status", "PUBLISHED", "FEATURED", "PROMOTED")
 * ```
 *
 * Benefits:
 * - More concise and readable syntax
 * - Single index lookup instead of multiple (set membership test)
 * - Better query optimization opportunities
 * - Reduced constraint tree depth for large value sets
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-in-set)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "inSet",
	shortDescription = "Compares value of the attribute with passed value and checks if the value of that attribute " +
		"equals to at least one of the passed values. " +
		"The constraint is equivalent to the multiple `equals` constraints combined with logical OR.",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-in-set",
	supportedIn = {ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE},
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeInSet extends AbstractAttributeFilterConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = 500395477991778874L;

	private AttributeInSet(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Serializable> AttributeInSet(
		@Nonnull @Classifier String attributeName,
		@Nonnull T... attributeValues
	) {
		super(concat(attributeName, attributeValues));
	}

	/**
	 * Returns set of {@link Serializable} values that attribute value must be part of.
	 */
	@Nonnull
	public Serializable[] getAttributeValues() {
		final Serializable[] arguments = getArguments();
		return Arrays.copyOfRange(arguments, 1, arguments.length);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeInSet(newArguments);
	}
}
