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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The constraint `excludingRoot` is a constraint that can only be used within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
 * that are transitively or directly related to them and the parent node itself. When the excludingRoot is used as
 * a sub-constraint, this behavior changes and the parent node itself or the entities directly related to that parent
 * node are be excluded from the result.
 *
 * If the hierarchy constraint targets the hierarchy entity, the `excludingRoot` will omit the requested parent node
 * from the result. In the case of the {@link HierarchyWithinRoot} constraint, the parent is an invisible "virtual" top
 * root, and this constraint makes no sense.
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "accessories"),
 *             excludingRoot()
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
 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
 * is a product assigned to a category), the `excludingRoot` constraint can only be used in the {@link HierarchyWithin}
 * parent constraint.
 *
 * In the case of {@link HierarchyWithinRoot}, the `excludingRoot` constraint makes no sense because no entity can be
 * assigned to a "virtual" top parent root.
 *
 * Because we learned that Accessories category has no directly assigned products, the `excludingRoot` constraint
 * presence would not affect the query result. Therefore, we choose Keyboard category for our example. When we list all
 * products in Keyboard category using {@link HierarchyWithin} constraint, we obtain 20 items. When the `excludingRoot`
 * constraint is used:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "keyboards"),
 *             excludingRoot()
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
 * ... we get only 4 items, which means that 16 were assigned directly to Keyboards category and only 4 of them were
 * assigned to Exotic keyboards.
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#excluding-root">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "excludingRoot",
	shortDescription = "The constraint limits hierarchy within parent constraint to exclude the entities directly related to the searched root node.",
	userDocsLink = "/documentation/query/filtering/hierarchy#excluding-root",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyExcludingRoot extends AbstractFilterConstraintLeaf implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3965082821350063527L;
	private static final String CONSTRAINT_NAME = "excludingRoot";

	private HierarchyExcludingRoot(@Nonnull Serializable... arguments) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyExcludingRoot() {
		super(CONSTRAINT_NAME);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyExcludingRoot(newArguments);
	}
}
