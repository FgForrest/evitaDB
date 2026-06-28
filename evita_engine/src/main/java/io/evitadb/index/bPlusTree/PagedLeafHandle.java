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

package io.evitadb.index.bPlusTree;

/**
 * The value-agnostic page-bookkeeping contract every paging variant of the B+ tree family exposes per leaf: the leaf's
 * logical persistence page sequence (carried across commits by the leaf node), the transaction-aware dirty signal, and
 * the stamp that assigns a freshly allocated page to a not-yet-paged (split-born or fresh) leaf. It carries no value
 * type, so the granular write path — {@link io.evitadb.index.page.PageStreamRegistry#collectChangedPages} — reconciles
 * page sequences once against this contract regardless of the concrete tree (bucket, long-keyed, element-keyed), while
 * each tree's richer {@code LeafPageHandle} adds the value-access methods the per-leaf payload is materialized from.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface PagedLeafHandle {

	/**
	 * The page sequence a leaf with no page yet reports from {@link #getPageSequence()}. The granular write path detects
	 * such a leaf and allocates it a real page. Identical to every paging tree's own {@code UNASSIGNED_PAGE_SEQUENCE}.
	 */
	int UNASSIGNED_PAGE_SEQUENCE = -1;

	/**
	 * Returns the leaf's current page sequence, or {@link #UNASSIGNED_PAGE_SEQUENCE} when the leaf has no page yet.
	 *
	 * @return the page sequence or {@link #UNASSIGNED_PAGE_SEQUENCE}
	 */
	int getPageSequence();

	/**
	 * Returns whether this leaf has been mutated since the last flush — the deterministic change-detection signal.
	 * The flag is read transaction-aware (the in-flight transaction's layer when one exists), so a leaf changed in
	 * the committing transaction reads dirty at flush even though its committed instance is replaced only later, at
	 * the commit-merge. Unlike a content hash it can never miss a real change: every mutation sets it.
	 *
	 * @return true when the leaf must be (re)written
	 */
	boolean isDirty();

	/**
	 * Clears the leaf's dirty flag — called by the emitter once the leaf page has been collected for this flush, so
	 * the next commit suppresses it unless it is mutated again. Transaction-aware (clears the layer's flag in a
	 * transaction; the committed instance the merge produces defaults to clean anyway).
	 */
	void clearDirty();

	/**
	 * Stamps a freshly allocated page sequence onto the leaf. The stamp lands on the live (source) node so the
	 * commit-merge carries it forward into the committed tree.
	 *
	 * @param pageSequence the allocated page sequence (>= 0)
	 */
	void setPageSequence(int pageSequence);

}
