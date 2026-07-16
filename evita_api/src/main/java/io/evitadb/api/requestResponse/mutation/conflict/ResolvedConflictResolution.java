/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation.conflict;

import javax.annotation.Nonnull;

/**
 * Outcome of a diagnostic conflict-resolution walk: the effective entity-level {@link ConflictResolution}
 * together with the {@link ConflictResolutionLayer schema layer} it was resolved from.
 *
 * Produced by {@link EffectiveConflictResolutionResolver#resolveWithSource} on the diagnostic (cold) path
 * only — the hot key-generation path uses {@link EffectiveConflictResolutionResolver#resolve} and does not
 * track the source layer. Carried into the conflict exception so a rolled-back transaction can report both
 * the policy that was in force and where it came from.
 *
 * @param resolution the effective entity-level resolution that was in force, never null
 * @param layer      the schema layer that supplied {@code resolution}, never null
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ResolvedConflictResolution(
	@Nonnull ConflictResolution resolution,
	@Nonnull ConflictResolutionLayer layer
) {
}
