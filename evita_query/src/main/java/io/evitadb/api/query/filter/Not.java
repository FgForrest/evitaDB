/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `not` container represents a <a href="https://en.wikipedia.org/wiki/Negation">logical negation</a>, that is
 * demonstrated on following table:
 *
 * <table>
 *     <thead>
 *         <tr>
 *             <th align="center">A</th>
 *             <th align="center">¬ A</th>
 *         </tr>
 *     </thead>
 *     <tbody>
 *         <tr>
 *             <td align="center">True</td>
 *             <td align="center">False</td>
 *         </tr>
 *         <tr>
 *             <td align="center">False</td>
 *             <td align="center">True</td>
 *         </tr>
 *     </tbody>
 * </table>
 *
 * The following query:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         not(
 *             entityPrimaryKeyInSet(110066, 106742, 110513)
 *         )
 *     )
 * )
 * </pre>
 *
 * ... returns thousands of results excluding the entities with primary keys mentioned in `entityPrimaryKeyInSet`
 * constraint. Because this situation is hard to visualize - let"s narrow our super set to only a few entities:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityPrimaryKeyInSet(110513, 66567, 106742, 66574, 66556, 110066),
 *         not(
 *             entityPrimaryKeyInSet(110066, 106742, 110513)
 *         )
 *     )
 * )
 * </pre>
 *
 * ... which returns only three products that were not excluded by the following `not` constraint.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/logical#not">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "not",
	shortDescription = "The container that behaves as [logical NOT](https://en.wikipedia.org/wiki/Negation) for the inner constraint.",
	userDocsLink = "/documentation/query/filtering/logical#not",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET }
)
public class Not extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 7151549459608672988L;

	/**
	 * Private constructor that creates unnecesary / not applicable version of the query.
	 */
	private Not() {}

	@Creator
	public Not(@Nonnull FilterConstraint child) {
		super(child);
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Nonnull
	public FilterConstraint getChild() {
		return getChildren()[0];
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return children.length == 0 ? new Not() : new Not(children[0]);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Not filtering query has no arguments!");
	}

}
