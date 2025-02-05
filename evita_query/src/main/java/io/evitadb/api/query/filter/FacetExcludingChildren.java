/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * TODO JNO - Update documentation
 * The constraint `includingChildren` is a constraint that can only be used within {@link FacetHaving} parent constraint.
 * It simply makes no sense anywhere else because it changes the default behavior of this constraint. Facet having
 * filters entities that have a direct reference to matching faceted entity. When the `includingChildren` constraint is
 * used, the query will return all entities that have a direct reference to the matching entity or any of its children
 * in the hierarchy.
 *
 * This constraint cannot be used for references to non-hierarchical entities - in such case the query will return an
 * error.
 *
 * Example:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         facetHaving(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             includingChildren()
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * This query will match all products that have reference to the category with code "accessories" or any of its children.
 * The {@link FacetSummary} will take references to any of the category children into account when calculating the impact
 * of category facet selection.
 *
 * It's also possible to specify sub-constraint that each of the child must satisfy in order to be included in selection.
 * This can be done by adding suffix `Having` and additional constraints to the `includingChildren` constraint:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         facetHaving(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             includingChildrenHaving(
 *                 or(
 *                     attributeInRangeNow("validity"),
 *                     attributeIs("validity", NULL)
 *                 )
 *             )
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent("code")
 *         )
 *     )
 * )
 * </pre>
 *
 * This query will select only children of the category "accessories" that have attribute `validity` range that includes
 * the current date or the attribute is not set at all.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/references#including-children">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "includingChildrenExcept",
	shortDescription = "The constraint automatically selects all children except those matching specified sub-constraints of the hierarchical entities matched by `facetHaving` container.",
	userDocsLink = "/documentation/query/filtering/references#including-children-except",
	supportedIn = ConstraintDomain.FACET
)
public class FacetExcludingChildren extends AbstractFilterConstraintContainer implements FacetSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3828147822588237136L;
	private static final String CONSTRAINT_NAME = "includingChildrenExcept";

	private FacetExcludingChildren(@Nonnull Serializable... arguments) {
		// because this query can be used only within some other facet query, it would be
		// unnecessary to duplicate the facet prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public FacetExcludingChildren() {
		super(CONSTRAINT_NAME);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetExcludingChildren(newArguments, getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"FacetIncludingChildrenExcept cannot have additional children."
		);
		return new FacetExcludingChildren(getArguments(), children);
	}

}
