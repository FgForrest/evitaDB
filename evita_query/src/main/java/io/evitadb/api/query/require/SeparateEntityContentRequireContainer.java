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

package io.evitadb.api.query.require;

import io.evitadb.api.query.RequireConstraint;

/**
 * Marker interface that must be implemented by any {@link RequireConstraint} whose body may contain
 * {@link EntityContentRequire} children that form an *isolated fetch scope*.
 *
 * During query planning the engine collects `EntityContentRequire` constraints from the global require clause for
 * the primary entity type being queried. Without this scoping mechanism, nested fetch specifications would be
 * incorrectly merged with the global entity-fetch context.
 *
 * By implementing `SeparateEntityContentRequireContainer`, a constraint declares: "my {@link EntityContentRequire}
 * children describe a *different* entity (or role) and must not be merged with outer fetch requirements."
 *
 * Key implementations:
 * - {@link EntityFetch} — defines the body richness for the queried entities or referenced entities (depending
 *   on context); its children are scoped exclusively to the entity type it targets
 * - {@link EntityGroupFetch} — defines the body richness for reference group entities inside facet/reference
 *   contexts; isolated from the primary entity fetch scope
 *
 * The query planner (notably `QueryPurifierVisitor` and `DefaultPrefetchRequirementCollector`) uses this interface
 * as a boundary marker to stop propagating `EntityContentRequire` resolution across different entity contexts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface SeparateEntityContentRequireContainer extends RequireConstraint {
}
