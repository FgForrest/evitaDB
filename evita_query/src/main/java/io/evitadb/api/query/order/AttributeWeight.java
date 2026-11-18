/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.TextPlaceWeightSupport;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * The `attributeWeight` constraint replaces one searchable attribute weight for its enclosing `relevanceText`.
 * The enclosing constraint decides whose attribute it is: directly inside `relevanceText` an attribute of the
 * queried entity, inside `referenceWeight` an attribute of that reference, and inside `entityWeight` an attribute
 * of the reference's target collection searched through composition. A composed target weight is the final place
 * weight, replacing the target search weight times the reference composition weight. Anywhere else the query is
 * refused.
 *
 * The attribute must exist at its level and be searchable in every scope the enclosing `relevanceText` covers;
 * narrow the ordering with `inScope` to cover fewer scopes. Zero keeps matches and quality but gives no strength.
 * The weight is a non-null `BigDecimal`, normalized so scale does not affect equality or plain decimal printing.
 * The name must be non-null and non-blank.
 * Construction refuses a negative weight, more than 100 significant digits and a stripped scale outside -100 to
 * 100. Bounds are checked before rescaling; diagnostics never expand an unsupported value. Planning refuses float
 * overflow and underflow, places the schema does not make searchable in a covered scope and, when scoring text is
 * in reach, places no scoring `textMatches` searches.
 *
 * ```evitaql
 * relevanceText(
 *     attributeWeight("name", 2.5),
 *     referenceWeight("brand", attributeWeight("label", 2))
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "weight", shortDescription = "Replaces one searchable place weight.",
	userDocsLink = "/documentation/query/ordering/relevance#relevance-text", supportedIn = ConstraintDomain.TEXT,
	supportedValues = @ConstraintSupportedValues(supportedTypes = String.class, arraysSupported = true)
)
public class AttributeWeight extends AbstractOrderConstraintLeaf implements AttributeConstraint<OrderConstraint>,
	RelevanceTextEntitySpecificationOrderConstraint,
	RelevanceTextReferenceSpecificationOrderConstraint,
	RelevanceTextTargetSpecificationOrderConstraint {
	@Serial private static final long serialVersionUID = 4973094201858596574L;

	/**
	 * Creates a weight for exactly one named searchable place.
	 *
	 * @param attributeName name of the weighted attribute at the level of the enclosing constraint, non-blank
	 * @param weight non-negative decimal replacing the schema search weight, normalized without losing precision
	 * @throws EvitaInvalidUsageException when the name is null or blank, the weight is null or negative, or its
	 *                                    representation exceeds the construction bounds
	 */
	@Creator
	public AttributeWeight(@Nonnull @Classifier String attributeName, @Nonnull @Value BigDecimal weight) {
		this(new Serializable[]{attributeName, weight});
	}

	/**
	 * Copies serialized arguments while validating the name and normalizing the decimal scale.
	 *
	 * @param arguments the name followed by the weight
	 * @throws EvitaInvalidUsageException when the arguments are not exactly a non-blank name and a BigDecimal
	 */
	private AttributeWeight(@Nonnull Serializable[] arguments) {
		super(validateArguments(arguments));
	}

	/**
	 * Returns the name of the weighted place at the enclosing level.
	 *
	 * @return the non-blank attribute name
	 */
	@Nonnull
	@Override
	public String getAttributeName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns the weight replacing the schema search weight of the place.
	 *
	 * @return the normalized non-negative decimal; float overflow or underflow is refused during planning
	 */
	@Nonnull
	public BigDecimal getWeight() {
		return (BigDecimal) getArguments()[1];
	}

	/**
	 * Copies this weight with the supplied name and decimal.
	 *
	 * @param newArguments the name followed by the weight, validated like constructor arguments
	 * @return a new weight constraint carrying the arguments
	 * @throws EvitaInvalidUsageException when the arguments are not exactly a non-blank name and a BigDecimal
	 */
	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeWeight(newArguments);
	}

	/**
	 * Validates the mandatory name and decimal, then bounds and canonicalizes the weight before argument storage.
	 *
	 * @param arguments the raw arguments, expected to be a non-blank name followed by a BigDecimal
	 * @return the name and normalized decimal, with no trailing fractional zeros or negative scale
	 * @throws EvitaInvalidUsageException when the arguments have another shape or exceed decimal representation bounds
	 */
	@Nonnull
	private static Serializable[] validateArguments(@Nonnull Serializable[] arguments) {
		Assert.isTrue(arguments != null && arguments.length == 2 &&
			arguments[0] instanceof String name && !name.isBlank() && arguments[1] instanceof BigDecimal,
			"Constraint `attributeWeight` requires one non-blank name followed by a weight!");
		return new Serializable[]{arguments[0], TextPlaceWeightSupport.normalize((BigDecimal) arguments[1])};
	}
}
