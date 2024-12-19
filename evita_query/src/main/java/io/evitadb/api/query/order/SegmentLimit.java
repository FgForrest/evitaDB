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

package io.evitadb.api.query.order;

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The distance constraint can only be used within the {@link Segment} container and limits the number or entities
 * in particular segment.
 *
 * See the following figure - the limit constraint narrows the result set to only 3 entities:
 *
 * <pre>
 * orderBy(
 *    segment(
 *       orderBy(
 *          attributeNatural("orderedQuantity, DESC)
 *       ),
 *       limit(3)
 *    ),
 *    segment(
 *       orderBy(
 *          ascending("code"),
 *          ascending("create")
 *       )
 *    )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/segment#limit">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "limit",
	shortDescription = "The constraint limits the number of entities in particular segment of the output.",
	userDocsLink = "/documentation/query/ordering/segment#limit",
	supportedIn = ConstraintDomain.SEGMENT
)
public class SegmentLimit extends AbstractOrderConstraintLeaf implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 3540225030475545553L;
	private static final String CONSTRAINT_NAME = "limit";

	private SegmentLimit(Serializable... arguments) {
		// because this query can be used only within some other segment query, it would be
		// unnecessary to duplicate the segment prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public SegmentLimit(int limit) {
		// because this query can be used only within some other segment query, it would be
		// unnecessary to duplicate the segment prefix
		super(CONSTRAINT_NAME, limit);
		Assert.isTrue(limit > 0, () -> new EvitaInvalidUsageException("Segment limit must be greater than zero."));
	}

	/**
	 * Returns limit value constraining the number of items in particular segment.
	 */
	public int getLimit() {
		return (Integer) getArguments()[0];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 1;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof Integer,
			"SegmentLimit container accepts only single integer argument!"
		);
		return new SegmentLimit(newArguments);
	}

}