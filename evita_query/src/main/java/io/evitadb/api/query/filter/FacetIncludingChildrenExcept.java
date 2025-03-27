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
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint `includingChildrenExcept` is a constraint that can only be used within {@link FacetHaving} parent constraint.
 * It simply makes no sense anywhere else because it changes the default behavior of this constraint. Facet having
 * filters entities that have a direct reference to matching faceted entity. When the `includingChildrenExcept` constraint
 * is used, the query will return all entities that have a direct reference to the matching entity or any of its children
 * in the hierarchy except the children that satisfy the internal constraints of the `includingChildrenExcept` constraint
 * container.
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
 *             includingChildrenExcept(
 *                 attributeEquals("visible", "INVISIBLE"),
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
 * This query will match all products that have reference to the category with code "accessories" or any of its children
 * except those with attribute `visible` set to "INVISIBLE". The {@link FacetSummary} will take references to any of
 * the category children into account when calculating the impact of category facet selection.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/references#including-children-except">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ConstraintDefinition(
	name = "includingChildrenExcept",
	shortDescription = "The constraint automatically selects all children except those matching specified sub-constraints of the hierarchical entities matched by `facetHaving` container.",
	userDocsLink = "/documentation/query/filtering/references#including-children-except",
	supportedIn = ConstraintDomain.FACET
)
public class FacetIncludingChildrenExcept extends AbstractFilterConstraintContainer implements FacetConstraint<FilterConstraint>, HierarchyReferenceSpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3828147822588237136L;
	private static final String CONSTRAINT_NAME = "includingChildrenExcept";

	public FacetIncludingChildrenExcept() {
		// because this query can be used only within some other facet query, it would be
		// unnecessary to duplicate the facet prefix
		super(CONSTRAINT_NAME);
	}

	@Creator
	public FacetIncludingChildrenExcept(@Nonnull @Child(domain = ConstraintDomain.ENTITY) FilterConstraint child) {
		super(CONSTRAINT_NAME, child);
	}

	@Nullable
	public FilterConstraint getChild() {
		final FilterConstraint[] children = getChildren();
		return children.length == 0 ? null : children[0];
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Override
	public boolean isNecessary() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("FacetIncludingChildrenExcept filtering constraint has no arguments!");
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"FacetIncludingChildrenExcept cannot have additional children."
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"FacetIncludingChildrenExcept can have only one child."
		);
		return children.length == 0 ? new FacetIncludingChildrenExcept() : new FacetIncludingChildrenExcept(children[0]);
	}

}