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
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `entityWeight` constraint weighs places of the target collection of the enclosing `referenceWeight` that the
 * query searches through composition. Its `attributeWeight` and `associatedDataWeight` children name target
 * attributes and associated data, and each number is the final composed place weight, replacing the target search
 * weight times the reference composition weight. Target reference attributes cannot be addressed here. It is valid
 * only directly inside `referenceWeight`.
 *
 * The reference must target a managed entity collection and declare `searchableThrough(COMPOSITION)` in every scope
 * the enclosing `relevanceText` covers. Each weighted target place must be searchable in every such scope; narrow
 * the ordering with `inScope` to cover fewer scopes. A container without child weights is refused during planning.
 *
 * Child weights are non-null `BigDecimal` values with scale-independent equality and plain decimal printing.
 * Each leaf weight refuses a negative value or one beyond the decimal representation bounds when it is
 * constructed, before this container exists. Planning refuses float overflow and underflow, together with the
 * schema and searched-place requirements.
 *
 * ```evitaql
 * referenceWeight(
 *     "brand",
 *     entityWeight(attributeWeight("name", 3), associatedDataWeight("description", 0.5))
 * )
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "weight", shortDescription = "Overrides composed weights of the reference's target places.",
	userDocsLink = "/documentation/query/ordering/relevance#relevance-text", supportedIn = ConstraintDomain.TEXT
)
public class EntityWeight extends AbstractOrderConstraintContainer implements EntityConstraint<OrderConstraint>,
	RelevanceTextReferenceSpecificationOrderConstraint {
	@Serial private static final long serialVersionUID = 2901444609450427932L;

	/**
	 * Creates a weight container retaining the supplied child order, including duplicate places.
	 *
	 * @param weights `attributeWeight` and `associatedDataWeight` children; NULL is treated as no children, which
	 *                planning refuses
	 */
	@Creator
	public EntityWeight(
		@Nullable @Child(domain = ConstraintDomain.TEXT) RelevanceTextTargetSpecificationOrderConstraint... weights
	) {
		this(NO_ARGS, weights == null ? new OrderConstraint[0] : weights);
	}

	/**
	 * Copies arguments and enforces the allowed child level without dropping empty containers.
	 *
	 * @param arguments must be empty, the container has no arguments
	 * @param weights the child weights, possibly none
	 * @throws EvitaInvalidUsageException when arguments are present or a child is not a weight allowed at this level
	 */
	private EntityWeight(@Nonnull Serializable[] arguments, @Nonnull OrderConstraint[] weights) {
		super(arguments, weights);
		Assert.isTrue(arguments.length == 0,
			"Constraint `entityWeight` accepts no arguments!");
		for (final OrderConstraint weight : getChildren()) {
			Assert.isTrue(weight instanceof RelevanceTextTargetSpecificationOrderConstraint,
				() -> "Constraint `entityWeight` does not accept `" + weight + "` as its child!");
		}
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
	 * @param newArguments must be empty, the container has no arguments
	 * @return a new container with the current children
	 * @throws EvitaInvalidUsageException when arguments are present
	 */
	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new EntityWeight(newArguments, getChildren());
	}

	/**
	 * Copies children while enforcing this level's weight types.
	 *
	 * @param children the replacement child weights
	 * @param additionalChildren must be empty, the container has no additional children
	 * @return a new container with the given children
	 * @throws EvitaInvalidUsageException when additional children are present or a child is not allowed at this level
	 */
	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(
		@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(additionalChildren.length == 0, "Constraint `entityWeight` accepts only place weights!");
		return new EntityWeight(getArguments(), children);
	}
}
