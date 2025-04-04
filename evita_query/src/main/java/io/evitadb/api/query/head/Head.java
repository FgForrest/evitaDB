/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.head;


import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `head` is container for introducing multiple head constraints in query header. It's usually not necessary to use,
 * because the query header can contain directly {@link Collection} constraint. But if you want to tag the query for
 * further investigation with some custom labels, you'd need this container, since {@link Label} constraint is allowed
 * only in the header part of the query.
 *
 * Example:
 *
 * <pre>
 * query(
 *     head(
 *        collection("product"),
 *        label("query-name", "List all products")
 *     )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/header/header#head">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@ConstraintDefinition(
	name = "head",
	shortDescription = "The container encapsulates inner head constraints into one main constraint that is required by the query.",
	userDocsLink = "/documentation/query/header/header#head"
)
public class Head extends AbstractHeadConstraintContainer implements GenericConstraint<HeadConstraint> {
	@Serial private static final long serialVersionUID = -3870428448982728781L;

	@Creator
	public Head(@Nonnull HeadConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public HeadConstraint getCopyWithNewChildren(@Nonnull HeadConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(
			additionalChildren.length == 0,
			"Head of the query allows no additional children!"
		);
		return new Head(children);
	}

	@Nonnull
	@Override
	public HeadConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Head constraint container allows no arguments!");
	}

}
