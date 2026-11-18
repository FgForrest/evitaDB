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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `referenceWeight` constraint weighs places reached through one named reference of the queried entity. Its
 * `attributeWeight` children replace the search weights of the reference's own searchable attributes, and its
 * `entityWeight` children the final weights of target places searched through composition. It is valid only
 * directly inside `relevanceText`.
 *
 * The reference must exist in the queried entity's schema and each weighted attribute must be searchable in every
 * scope the enclosing `relevanceText` covers. Narrow the ordering with `inScope` to cover fewer scopes. The
 * container must hold at least one weight; an empty container survives normalization so planning can refuse it.
 * Several `referenceWeight` constraints combine their places within one `relevanceText`; weighting a place twice
 * is refused. The reference name must be non-null and non-blank.
 *
 * Child weights are non-null `BigDecimal` values with scale-independent equality and plain decimal printing.
 * Each leaf weight refuses a negative value or one beyond the decimal representation bounds when it is
 * constructed, before this container exists. Planning refuses float overflow and underflow, together with the
 * schema and searched-place requirements.
 *
 * ```evitaql
 * relevanceText(
 *     referenceWeight(
 *         "brand",
 *         attributeWeight("label", 2),
 *         entityWeight(attributeWeight("name", 3))
 *     )
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "weight", shortDescription = "Overrides weights at one reference level.",
	userDocsLink = "/documentation/query/ordering/relevance#relevance-text", supportedIn = ConstraintDomain.TEXT
)
public class ReferenceWeight extends AbstractOrderConstraintContainer implements ReferenceConstraint<OrderConstraint>,
	RelevanceTextEntitySpecificationOrderConstraint {
	@Serial private static final long serialVersionUID = -710055817985495992L;

	/**
	 * Creates a weight container retaining the supplied child order, including duplicate places.
	 *
	 * @param referenceName name of the reference of the queried entity whose places are weighted, non-blank
	 * @param weights `attributeWeight` and `entityWeight` children; NULL is treated as no children, which planning
	 *                refuses
	 * @throws EvitaInvalidUsageException when the name is blank
	 */
	@Creator
	public ReferenceWeight(
		@Nonnull @Classifier String referenceName,
		@Nullable @Child(domain = ConstraintDomain.TEXT) RelevanceTextReferenceSpecificationOrderConstraint... weights
	) {
		this(new Serializable[]{referenceName}, weights == null ? new OrderConstraint[0] : weights);
	}

	/**
	 * Copies arguments and enforces the allowed child level without dropping empty containers.
	 *
	 * @param arguments the reference name as the only argument
	 * @param weights the child weights, possibly none
	 * @throws EvitaInvalidUsageException when the name is missing or blank or a child is not a weight allowed at
	 *                                    this level
	 */
	private ReferenceWeight(@Nonnull Serializable[] arguments, @Nonnull OrderConstraint[] weights) {
		super(arguments, weights);
		Assert.isTrue(arguments.length == 1 && arguments[0] instanceof String name && !name.isBlank(),
			"Constraint `referenceWeight` requires one non-blank reference name!");
		for (final OrderConstraint weight : getChildren()) {
			Assert.isTrue(weight instanceof RelevanceTextReferenceSpecificationOrderConstraint,
				() -> "Constraint `referenceWeight` does not accept `" + weight + "` as its child!");
		}
	}

	/**
	 * Returns the reference whose places are weighted.
	 *
	 * @return the non-blank reference name
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	/**
	 * Retains even empty containers so planning can refuse weights that override nothing.
	 *
	 * @return always true
	 */
	@Override
	public boolean isApplicable() {
		return true;
	}

	/**
	 * Preserves the level boundary through normalization, even with one or no children.
	 *
	 * @return always true
	 */
	@Override
	public boolean isNecessary() {
		return true;
	}

	/**
	 * Copies the arguments while retaining all child weights in order.
	 *
	 * @param newArguments the reference name as the only argument
	 * @return a new container with the given name and the current children
	 * @throws EvitaInvalidUsageException when the name is missing or blank
	 */
	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceWeight(newArguments, getChildren());
	}

	/**
	 * Copies children while enforcing this level's weight types.
	 *
	 * @param children the replacement child weights
	 * @param additionalChildren must be empty, the container has no additional children
	 * @return a new container with the current name and the given children
	 * @throws EvitaInvalidUsageException when additional children are present or a child is not allowed at this level
	 */
	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(additionalChildren.length == 0, "Constraint `referenceWeight` accepts only place weights!");
		return new ReferenceWeight(getArguments(), children);
	}
}
