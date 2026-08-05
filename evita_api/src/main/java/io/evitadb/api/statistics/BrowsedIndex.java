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

package io.evitadb.api.statistics;

import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * One entity index, as described by an index browse.
 *
 * {@link CollectionIndexSummary} answers *how many* indexes of each kind a collection holds; this answers *which ones*,
 * one page at a time. It exists because a count of forty thousand `REFERENCED_ENTITY` indexes tells an operator that
 * something is wrong but not which reference caused it.
 *
 * **Identity.** An index is identified by the triplet (kind, scope, discriminator), and the discriminator is rendered
 * here as its two readable parts rather than as an opaque object:
 *
 * - `GLOBAL` indexes carry neither - both {@link #referenceName()} and {@link #discriminatorPrimaryKey()} are null.
 * - The per-reference-*type* kinds carry a reference name and no primary key: one index covers the whole reference.
 * - The per-referenced-*entity* kinds carry both - the index covers exactly one target entity of that reference.
 *
 * @param indexKind               kind of the index
 * @param scope                   scope the index belongs to
 * @param referenceName           name of the reference this index is bound to, or null for a `GLOBAL` index that is
 *                                bound to no reference
 * @param discriminatorPrimaryKey primary key of the referenced entity this index is bound to, or null when the index
 *                                covers a whole reference type rather than one target entity
 * @param entityCount             how many entities the index covers - a cardinality reading of the index's primary-key
 *                                bitmap, never a walk of its contents
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see IndexBrowseCriteria
 * @see CollectionIndexSummary
 */
public record BrowsedIndex(
	@Nonnull EntityIndexKind indexKind,
	@Nonnull Scope scope,
	@Nullable String referenceName,
	@Nullable Integer discriminatorPrimaryKey,
	int entityCount
) {

	public BrowsedIndex {
		Objects.requireNonNull(indexKind, "Index kind must not be null!");
		Objects.requireNonNull(scope, "Scope must not be null!");
	}

}
