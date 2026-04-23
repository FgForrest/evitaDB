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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Filters entities by exact equality of a named attribute value to a specified comparable value. This is the most
 * fundamental attribute comparison constraint in the query language, used for precise matching against filterable or
 * unique attributes stored in the entity schema.
 *
 * The constraint performs type-safe equality comparison between the attribute value and the provided comparison value.
 * Both values must be convertible to a common comparable type via {@link io.evitadb.dataType.EvitaDataTypes}, or the
 * constraint evaluates to false. String comparisons are case-sensitive and follow natural ordering or locale-specific
 * collation rules for localized attributes. Range types compare using left boundary first, then right boundary.
 * Boolean values are treated as numeric (true=1, false=0) for comparison purposes.
 *
 * **EvitaQL syntax:**
 *
 * ```
 * attributeEquals(attributeName:string!, value:comparable!)
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
 * When the attribute is array-typed, the constraint matches if **any** element in the array equals the comparison
 * value. For example, given `code=["A", "B", "C"]`, all of these constraints match:
 *
 * ```
 * attributeEquals("code", "A")
 * attributeEquals("code", "B")
 * attributeEquals("code", "C")
 * ```
 *
 * **Common use cases:**
 *
 * - Filtering by unique identifiers: `attributeEquals("sku", "PROD-12345")`
 * - Exact status matching: `attributeEquals("status", "PUBLISHED")`
 * - Boolean flags: `attributeEquals("featured", true)`
 * - Numeric exact matches: `attributeEquals("quantity", 0)`
 * - Enum value filtering: `attributeEquals("color", "RED")`
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-equals)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "equals",
	shortDescription = "Compares value of the attribute with passed value and checks if they are both equal.",
	userDocsLink = "/documentation/query/filtering/comparable#attribute-equals",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE },
	supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeEquals extends AbstractAttributeFilterComparisonConstraintLeaf implements FilterConstraint {
	@Serial private static final long serialVersionUID = 3928023999412612529L;

	private AttributeEquals(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public <T extends Serializable> AttributeEquals(@Nonnull @Classifier String attributeName,
	                                                @Nonnull T attributeValue) {
		super(attributeName, attributeValue);
	}

	/**
	 * Returns value that must be equal to the attribute value.
	 */
	@Nonnull
	public <T extends Serializable> T getAttributeValue() {
		//noinspection unchecked
		return (T) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeEquals(newArguments);
	}

}
