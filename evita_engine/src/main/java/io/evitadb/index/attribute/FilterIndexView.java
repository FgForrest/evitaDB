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

package io.evitadb.index.attribute;

import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Stateless VIEW variant of {@link FilterIndex}. It wraps an externally-owned shared {@link InvertedIndex} (and optional
 * {@link RangeIndex}) owned by {@link AttributeIndex} (its `sharedValueIndex` / `sharedRangeIndex` maps). The view owns
 * no transactional state of its own — it is a thin read/query façade whose `addRecord`/`removeRecord` mutate the shared
 * tree directly through that tree's own transactional dispatch.
 *
 * Because the view never participates in a commit, it must not maintain a private dirty flag (that would leak an
 * undischarged transactional layer). Instead every lifecycle decision is delegated to the wrapped shared tree:
 *
 * - {@link #getId()} returns the shared tree's id, so the view's query-planner formula id stays stable across commits
 *   that did not touch the tree (and the view object may itself be carried forward unchanged).
 * - {@link #isDirty()} / {@link #resetDirty()} delegate to the shared tree's own dirty flag.
 * - {@link #markDirty()} is a no-op.
 *
 * The `comparator` is derived from the (plain) `attributeType` exactly as the owning constructors do; the `normalizer`
 * is taken from the wrapped tree so the view presents the very same key bytes as the shared tree it reads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FilterIndexView extends FilterIndex {
	@Serial private static final long serialVersionUID = 5176389425077193213L;

	/**
	 * Wraps an externally-owned shared {@link InvertedIndex} (and optional shared {@link RangeIndex}) as a stateless
	 * filter view.
	 *
	 * @param attributeIndexKey   key identifying the attribute
	 * @param sharedInvertedIndex the externally-owned shared value→ValueToRecord tree to wrap
	 * @param sharedRangeIndex    the externally-owned shared range structure, or `null` for non-range attributes
	 * @param attributeType       the declared attribute type (array-aware; the plain type drives the comparator)
	 */
	public FilterIndexView(
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull InvertedIndex sharedInvertedIndex,
		@Nullable RangeIndex sharedRangeIndex,
		@Nonnull Class<?> attributeType
	) {
		super(
			attributeIndexKey,
			attributeType,
			sharedInvertedIndex,
			sharedRangeIndex,
			getComparator(attributeIndexKey, plainTypeOf(attributeType)),
			sharedInvertedIndex.getNormalizer()
		);
	}

	@Override
	public long getId() {
		// derive the transactional id from the wrapped shared tree so the formula cache stays warm across commits
		// that did not touch this key (the tree instance — and therefore its id — is carried forward by reference)
		return getInvertedIndex().getId();
	}

	@Override
	public boolean isDirty() {
		return getInvertedIndex().isDirty();
	}

	@Override
	protected void markDirty() {
		// no-op: the shared tree this view wraps tracks its own dirtiness; a view never commits on its own
	}

	@Override
	public void resetDirty() {
		// the shared tree is the persistence source of truth
		getInvertedIndex().resetDirty();
	}

}
