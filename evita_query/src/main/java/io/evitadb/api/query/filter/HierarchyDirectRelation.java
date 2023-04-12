/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.filter;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This query can be used only as sub query of `withinHierarchy` or `withinRootHierarchy`.
 * If you use `directRelation` sub-query fetching products related to category - only products that are directly
 * related to that category will be returned in the response.
 *
 * Let's have the following category tree:
 *
 * - TV (1)
 *     - Crt (2)
 *     - LCD (3)
 *        - AMOLED (4)
 *
 * These categories are related by following products:
 *
 * - TV (1):
 *     - Product Philips 32"
 *     - Product Samsung 24"
 *     - Crt (2):
 *         - Product Ilyiama 15"
 *         - Product Panasonic 17"
 *     - LCD (3):
 *         - Product BenQ 32"
 *         - Product LG 28"
 *         - AMOLED (4):
 *             - Product Samsung 32"
 *
 * When using this query:
 *
 * ```
 * query(
 *    entities('PRODUCT'),
 *    filterBy(
 *       withinHierarchy('CATEGORY', 1)
 *    )
 * )
 * ```
 *
 * All products will be returned.
 *
 * When this query is used:
 *
 * ```
 * query(
 *    entities('PRODUCT'),
 *    filterBy(
 *       withinHierarchy('CATEGORY', 1, directRelation())
 *    )
 * )
 * ```
 *
 * Only products directly related to TV category will be returned - i.e.: Philips 32" and Samsung 24". Products related
 * to sub-categories of TV category will be omitted.
 *
 * You can also use this hint to browse the hierarchy of the entity itself - to fetch subcategories of category.
 * If you use this query:
 *
 * ```
 * query(
 *    entities('CATEGORY'),
 *    filterBy(
 *       withinHierarchy(1)
 *    )
 * )
 * ```
 *
 * All categories under the category subtree of `TV (1)` will be listed (this means categories `TV`, `Crt`, `LCD`, `AMOLED`).
 * If you use this query:
 *
 * ```
 * query(
 *    entities('CATEGORY'),
 *    filterBy(
 *       withinHierarchy(1, directRelation())
 *    )
 * )
 * ```
 *
 * Only direct sub-categories of category `TV (1)` will be listed (this means categories `Crt` and `LCD`).
 * You can also use this hint with query `withinRootHierarchy`:
 *
 * ```
 * query(
 *    entities('CATEGORY'),
 *    filterBy(
 *       withinRootHierarchy()
 *    )
 * )
 * ```
 *
 * All categories in entire tree will be listed.
 *
 * When using this query:
 *
 * ```
 * query(
 *    entities('CATEGORY'),
 *    filterBy(
 *       withinHierarchy(directRelation())
 *    )
 * )
 * ```
 *
 * Which would return only category `TV (1)`.
 *
 * As you can see {@link HierarchyExcludingRoot} and {@link HierarchyDirectRelation} are mutually exclusive.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "directRelation",
	shortDescription = "The constraint limits hierarchy within parent constraint to take only directly related entities into an account.",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyDirectRelation extends AbstractFilterConstraintLeaf implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3959881131308135131L;

	private HierarchyDirectRelation(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public HierarchyDirectRelation() {
		super();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public String getName() {
		// missing "hierarchy" prefix because this query can be used only within some other hierarchy query,
		// it would be unnecessary to duplicate the hierarchy prefix
		return "directRelation";
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyDirectRelation(newArguments);
	}

}
