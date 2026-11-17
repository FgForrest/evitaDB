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

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `attributeWeight` constraint replaces the schema search weight of one searchable attribute for this query.
 * The enclosing constraint decides whose attribute it is: directly inside `relevanceText` an attribute of the
 * queried entity, inside `referenceWeight` an attribute of that reference, and inside `entityWeight` an attribute
 * of the reference's target collection searched through composition. A composed target weight is the final place
 * weight, replacing the target search weight times the reference composition weight. Anywhere else the query is
 * refused.
 *
 * The attribute must exist at its level and be searchable in at least one scope the query processes, otherwise
 * planning refuses the query. Zero keeps the place's matches and match quality but gives it no strength. Negative
 * weights are refused during planning, non-finite numbers already during construction. The name must be non-null
 * and non-blank.
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
	name = "attributeWeight", shortDescription = "Replaces one searchable place weight.",
	userDocsLink = "/documentation/query/ordering/relevance#relevance-text", supportedIn = ConstraintDomain.ENTITY
)
public class AttributeWeight extends AbstractOrderConstraintLeaf implements GenericConstraint<OrderConstraint>,
	RelevanceTextEntitySpecificationOrderConstraint,
	RelevanceTextReferenceSpecificationOrderConstraint,
	RelevanceTextTargetSpecificationOrderConstraint {
	@Serial private static final long serialVersionUID = 4973094201858596574L;

	/**
	 * Creates a weight for exactly one named searchable place.
	 *
	 * @param attributeName name of the weighted attribute at the level of the enclosing constraint, non-blank
	 * @param weight number replacing the schema search weight; negative values are accepted here and refused during
	 *               planning, negative zero is stored as positive zero
	 * @throws EvitaInvalidUsageException when the name is blank or the weight is not a finite number
	 */
	@Creator
	public AttributeWeight(@Nonnull @Classifier String attributeName, @Value float weight) {
		this(new Serializable[]{attributeName, weight + 0.0f});
	}

	/**
	 * Copies serialized arguments while preserving name validation and canonical positive zero.
	 *
	 * @param arguments the name followed by the weight
	 * @throws EvitaInvalidUsageException when the arguments are not exactly a non-blank name and a number
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
	public String getAttributeName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns the weight replacing the schema search weight of the place.
	 *
	 * @return a finite number, never negative zero; a negative weight is kept here and refused during planning
	 */
	public float getWeight() {
		return ((Number) getArguments()[1]).floatValue();
	}

	/**
	 * Copies this weight with the supplied name and number.
	 *
	 * @param newArguments the name followed by the weight, validated like constructor arguments
	 * @return a new weight constraint carrying the arguments
	 * @throws EvitaInvalidUsageException when the arguments are not exactly a non-blank name and a number
	 */
	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new AttributeWeight(newArguments);
	}

	/**
	 * Validates the mandatory name and numeric shape before superclass normalization.
	 *
	 * @param arguments the raw arguments, expected to be a non-blank name followed by a number
	 * @return the name and the weight as a float with negative zero turned into positive zero
	 * @throws EvitaInvalidUsageException when the arguments have another shape, including NULL
	 */
	@Nonnull
	private static Serializable[] validateArguments(@Nonnull Serializable[] arguments) {
		Assert.isTrue(arguments != null && arguments.length == 2 &&
			arguments[0] instanceof String name && !name.isBlank() && arguments[1] instanceof Number,
			"Constraint `attributeWeight` requires one non-blank name followed by a weight!");
		return new Serializable[]{arguments[0], ((Number) arguments[1]).floatValue() + 0.0f};
	}
}
