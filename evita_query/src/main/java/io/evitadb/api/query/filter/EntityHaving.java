/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.EntityConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `entityHaving` constraint filters based on attributes and other filterable properties of referenced entities. It can only be used as a
 * child constraint within {@link ReferenceHaving} or {@link FacetHaving}, which establish the reference context. This constraint shifts the
 * filtering scope from the reference (relation) to the referenced entity itself, allowing you to examine properties of the target entity
 * rather than properties of the relationship.
 *
 * This constraint requires the reference schema to have the `REFERENCED_ENTITY` component enabled in its indexed
 * components configuration and a group type defined.
 *
 * This constraint is an {@link EntityConstraint} and {@link SeparateEntityScopeContainer}, declaring that it defines a filtering context for
 * a different entity type than the query's primary collection. It is supported in the {@code REFERENCE}, {@code INLINE_REFERENCE},
 * {@code FACET}, and {@code SEGMENT} constraint domains.
 *
 * ## Basic Usage
 *
 * Filter entities that reference a `brand` entity with a specific `code` attribute:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     entityHaving(
 *         attributeEquals("code", "apple")
 *     )
 * )
 * ```
 *
 * This query matches entities (e.g., products) that reference a `brand` entity whose `code` attribute equals "apple".
 *
 * ## Full Range of Filtering Operators
 *
 * The `entityHaving` constraint accepts any filtering constraint that is valid in the entity domain. You can use attribute comparisons,
 * range queries, locale filtering, price constraints, and even nested hierarchical or reference constraints:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     entityHaving(
 *         and(
 *             attributeEquals("verified", true),
 *             attributeGreaterThan("foundedYear", 1990)
 *         )
 *     )
 * )
 * ```
 *
 * ## Contrast with Reference Attributes
 *
 * Without `entityHaving`, filtering constraints within `referenceHaving` apply to **reference attributes** (attributes stored on the
 * relationship itself). With `entityHaving`, constraints apply to **entity attributes** (attributes of the referenced entity):
 *
 * - **Reference attribute**: `referenceHaving("brand", attributeEquals("category", "primary"))` — filters on relation attribute `category`.
 * - **Entity attribute**: `referenceHaving("brand", entityHaving(attributeEquals("name", "Apple")))` — filters on brand entity's `name`.
 *
 * ## Single Child Constraint
 *
 * The `entityHaving` container accepts exactly one child constraint. If you need multiple filtering conditions, use a logical container like
 * {@link And} or {@link Or}:
 *
 * ```
 * referenceHaving(
 *     "supplier",
 *     entityHaving(
 *         or(
 *             attributeEquals("country", "USA"),
 *             attributeEquals("country", "Canada")
 *         )
 *     )
 * )
 * ```
 *
 * ## Relationship to Other Constraints
 *
 * - {@link ReferenceHaving}: The parent container that defines the reference name and provides the filtering context for `entityHaving`.
 * - {@link FacetHaving}: Similar to `referenceHaving` but used for faceted filtering; `entityHaving` works identically within both.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#entity-having)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The container that shifts the filtering scope to the referenced entity, allowing to filter by its attributes" +
		" and other properties rather than by the reference relation attributes.",
	userDocsLink = "/documentation/query/filtering/references#entity-having",
	supportedIn = {
		ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE,
		ConstraintDomain.FACET, ConstraintDomain.SEGMENT
	}
)
public class EntityHaving extends AbstractFilterConstraintContainer
	implements EntityConstraint<FilterConstraint>, SeparateEntityScopeContainer {
	@Serial private static final long serialVersionUID = 7151549459608672988L;

	private EntityHaving() {}

	@Creator
	public EntityHaving(@Nonnull FilterConstraint child) {
		super(child);
	}

	/**
	 * Returns the single child filter constraint, or `null` if there are no children.
	 */
	@Nullable
	public FilterConstraint getChild() {
		final FilterConstraint[] children = getChildren();
		return children.length > 0 ? children[0] : null;
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"EntityHaving cannot have additional children."
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"EntityHaving can have only one child."
		);
		return children.length == 0 ? new EntityHaving() : new EntityHaving(children[0]);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("EntityHaving filtering constraint has no arguments!");
	}
}
