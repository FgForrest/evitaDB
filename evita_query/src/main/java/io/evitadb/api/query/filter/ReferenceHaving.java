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
import io.evitadb.api.query.ReferenceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Classifier;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `referenceHaving` constraint filters entities based on properties of their references to other entities. It eliminates entities that have
 * no reference of the specified name satisfying the given filtering constraints. The constraint behaves similarly to SQL's `EXISTS` operator,
 * allowing you to express complex join-like conditions without the performance penalties of traditional relational database joins.
 *
 * This constraint is a {@link ReferenceConstraint} and {@link SeparateEntityScopeContainer}, meaning it defines a new filtering context for
 * referenced entities. The filtering scope switches from the queried entity to the referenced entity type when constraints are nested within
 * `referenceHaving`.
 *
 * ## Reference Attribute Filtering
 *
 * You can filter on attributes stored directly on the reference (relation attributes), which describe properties of the relationship itself
 * rather than the referenced entity:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     attributeEquals("category", "alternativeProduct")
 * )
 * ```
 *
 * This query matches entities that have a reference to `brand` where the reference's `category` attribute equals "alternativeProduct".
 *
 * ## Referenced Entity Filtering
 *
 * To filter on attributes of the referenced entity itself (not the relation), wrap the filtering constraint in {@link EntityHaving}:
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
 * This query matches entities that have a reference to a `brand` entity whose `code` attribute equals "apple".
 *
 * ## Reference Existence Check
 *
 * When used without filtering constraints, `referenceHaving` simply checks for the existence of a reference:
 *
 * ```
 * referenceHaving("brand")
 * ```
 *
 * This query matches entities that have at least one reference named `brand`, regardless of its properties.
 *
 * ## Primary Key Filtering
 *
 * You can filter references by the primary key of the referenced entity:
 *
 * ```
 * referenceHaving(
 *     "brand",
 *     entityPrimaryKeyInSet(1, 5, 7)
 * )
 * ```
 *
 * This query matches entities that have a reference to `brand` entities with primary keys 1, 5, or 7.
 *
 * ## Relationship to Other Constraints
 *
 * - {@link FacetHaving}: Similar to `referenceHaving` but used for faceted navigation with statistical impact calculations when placed
 *   inside {@link UserFilter}. Outside `userFilter`, `facetHaving` behaves identically to `referenceHaving`.
 * - {@link EntityHaving}: Used within `referenceHaving` to filter on properties of the referenced entity rather than the reference itself.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#reference-having)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "having",
	shortDescription = "The container allowing to filter entities by having references to entities managed by evitaDB that " +
		"match the inner filter constraint. This container resembles the SQL inner join clauses.",
	userDocsLink = "/documentation/query/filtering/references#reference-having",
	supportedIn = ConstraintDomain.ENTITY
)
public class ReferenceHaving extends AbstractFilterConstraintContainer
	implements ReferenceConstraint<FilterConstraint>, SeparateEntityScopeContainer {
	@Serial private static final long serialVersionUID = -2727265686254207631L;

	private ReferenceHaving(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(arguments, children);
	}

	public ReferenceHaving(@Nonnull @Classifier String referenceName) {
		super(new Serializable[]{referenceName});
	}

	@Creator
	public ReferenceHaving(@Nonnull @Classifier String referenceName,
	                       @Nonnull FilterConstraint... filter) {
		super(new Serializable[]{referenceName}, filter);
	}

	/**
	 * Returns reference name of the relation that should be used for filtering according to child constraints.
	 */
	@Nonnull
	public String getReferenceName() {
		return (String) getArguments()[0];
	}

	@Override
	public boolean isNecessary() {
		return getArguments().length == 1;
	}

	@Override
	public boolean isApplicable() {
		return getArguments().length == 1;
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
			"ReferenceHaving doesn't accept additional children!"
		);
		return children.length == 0
			? new ReferenceHaving(getReferenceName())
			: new ReferenceHaving(getReferenceName(), children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new ReferenceHaving(newArguments, getChildren());
	}
}
