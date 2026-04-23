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

package io.evitadb.api.requestResponse.mutation;

/**
 * Root marker interface for all mutation types in evitaDB — both external (API-facing, WAL-serialized)
 * and internal (engine-only, derived from triggers).
 *
 * This interface carries no methods. Its sole purpose is to establish a type hierarchy that allows IDE
 * navigation across the complete mutation landscape via "Show Implementations" / type hierarchy views.
 *
 * Two branches exist under this root:
 *
 * - {@link Mutation} — external mutations: passed through the API, written to WAL, carry
 *   {@link Mutation#operation()} and conflict key semantics
 * - engine-internal mutations (e.g., `IndexMutation` in `evita_engine`) — engine-generated, never
 *   serialized, never written to WAL, regenerated deterministically on replay
 *
 * This interface is intentionally **not sealed** to allow cross-module extension by engine-internal
 * mutation types that reside in `evita_engine`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface MutationContract {
}
