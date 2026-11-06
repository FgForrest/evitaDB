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

import io.evitadb.api.query.FilterConstraint;

/**
 * Marker interface for filter constraints that refine a {@link TextMatches} fulltext search. These constraints are
 * valid only as direct children of `textMatches` and carry no meaning on their own.
 *
 * **Implementations**
 *
 * - {@link InField}: restricts the search to a single searchable field
 *
 * **Why a marker and not a concrete child type**
 *
 * The `textMatches` creator is typed by this interface rather than by `InField`, so that further refinements of the
 * search (such as an adaptive relaxation of the strict match) arrive as new
 * implementations of this interface instead of as a change of the `textMatches` signature.
 *
 * **Containment**
 *
 * - **Valid**: `textMatches("black leather jacket", inField("name"), inField("description"))`
 * - **Invalid**: `and(inField("name"), attributeEquals("code", "A"))` — the Java API and the EvitaQL grammar accept
 *   such a query, but its execution is rejected
 *
 * Unlike {@link HierarchySpecificationFilterConstraint}, this interface does not extend a property-type marker: every
 * implementation declares exactly one property type of its own.
 *
 * @see TextMatches
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface TextMatchesSpecificationFilterConstraint extends FilterConstraint {
}
