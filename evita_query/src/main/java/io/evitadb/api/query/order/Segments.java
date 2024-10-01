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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `segments` allows to define multiple ordering styles in one query. Segments take the produced filtered result and
 * sort it by the order clauses defined within particular segment and extract specified number of entities from
 * the sorted output. The extracted entities are then excluded from the original result and the process is repeated
 * with the next segment until all segments are processed. If there are any entities left in the original result,
 * they are appended to the final result in the order or the primary key (ascending).
 *
 * Segments also allow to define a filtering constraint that selects only entities that are to be processed / ordered
 * by the particular segment (similar to sub-select in relational algebra except it doesn't affect the set of returned
 * result but rather their order limited to this segment).
 *
 * When segment doesn't define limit it means all entities matching the filter constraint are processed by the segment.
 * If no filter constraint is defined for the segment, all entities are processed - and if there is another segment
 * defined after this one, it will never be reached.
 *
 * Segments are not the same as multiple order clauses in the `orderBy` constraint - multiple order clauses define
 * primary, secondary, tertiary, etc. sorting order for the whole result set. Segments define multiple separate sorting
 * orders for different parts of the result set.
 *
 * Example of usage:
 *
 * 1. first 3 items in result will be sorted by orderedQuantity in descending order
 * 2. from the rest of the result, only entities having `new` attribute set to `true` will be taken, sorted randomly
 *    and only first 2 entities of those will be added to the final result
 * 3. the rest of the entities will be sorted by code and create date in ascending order
 *
 * <pre>
 * orderBy(
 *    segments(
 *       segment(
 *          orderBy(
 *             attributeNatural("orderedQuantity, DESC)
 *          ),
 *          limit(3)
 *       ),
 *       segment(
 *          entityHaving(
 *             attributeEquals("new", true)
 *          ),
 *          orderBy(
 *             random()
 *          ),
 *          limit(2)
 *       ),
 *       segment(
 *          orderBy(
 *             ascending("code"),
 *             ascending("create")
 *          )
 *       )
 *    )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/segment">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "segments",
	shortDescription = "The container encapsulates inner segments into one main prioritized constraint container that controls the output of the query.",
	userDocsLink = "/documentation/query/ordering/segment",
	supportedIn = ConstraintDomain.ENTITY
)
public class Segments extends AbstractOrderConstraintContainer implements GenericConstraint<OrderConstraint> {
	@Serial private static final long serialVersionUID = 6352220342769661652L;

	@Creator
	public Segments(@Nonnull @Child(domain = ConstraintDomain.SEGMENT) Segment... segments) {
		super(segments);
	}

	/**
	 * Returns all segments defined in this container.
	 *
	 * @return array of segments
	 */
	@Nullable
	public Segment[] getSegments() {
		return Arrays.stream(getChildren())
			.map(Segment.class::cast)
			.toArray(Segment[]::new);
	}

	@Nonnull
	@Override
	public OrderConstraint getCopyWithNewChildren(@Nonnull OrderConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(additionalChildren.length == 0, "Segments cannot have additional children!");
		return new Segments(
			Arrays.stream(children)
				.peek(it -> Assert.isPremiseValid(it instanceof Segment, "Segments can only contain segments!"))
				.map(Segment.class::cast)
				.toArray(Segment[]::new)
		);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Segments container doesn't support arguments!");
	}
}