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
 * The `groupHaving` constraint filters based on attributes and other filterable properties of the group entity
 * associated with a reference. It can only be used as a child constraint within {@link ReferenceHaving} or
 * {@link FacetHaving}, which establish the reference context. This constraint shifts the filtering scope from the
 * reference (relation) to the group entity, allowing you to examine properties of the group entity rather than
 * properties of the relationship or the referenced entity itself.
 *
 * This constraint requires the reference schema to have the `REFERENCED_GROUP_ENTITY` component enabled in its indexed
 * components configuration and a group type defined.
 *
 * This constraint is an {@link EntityConstraint} and {@link SeparateEntityScopeContainer}, declaring that it defines
 * a filtering context for a different entity type than the query's primary collection. It is supported in the
 * {@code REFERENCE}, {@code INLINE_REFERENCE}, {@code FACET}, and {@code SEGMENT} constraint domains.
 *
 * ## Basic Usage
 *
 * Filter entities that reference a `brand` whose group entity (a `Store`) has a specific `code` attribute:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     groupHaving(
 *         attributeEquals("code", "store-london")
 *     )
 * )
 * ```
 *
 * This query matches entities (e.g., products) that have a `brand` reference whose group (a Store entity) has
 * its `code` attribute equal to "store-london".
 *
 * ## Full Range of Filtering Operators
 *
 * The `groupHaving` constraint accepts any filtering constraint that is valid in the entity domain. You can use
 * attribute comparisons, range queries, primary key filters, and even nested constraints:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     groupHaving(
 *         entityPrimaryKeyInSet(1, 2, 3)
 *     )
 * )
 * ```
 *
 * ## Contrast with Reference and Entity Attributes
 *
 * Without `entityHaving` or `groupHaving`, filtering constraints within `referenceHaving` apply to **reference
 * attributes** (attributes stored on the relationship itself). With `entityHaving`, constraints apply to **referenced
 * entity attributes**. With `groupHaving`, constraints apply to **group entity attributes**:
 *
 * - **Reference attribute**: `referenceHaving("brand", attributeEquals("priority", 1))` -- filters on relation attribute.
 * - **Entity attribute**: `referenceHaving("brand", entityHaving(attributeEquals("name", "Apple")))` -- filters on brand entity's attribute.
 * - **Group attribute**: `referenceHaving("brand", groupHaving(attributeEquals("code", "london")))` -- filters on group (Store) entity's attribute.
 *
 * ## Single Child Constraint
 *
 * The `groupHaving` container accepts exactly one child constraint. If you need multiple filtering conditions, use
 * a logical container like {@link And} or {@link Or}:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     groupHaving(
 *         or(
 *             attributeEquals("code", "store-london"),
 *             attributeEquals("code", "store-paris")
 *         )
 *     )
 * )
 * ```
 *
 * ## Relationship to Other Constraints
 *
 * - {@link ReferenceHaving}: The parent container that defines the reference name and provides the filtering context.
 * - {@link FacetHaving}: Similar to `referenceHaving` but used for faceted filtering; `groupHaving` works identically within both.
 * - {@link EntityHaving}: A sibling constraint that shifts scope to the referenced entity; `groupHaving` shifts scope to the group entity instead.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#group-having)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "groupHaving",
	shortDescription = "The container that shifts the filtering scope to the group entity of the reference, " +
		"allowing to filter by its attributes and other properties rather than by the reference relation " +
		"or referenced entity attributes.",
	userDocsLink = "/documentation/query/filtering/references#group-having",
	supportedIn = { ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE, ConstraintDomain.FACET, ConstraintDomain.SEGMENT }
)
public class GroupHaving extends AbstractFilterConstraintContainer implements EntityConstraint<FilterConstraint>, SeparateEntityScopeContainer {
	@Serial private static final long serialVersionUID = 5484727724544395858L;

	private GroupHaving() {}

	@Creator
	public GroupHaving(@Nonnull FilterConstraint child) {
		super(child);
	}

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
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(
			ArrayUtils.isEmpty(additionalChildren),
			"GroupHaving cannot have additional children."
		);
		Assert.isPremiseValid(
			children.length <= 1,
			"GroupHaving can have only one child."
		);
		return children.length == 0 ? new GroupHaving() : new GroupHaving(children[0]);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("GroupHaving filtering constraint has no arguments!");
	}

}
