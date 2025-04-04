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
 * The `and` container represents a <a href="https://en.wikipedia.org/wiki/Logical_conjunction">logical conjunction</a>,
 * that is demonstrated on following table:
 *
 * <table>
 *     <thead>
 *         <tr>
 *             <th align="center">A</th>
 *             <th align="center">B</th>
 *             <th align="center">A ∧ B</th>
 *         </tr>
 *     </thead>
 *     <tbody>
 *         <tr>
 *             <td align="center">True</td>
 *             <td align="center">True</td>
 *             <td align="center">True</td>
 *         </tr>
 *         <tr>
 *             <td align="center">True</td>
 *             <td align="center">False</td>
 *             <td align="center">False</td>
 *         </tr>
 *         <tr>
 *             <td align="center">False</td>
 *             <td align="center">True</td>
 *             <td align="center">False</td>
 *         </tr>
 *         <tr>
 *             <td align="center">False</td>
 *             <td align="center">False</td>
 *             <td align="center">False</td>
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
 *         and(
 *             entityPrimaryKeyInSet(110066, 106742, 110513),
 *             entityPrimaryKeyInSet(110066, 106742),
 *             entityPrimaryKeyInSet(107546, 106742,  107546)
 *         )
 *     )
 * )
 * </pre>
 *
 * ... returns a single result - product with entity primary key 106742, which is the only one that all three
 * `entityPrimaryKeyInSet` constraints have in common.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/logical#and">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "and",
	shortDescription = "The container that combines inner constraints with [logical AND](https://en.wikipedia.org/wiki/Logical_conjunction).",
	userDocsLink = "/documentation/query/filtering/logical#and",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET }
)
public class And extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -3383976355275556890L;

	@Creator
	public And(@Nonnull FilterConstraint... children) {
		super(children);
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		return new And(children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("And filtering query has no arguments!");
	}

}
