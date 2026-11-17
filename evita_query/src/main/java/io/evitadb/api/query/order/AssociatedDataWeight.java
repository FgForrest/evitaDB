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
import java.math.BigDecimal;

/**
 * The `associatedDataWeight` constraint replaces one associated data weight for its enclosing `relevanceText`.
 * Directly inside `relevanceText` the data belongs to the queried entity; inside `entityWeight` it belongs to the
 * reference's target collection searched through composition. A composed target weight is final, replacing the
 * target search weight times the reference composition weight. Anywhere else the query is refused.
 *
 * The associated data must exist at its level and be searchable in every scope the enclosing `relevanceText`
 * covers; narrow the ordering with `inScope` to cover fewer scopes. Zero keeps matches and quality but gives no
 * strength.
 * Negative weights and values that overflow or underflow the internal float are refused during planning. The weight
 * is a non-null `BigDecimal`, normalized so scale does not affect equality or plain decimal printing. The name must
 * be non-null and non-blank.
 * Construction requires at most 100 significant digits and a stripped scale between -100 and 100. Bounds are
 * checked before rescaling; diagnostics never expand an unsupported value.
 *
 * ```evitaql
 * relevanceText(
 *     associatedDataWeight("description", 0.5)
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "associatedDataWeight", shortDescription = "Replaces one searchable place weight.",
	userDocsLink = "/documentation/query/ordering/relevance#relevance-text", supportedIn = ConstraintDomain.ENTITY
)
public class AssociatedDataWeight extends AbstractOrderConstraintLeaf implements GenericConstraint<OrderConstraint>,
	RelevanceTextEntitySpecificationOrderConstraint,
	RelevanceTextTargetSpecificationOrderConstraint {
	@Serial private static final long serialVersionUID = 5675195514389518083L;

	/**
	 * Creates a weight for exactly one named searchable place.
	 *
	 * @param associatedDataName name of the weighted associated data item at the level of the enclosing constraint,
	 *                           non-blank
	 * @param weight number replacing the schema search weight; negative values are accepted here and refused during
	 *               planning; scale is normalized without losing decimal precision
	 * @throws EvitaInvalidUsageException when the name is null or blank, the weight is null, or its representation
	 *                                    exceeds the construction bounds
	 */
	@Creator
	public AssociatedDataWeight(@Nonnull @Classifier String associatedDataName, @Nonnull @Value BigDecimal weight) {
		this(new Serializable[]{associatedDataName, weight});
	}

	/**
	 * Copies serialized arguments while validating the name and normalizing the decimal scale.
	 *
	 * @param arguments the name followed by the weight
	 * @throws EvitaInvalidUsageException when the arguments are not exactly a non-blank name and a BigDecimal
	 */
	private AssociatedDataWeight(@Nonnull Serializable[] arguments) {
		super(validateArguments(arguments));
	}

	/**
	 * Returns the name of the weighted place at the enclosing level.
	 *
	 * @return the non-blank associated data item name
	 */
	@Nonnull
	public String getAssociatedDataName() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns the weight replacing the schema search weight of the place.
	 *
	 * @return the normalized decimal; negative values and float overflow or underflow are refused during planning
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
		return new AssociatedDataWeight(newArguments);
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
			"Constraint `associatedDataWeight` requires one non-blank name followed by a weight!");
		return new Serializable[]{arguments[0], TextPlaceWeightSupport.normalize((BigDecimal) arguments[1])};
	}
}
