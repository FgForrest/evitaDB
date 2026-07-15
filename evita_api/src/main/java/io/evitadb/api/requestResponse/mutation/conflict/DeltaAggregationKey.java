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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Delta-agnostic accumulation identity shared by {@link AttributeDeltaConflictKey} and
 * {@link ReferenceAttributeDeltaConflictKey}. Two commutative delta keys that target the same accumulation
 * slot — the same entity, primary key, optional reference and attribute — but differ only in their delta
 * value or allowed range produce an equal instance of this record, so their deltas accumulate into a single
 * running total during commit-time range checking. This is deliberately coarser than the delta keys'
 * {@link Object#equals(Object)} (which stays delta-sensitive for per-transaction key sets and the conflict
 * ring buffer) yet finer than {@link ConflictKey#parentConflictKey()} (which drops the attribute locale).
 *
 * The `referenceKey` component is `null` for a plain attribute delta and carries the owning reference for a
 * reference-attribute delta, keeping the two families in disjoint accumulation slots.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
record DeltaAggregationKey(
	@Nonnull String entityType,
	@Nullable Integer entityPrimaryKey,
	@Nullable ReferenceKey referenceKey,
	@Nonnull AttributeKey attributeKey
) {
}
