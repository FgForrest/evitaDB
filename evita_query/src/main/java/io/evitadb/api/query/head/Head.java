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
 * The `head` container groups multiple head constraints (collection + labels) in the query header section.
 *
 * This container is usually not necessary when only the {@link Collection} constraint is needed — in such cases,
 * the collection can appear directly in the query without wrapping it in `head()`. However, the `head` container
 * becomes necessary when you want to tag the query with custom labels via {@link Label} constraints alongside
 * the collection specification, since labels are only allowed in the header part of the query.
 *
 * The header is one of four logical parts that constitute an evitaDB query:
 * 1. **Header** (head) — specifies the target entity collection and optional query labels
 * 2. **Filter** (filterBy) — constraints limiting which entities are returned
 * 3. **Order** (orderBy) — defines the sequence of returned entities
 * 4. **Require** (require) — additional processing instructions (data fetching, statistics, pagination)
 *
 * Example:
 *
 * ```evitaql
 * query(
 *    head(
 *       collection('Product'),
 *       label('query-name', 'List all products'),
 *       label('page-url', '/products')
 *    ),
 *    filterBy(
 *       attributeEquals('visible', true)
 *    )
 * )
 * ```
 *
 * The container accepts any number of {@link HeadConstraint} children but typically contains one {@link Collection}
 * and zero or more {@link Label} constraints.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/header/header#head)
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

	/**
	 * Creates a copy of this head container with the given children. Throws if any additional children
	 * are provided, since the head container does not support them.
	 */
	@Nonnull
	@Override
	public HeadConstraint getCopyWithNewChildren(@Nonnull HeadConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(
			additionalChildren.length == 0,
			"Head of the query allows no additional children!"
		);
		return new Head(children);
	}

	/**
	 * Not supported — the head container accepts no arguments. Always throws {@link UnsupportedOperationException}.
	 */
	@Nonnull
	@Override
	public HeadConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Head constraint container allows no arguments!");
	}

}
