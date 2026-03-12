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

package io.evitadb.index.mutation;

import javax.annotation.Nonnull;

/**
 * Transport envelope carrying concrete {@link IndexMutation} instances targeting a specific
 * `EntityCollection`. Created by the source executor after detecting relevant changes. The target
 * collection dispatches each nested mutation to the appropriate executor.
 *
 * This is a standalone record — it does **not** implement {@link IndexMutation}. It serves purely
 * as a routing envelope: the {@code entityType} identifies the target collection, and the nested
 * mutations are the actual work items.
 *
 * **Note on equality:** Java records use {@code Object.equals()} for array fields, so two envelopes
 * with identical contents but different array references will NOT be {@code equals()}. This is
 * intentional — envelope equality is not needed for correctness.
 *
 * @param entityType target collection entity type
 * @param mutations  concrete index mutations to dispatch to the target collection
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record EntityIndexMutation(
	@Nonnull String entityType,
	@Nonnull IndexMutation[] mutations
) {
}
