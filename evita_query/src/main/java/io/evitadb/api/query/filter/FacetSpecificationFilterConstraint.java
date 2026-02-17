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

import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;

/**
 * Marker interface for filtering constraints that can be used as child constraints within {@link FacetHaving} to further specify facet
 * filtering logic. Constraints implementing this interface provide additional filtering capabilities specific to faceted navigation scenarios.
 *
 * ## Purpose in the Constraint System
 *
 * This interface serves as a **type marker** in evitaDB's constraint classification system. It extends both {@link FacetConstraint} and
 * {@link FilterConstraint}, indicating that implementations are filtering constraints that operate specifically within the facet property
 * domain. The interface has no methods beyond those inherited from its parent interfaces.
 *
 * ## Role in Facet Filtering
 *
 * Constraints implementing `FacetSpecificationFilterConstraint` are designed to work within the {@link FacetHaving} container and provide
 * specialized filtering logic for faceted references. These constraints typically modify how facet matches are determined (e.g., including
 * hierarchical children, excluding specific entities) rather than directly filtering entity attributes.
 *
 * ## Known Implementations
 *
 * Current implementations of this interface include:
 *
 * - {@link FacetIncludingChildren}: Includes hierarchical descendants in facet matching.
 * - {@link FacetIncludingChildrenExcept}: Includes hierarchical descendants except those matching exclusion criteria.
 *
 * These constraints are not general-purpose filters — they only make sense within {@link FacetHaving} because they modify the facet matching
 * behavior for hierarchical references.
 *
 * ## Constraint Validation
 *
 * The evitaDB query constraint processor uses this marker interface during query validation to ensure that `FacetSpecificationFilterConstraint`
 * implementations are only used in valid contexts (as children of {@link FacetHaving}). Using these constraints outside {@link FacetHaving}
 * may result in a query validation error.
 *
 * ## Relationship to Other Interfaces
 *
 * - {@link FacetConstraint}: Parent marker interface indicating this constraint operates in the facet property domain.
 * - {@link FilterConstraint}: Parent marker interface indicating this is a filtering constraint.
 * - {@link FacetHaving}: The container that accepts `FacetSpecificationFilterConstraint` implementations as children.
 * - {@link HierarchyReferenceSpecificationFilterConstraint}: Similar marker interface for hierarchy specification constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface FacetSpecificationFilterConstraint extends FacetConstraint<FilterConstraint>, FilterConstraint {
}
