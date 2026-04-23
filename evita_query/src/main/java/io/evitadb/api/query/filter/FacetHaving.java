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
import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `facetHaving` constraint filters entities based on faceted references, enabling drill-down navigation with statistical impact
 * calculations. It works similarly to {@link ReferenceHaving}, but is specifically designed for faceted filtering scenarios and integrates
 * with the {@code facetSummary} requirement to compute facet statistics and selection impact predictions. When placed inside a
 * {@link UserFilter} container, `facetHaving` participates in facet statistics calculations; when used outside {@code userFilter}, it
 * behaves identically to {@code referenceHaving}.
 *
 * This constraint is a {@link FacetConstraint}, marking it as part of evitaDB's faceted filtering subsystem. Facets are a specialized type
 * of reference used for drill-down navigation in e-commerce applications (e.g., filtering products by brand, color, or category).
 *
 * ## Basic Usage
 *
 * Filter entities that have a faceted reference to a specific brand:
 *
 * ```
 * userFilter(
 *     facetHaving(
 *         "brand",
 *         entityHaving(
 *             attributeInSet("code", "amazon")
 *         )
 *     )
 * )
 * ```
 *
 * This query matches entities (e.g., products) that have a faceted reference to a `brand` entity with `code` "amazon". When combined with
 * `facetSummary` in the `require` section, the response will include statistics about other facet values (e.g., how many products remain if
 * you also select "Sony" as a brand).
 *
 * ## Integration with FacetSummary
 *
 * The primary difference between `facetHaving` and `referenceHaving` is how they interact with the `facetSummary` requirement. When
 * `facetHaving` is placed inside {@link UserFilter}, the facet summary calculation **excludes** the `facetHaving` constraints when computing
 * the impact of selecting or deselecting facet values. This allows the UI to show users:
 *
 * - How many results remain if they select an additional facet value.
 * - How many results would be removed if they deselect a currently selected facet value.
 *
 * Example with facet summary:
 *
 * ```
 * query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("available", true),
 *             userFilter(
 *                 facetHaving("brand", entityPrimaryKeyInSet(1, 5))
 *             )
 *         )
 *     ),
 *     require(
 *         facetSummary(IMPACT)
 *     )
 * )
 * ```
 *
 * The facet summary will compute statistics for all `brand` facets, showing how many products match each brand **excluding** the current
 * `facetHaving` filter but **including** the mandatory filter (`attributeEquals("available", true)`). This gives accurate impact predictions
 * for facet selection changes.
 *
 * ## Behavior Outside UserFilter
 *
 * When `facetHaving` is used outside {@link UserFilter}, it behaves identically to {@link ReferenceHaving}. It filters entities based on the
 * specified reference constraints, but does **not** participate in facet statistics calculations:
 *
 * ```
 * filterBy(
 *     facetHaving(
 *         "brand",
 *         entityPrimaryKeyInSet(1, 5)
 *     )
 * )
 * ```
 *
 * This is functionally equivalent to `referenceHaving("brand", entityPrimaryKeyInSet(1, 5))`.
 *
 * ## Faceted Reference Filtering
 *
 * Like {@link ReferenceHaving}, `facetHaving` can filter on reference attributes or referenced entity properties:
 *
 * - **Reference attribute filtering**: `facetHaving("color", attributeEquals("priority", 1))`
 * - **Entity attribute filtering**: `facetHaving("color", entityHaving(attributeEquals("name", "Red")))`
 *
 * ## Child Constraints
 *
 * The `facetHaving` constraint accepts zero or more child filtering constraints. Child constraints must be unique (no duplicate constraint
 * instances). You can use:
 *
 * - {@link EntityHaving} to filter on properties of the referenced entity.
 * - Attribute constraints to filter on reference attributes.
 * - {@code entityPrimaryKeyInSet} to filter by referenced entity primary keys.
 *
 * ## Design Rationale
 *
 * Faceted navigation is a core e-commerce pattern where users progressively narrow search results by selecting attribute values (facets).
 * evitaDB treats facets as a first-class concept with specialized indexes and statistics computation. The `facetHaving` constraint leverages
 * these specialized indexes to efficiently filter entities and compute facet impact predictions without expensive recalculations.
 *
 * By separating user-controlled filters ({@link UserFilter}) from mandatory filters, evitaDB can compute facet statistics in a way that
 * provides accurate "what-if" predictions: "If I select this facet, how many results will I see?" This separation is critical for building
 * responsive faceted navigation UIs.
 *
 * ## Relationship to Other Constraints
 *
 * - {@link ReferenceHaving}: General-purpose reference filtering; `facetHaving` extends its behavior with facet statistics support.
 * - {@link UserFilter}: Container that distinguishes user-controlled filters from mandatory filters; required for facet statistics.
 * - {@link EntityHaving}: Used within `facetHaving` to filter on properties of the referenced entity.
 * - {@code facetSummary}: Requirement that computes facet statistics; works in conjunction with `facetHaving`.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#facet-having)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The container allowing to filter entities by faceted references that match the inner filter constraint." +
		" When used inside `userFilter`, it integrates with the `facetSummary` requirement to compute facet statistics" +
		" and impact predictions.",
	userDocsLink = "/documentation/query/filtering/references#facet-having",
	supportedIn = ConstraintDomain.ENTITY
)
public class FacetHaving extends AbstractFilterConstraintContainer implements FacetConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -4135466525683422992L;

	private FacetHaving(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(arguments, children);
	}

	/**
	 * Private constructor that creates unnecessary / not applicable version of the query.
	 */
	private FacetHaving(@Nonnull @Classifier String referenceName) {
		super(referenceName);
	}

	@Creator
	public FacetHaving(@Nonnull @Classifier String referenceName,
	                   @Nonnull @Child(uniqueChildren = true) FilterConstraint... filter) {
		super(new Serializable[]{referenceName}, filter);
	}

	/**
	 * Returns reference name of the facet relation that should be used for filtering
	 * according to children constraints.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@Override
	public boolean isNecessary() {
		return getArguments().length == 1 && getChildren().length > 0;
	}

	@AliasForParameter("filter")
	@Nonnull
	@Override
	public FilterConstraint[] getChildren() {
		return super.getChildren();
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"FacetHaving doesn't accept additional children!"
		);
		return children.length == 0 ? new FacetHaving(getReferenceName()) : new FacetHaving(getReferenceName(), children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new FacetHaving(newArguments, getChildren());
	}
}