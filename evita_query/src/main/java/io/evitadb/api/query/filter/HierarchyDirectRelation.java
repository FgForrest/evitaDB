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
 * The constraint `directRelation` is a constraint that can only be used within {@link HierarchyWithin} or
 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
 * that are transitively or directly related to them and the parent node itself. If the directRelation is used as
 * a sub-constraint, this behavior changes and only direct descendants or directly referencing entities are matched.
 *
 * If the hierarchy constraint targets the hierarchy entity, the `directRelation` will cause only the children of
 * a direct parent node to be returned. In the case of the hierarchyWithinRoot constraint, the parent is an invisible
 * "virtual" top root - so only the top-level categories are returned.
 *
 * <pre>
 * query(
 *     collection('Category'),
 *     filterBy(
 *         hierarchyWithinRootSelf(
 *             directRelation()
 *         )
 *     ),
 *     require(
 *         entityFetch(
 *             attributeContent('code')
 *         )
 *     )
 * )
 * </pre>
 *
 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
 * is a product assigned to a category), it can only be used in the hierarchyWithin parent constraint.
 *
 * In the case of {@link HierarchyWithinRoot}, the `directRelation` constraint makes no sense because no entity can be
 * assigned to a "virtual" top parent root.
 *
 * So we can only list products that are directly related to a certain category. We can list products that have
 * Smartwatches category assigned:
 *
 * <pre>
 * query(
 *     collection("Product"),
 *     filterBy(
 *         hierarchyWithin(
 *             "categories",
 *             attributeEquals("code", "smartwatches"),
 *             directRelation()
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
 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#direct-relation">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "directRelation",
	shortDescription = "The constraint limits hierarchy within parent constraint to take only directly related entities into an account.",
	userDocsLink = "/documentation/query/filtering/hierarchy#direct-relation",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyDirectRelation extends AbstractFilterConstraintLeaf implements HierarchySpecificationFilterConstraint {
	@Serial private static final long serialVersionUID = 3959881131308135131L;
	private static final String CONSTRAINT_NAME = "directRelation";

	private HierarchyDirectRelation(@Nonnull Serializable... arguments) {
		// missing "hierarchy" prefix because this query can be used only within some other hierarchy query,
		// it would be unnecessary to duplicate the hierarchy prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public HierarchyDirectRelation() {
		super(CONSTRAINT_NAME);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new HierarchyDirectRelation(newArguments);
	}

}
