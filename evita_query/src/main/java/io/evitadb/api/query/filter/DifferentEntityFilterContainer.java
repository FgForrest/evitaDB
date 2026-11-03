/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

/**
 * Marks a container whose children filter a different entity than the container's enclosing entity level.
 * Locale lookup stops at this boundary. The children resolve their own locale and, when they have no opinion,
 * inherit the enclosing level's resolved locale.
 *
 * The marker narrows {@link SeparateEntityScopeContainer}: every implementor also isolates the entity keys and the
 * `entityScope` of its children from the enclosing level. The child-category filters
 * {@link FacetIncludingChildren} and {@link FacetIncludingChildrenExcept} also form such a level: their children
 * filter subcategories and inherit the owner's locale only when they name none of their own.
 *
 * `referenceHaving` and `facetHaving` do not start a new locale level: their direct children are evaluated in
 * the owner's locale, including localized reference attributes. A direct `entityLocaleEquals` in either container
 * requires the owner to hold that locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface DifferentEntityFilterContainer extends SeparateEntityScopeContainer {
}
