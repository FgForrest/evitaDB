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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * A specialized filter container used exclusively within {@link FacetSummary} requirements to define which facet
 * **groups** are included in the facet summary response. This constraint is analogous to {@link FilterBy} but operates
 * on facet groups rather than entities.
 *
 * **Purpose**
 *
 * `FilterGroupBy` allows you to selectively include or exclude facet groups (the grouping entities in faceted
 * navigation, such as "Brand", "Color", "Size") in the facet summary based on their properties. This is useful when
 * you want to show only certain facet groups based on attributes like visibility flags, priority, or other metadata.
 *
 * **Context: Facet Summary**
 *
 * In evitaDB's faceting model:
 * - **Facets** are references to entities (e.g., specific brands like "Nike", "Adidas")
 * - **Facet Groups** are the grouping entities (e.g., the "Brand" category entity that groups all brand facets)
 * - **Facet Summary** is a statistical aggregation showing available facets and their impact on filtering
 *
 * When you request a {@link FacetSummary}, evitaDB computes statistics for all facet groups and their facets. The
 * `filterGroupBy` constraint allows you to limit which facet groups appear in the summary by filtering the group
 * entities themselves based on their attributes.
 *
 * **Default Conjunction Semantics**
 *
 * Like {@link FilterBy}, when multiple child constraints are specified directly within `filterGroupBy`, they are
 * implicitly combined using **logical conjunction (AND)**. All child constraints must be satisfied for a facet group
 * to be included in the summary:
 * - `filterGroupBy(a, b, c)` is equivalent to `filterGroupBy(and(a, b, c))`
 * - All conditions a, b, and c must evaluate to true for the facet group to appear
 *
 * To use different logical combinations (OR, NOT), you must explicitly nest the appropriate logical operators
 * ({@link And}, {@link Or}, {@link Not}) within `filterGroupBy`.
 *
 * **Usage Context**
 *
 * This constraint can only be used in specific domains:
 * - `REFERENCE`: within {@link FacetSummary} constraints for regular references
 * - `INLINE_REFERENCE`: within {@link FacetSummary} constraints for inline references
 *
 * Unlike {@link FilterBy}, which is a top-level query section, `filterGroupBy` is always nested within a
 * `facetSummary` requirement and cannot appear at the query root.
 *
 * **EvitaQL Syntax**
 *
 * ```evitaql
 * filterGroupBy(filterConstraint:any*)
 * ```
 *
 * **Example Usage**
 *
 * ```java
 * // Show only visible facet groups in facet summary
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("category", "Electronics")
 *     ),
 *     require(
 *         facetSummary(
 *             COUNTS,
 *             filterGroupBy(
 *                 attributeEquals("visible", true)
 *             )
 *         )
 *     )
 * )
 *
 * // Show facet groups with priority >= 5 or explicitly featured
 * query(
 *     collection("Product"),
 *     filterBy(
 *         attributeEquals("available", true)
 *     ),
 *     require(
 *         facetSummary(
 *             COUNTS,
 *             filterGroupBy(
 *                 or(
 *                     attributeGreaterThanEquals("priority", 5),
 *                     attributeEquals("featured", true)
 *                 )
 *             )
 *         )
 *     )
 * )
 *
 * // Exclude deprecated facet groups from facet summary
 * query(
 *     collection("Product"),
 *     require(
 *         facetSummary(
 *             COUNTS,
 *             filterGroupBy(
 *                 not(
 *                     attributeEquals("deprecated", true)
 *                 )
 *             )
 *         )
 *     )
 * )
 *
 * // Complex group filtering - show groups that have a code and are either active or promoted
 * query(
 *     collection("Product"),
 *     require(
 *         facetSummaryOfReference(
 *             "parameterValues",
 *             COUNTS,
 *             filterGroupBy(
 *                 attributeIsNotNull("code"),
 *                 or(
 *                     attributeEquals("active", true),
 *                     attributeEquals("promoted", true)
 *                 )
 *             )
 *         )
 *     )
 * )
 * ```
 *
 * **Relationship to FilterBy**
 *
 * The semantic difference between `filterBy` and `filterGroupBy` is the target of filtering:
 * - **FilterBy**: filters the **entities** in the main query (e.g., products)
 * - **FilterGroupBy**: filters the **facet groups** in the facet summary (e.g., parameter types, brand categories)
 *
 * Both use the same filtering constraint syntax and logical operators, but they apply to different entity types in
 * different parts of the query evaluation.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#filter-by)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "filterGroupBy",
	shortDescription = "The container encapsulating filter constraint limiting the facet groups returned in facet summary.",
	userDocsLink = "/documentation/query/basics#filter-by",
	supportedIn = { ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class FilterGroupBy extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -209752332976848423L;

	FilterGroupBy() {
		super();
	}

	@Creator
	public FilterGroupBy(@Nonnull @Child(uniqueChildren = true) FilterConstraint... children) {
		super(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("FilterGroupBy filtering query has no arguments!");
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "FilterGroupBy doesn't accept other than filtering constraints!");
		return children.length > 0 ? new FilterGroupBy(children) : new FilterGroupBy();
	}

}
