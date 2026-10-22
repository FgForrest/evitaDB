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

import io.evitadb.api.query.FilterConstraint;

/**
 * Marks the child-category filters of a hierarchical {@link FacetHaving}: {@link FacetIncludingChildren} and
 * {@link FacetIncludingChildrenExcept}. Their children filter the referenced hierarchy nodes and form a separate
 * locale level, rather than filtering the owners of the references.
 *
 * An `entityLocaleEquals` inside a specification applies to the subcategories only. It neither sets nor conflicts
 * with the owner's locale and does not test the owner's localized data. Without its own opinion, this level inherits
 * the enclosing owner's resolved locale, including for localized subcategory attributes.
 *
 * The {@link DifferentEntityFilterContainer} boundary also isolates entity keys and scopes through its parent
 * {@link SeparateEntityScopeContainer}. Implementations are immutable and can be used only inside facet filters.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface HierarchyReferenceSpecificationFilterConstraint
	extends DifferentEntityFilterContainer, FilterConstraint {
}
