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
 * **Identity is (kind, scope, discriminator) - and only that triplet.** {@link #referenceName()} and
 * {@link #discriminatorPrimaryKey()} are readable *projections* of the discriminator, convenient for display and for
 * grouping, but they do not identify an index between them. Two distinct indexes can agree on both: a reference whose
 * targets are told apart by representative attribute values has one index per distinct value set, all sharing one
 * reference name and one target primary key. Deduplicating or keying on the pair would silently collapse them.
 *
 * How the parts are populated:
 *
 * - `GLOBAL` indexes carry no discriminator at all - all three fields are null.
 * - The per-reference-*type* kinds carry a reference name and no primary key: one index covers the whole reference.
 * - The per-referenced-*entity* kinds carry both, plus whatever else distinguishes the target, which is why
 *   {@link #discriminator()} rather than the pair is the thing to compare.
 *
 * @param indexKind               kind of the index
 * @param scope                   scope the index belongs to
 * @param discriminator           stable rendering of everything that distinguishes this index from its siblings of the
 *                                same kind and scope, or null for a `GLOBAL` index that has no siblings to be told
 *                                apart from; compare this - never the two projections below - to decide whether two
 *                                rows describe one index
 * @param referenceName           name of the reference this index is bound to, or null for a `GLOBAL` index that is
 *                                bound to no reference; not unique on its own
 * @param discriminatorPrimaryKey primary key of the referenced entity this index is bound to, or null when the index
 *                                covers a whole reference type rather than one target entity; not unique on its own
 * @param entityCount             how many entities the index covers - a cardinality reading of the index's primary-key
 *                                bitmap, never a walk of its contents
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see IndexBrowseCriteria
 * @see CollectionIndexSummary
 */
public record BrowsedIndex(
	@Nonnull EntityIndexKind indexKind,
	@Nonnull Scope scope,
	@Nullable String discriminator,
	@Nullable String referenceName,
	@Nullable Integer discriminatorPrimaryKey,
	int entityCount
) {

	public BrowsedIndex {
		Objects.requireNonNull(indexKind, "Index kind must not be null!");
		Objects.requireNonNull(scope, "Scope must not be null!");
	}

}
