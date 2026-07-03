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

package io.evitadb.index.page;

import io.evitadb.index.bPlusTree.PagedLeafHandle;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-stream bookkeeping for the granular index page layout. A *stream* is the page sequence of one
 * persisted sub-index (e.g. a single FilterIndex's bucket tree), identified by a compact {@code streamId} (the
 * {@code KeyCompressor} id of its {@code LeafStreamKey}). For every stream the registry holds three things the
 * persisted tree-as-pages layout needs but transactional memory cannot:
 *
 * - an **advance-only page-sequence allocator** ({@link #allocate(int)}). Page sequence numbers are never reused: a
 *   freed page's id is retired forever, so a retained older catalog version that still references that page-PK keeps
 *   resolving the bytes it expects. The allocator is a 32-bit counter; exhaustion is a documented (unbuilt) renumber
 *   escape.
 * - the explicit **high-water** (the maximum page-sequence ever allocated for the stream, {@link #highWater(int)}).
 *   It is persisted verbatim in the {@code PAGED} root record rather than re-derived as {@code max(live pages)} —
 *   a freed/tombstoned maximum page would otherwise let a {@code max}-over-live scan hand back a retired id.
 * - the **live-page set** {@code {pageSequence}} ({@link #livePages(int)}): the page sequences the stream currently has on
 *   disk. It is NOT the change detector — which leaf pages a flush rewrites is decided per leaf by the leaf's own
 *   transaction-aware dirty flag (`BPlusLeafTreeNode.isDirty()`), an exact signal that, unlike a content hash, can
 *   never miss a real change. The live set serves only the freed-page reclaim: pages present last commit but absent
 *   this commit were dropped by a leaf merge and must be removed from storage ({@link #freedPageSequences(int, Set)}).
 *
 * **Commit handshake.** A flush stages the next live set for each touched stream ({@link #stage(int, Set)}); the
 * staged sets become live only when the commit is known durable ({@link #publishStaged()}), and are dropped wholesale
 * if the commit aborts after staging ({@link #discardStaged()}). The high-water, by contrast, advances *live* at
 * allocation time — an aborted transaction never reaches flush so it allocates nothing, while a flush that allocates
 * then crashes before its durable write merely burns an id harmlessly (advance-only).
 *
 * **Residence.** The registry is **owner-resident**: it lives on the committed owner
 * {@code InvertedIndex} (this engine module) and is carried by reference through the index's
 * {@code createCopyWithMergedTransactionalMemory} into the next committed copy. It deliberately lives OUTSIDE
 * transactional memory — it is reached by both the flush (which walks the transaction-aware tree to emit pages) and
 * the commit-merge (which builds the committed catalog), two passes over different object graphs where the
 * transactional index instance is discarded every commit; carrying the registry by reference is what bridges them.
 * It is mutated only by the single catalog writer thread during flush/commit and is therefore not synchronized.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PageStreamRegistry implements Serializable {
	@Serial private static final long serialVersionUID = -5307883597226203356L;

	/**
	 * High-water sentinel for a stream that has never allocated a page; the first {@link #allocate(int)} returns 0.
	 */
	public static final int NO_PAGE = -1;

	/**
	 * Per-stream state keyed by {@code streamId}. Created lazily on first allocation, staging, or restore.
	 */
	private final Map<Integer, PageStream> streams = new HashMap<>(32);

	/**
	 * Allocates the next page-sequence number for the given stream, advancing (and never decreasing) its high-water.
	 * Allocated ids are never reused.
	 *
	 * @param streamId the stream to allocate within
	 * @return the freshly allocated page-sequence number (>= 0)
	 */
	public int allocate(int streamId) {
		final PageStream stream = this.streams.computeIfAbsent(streamId, id -> new PageStream());
		stream.highWater += 1;
		return stream.highWater;
	}

	/**
	 * Returns the high-water (largest page-sequence ever allocated) for the stream, or {@link #NO_PAGE} when the stream
	 * is unknown or has never allocated a page.
	 *
	 * @param streamId the stream to inspect
	 * @return the high-water page-sequence, or {@link #NO_PAGE}
	 */
	public int highWater(int streamId) {
		final PageStream stream = this.streams.get(streamId);
		return stream == null ? NO_PAGE : stream.highWater;
	}

	/**
	 * Whether the registry currently tracks the given stream.
	 *
	 * @param streamId the stream to test
	 * @return true if the stream is known
	 */
	public boolean isKnown(int streamId) {
		return this.streams.containsKey(streamId);
	}

	/**
	 * Returns every page sequence currently live in the given stream's published set — all leaf pages the stream has on
	 * disk — or an empty array when it has none. Used by the `PAGED -> SINGLE` fallback to remove all prior leaf pages
	 * before a sub-index collapses back to its inline shape.
	 *
	 * @param streamId the stream to inspect
	 * @return the live page sequences, or an empty array when the stream has no leaf pages
	 */
	@Nonnull
	public int[] livePageSequences(int streamId) {
		return liveSequencesExcluding(streamId, Collections.emptySet());
	}

	/**
	 * Returns the page sequences live in the given stream's published set that are NOT among {@code liveSequences} — the
	 * pages a leaf merge dropped this commit (freed pages, to be removed from storage). Reads the published set (not
	 * the staged one), so the result is stable across the flush and merge passes of a single commit.
	 *
	 * @param streamId the stream to inspect
	 * @param liveSequences the page sequences still live this commit
	 * @return the freed page sequences, or an empty array when none were dropped
	 */
	@Nonnull
	public int[] freedPageSequences(int streamId, @Nonnull Set<Integer> liveSequences) {
		return liveSequencesExcluding(streamId, liveSequences);
	}

	/**
	 * Shared live-set difference: the published live page sequences of {@code streamId} with every sequence in
	 * {@code excluded} removed, in the set's iteration order. The single helper behind {@link #livePageSequences(int)}
	 * (empty exclusion) and {@link #freedPageSequences(int, Set)} (live-set exclusion).
	 *
	 * Single pass: fill an upper-bound-sized buffer, then return it as-is when nothing was excluded (the common
	 * {@link #livePageSequences(int)} path) or a trimmed copy when a leaf merge dropped some pages.
	 *
	 * @param streamId the stream whose published live set is scanned
	 * @param excluded the page sequences to omit from the result
	 * @return the matching page sequences, or an empty array when none match
	 */
	@Nonnull
	private int[] liveSequencesExcluding(int streamId, @Nonnull Set<Integer> excluded) {
		final Set<Integer> publishedLive = livePages(streamId);
		if (publishedLive.isEmpty()) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		final int[] result = new int[publishedLive.size()];
		int count = 0;
		for (final Integer sequence : publishedLive) {
			if (!excluded.contains(sequence)) {
				result[count++] = sequence;
			}
		}
		if (count == 0) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		return count == result.length ? result : Arrays.copyOf(result, count);
	}

	/**
	 * Returns the page sequences the given stream WILL have on disk once the in-flight flush is durable — its staged set
	 * when a flush has already staged this commit, otherwise its published live set (and an empty array for an unknown /
	 * page-less stream). Unlike {@link #livePageSequences(int)} (which always reads the published set and so lags behind
	 * a flush that has staged but not yet published), this reflects the CURRENT tree shape at any point of the flush, so
	 * an owner can snapshot "what disk holds after this commit" for a sub-index whose pages it must reclaim if the
	 * sub-index is later dropped.
	 *
	 * @param streamId the stream to inspect
	 * @return the staged set when staged this commit, else the published live set, else an empty array
	 */
	@Nonnull
	public int[] pendingLivePageSequences(int streamId) {
		final PageStream stream = this.streams.get(streamId);
		if (stream == null) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		final Set<Integer> pending = stream.staged != null ? stream.staged : stream.live;
		if (pending.isEmpty()) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		final int[] result = new int[pending.size()];
		int count = 0;
		for (final Integer pageSequence : pending) {
			result[count++] = pageSequence;
		}
		return result;
	}

	/**
	 * Seeds a stream's state at cold load from its persisted root record: the explicit high-water and the published
	 * live-page set (rebuilt by the caller from the persisted leaf-page sequence list). Replaces any existing state for
	 * the stream.
	 *
	 * @param streamId  the stream to restore
	 * @param highWater the persisted high-water ({@link #NO_PAGE} when the stream has no pages)
	 * @param livePages the live page sequences on disk (copied defensively)
	 */
	public void restore(int streamId, int highWater, @Nonnull Set<Integer> livePages) {
		Assert.isPremiseValid(highWater >= NO_PAGE, "High-water must be >= " + NO_PAGE + ".");
		final PageStream stream = new PageStream();
		stream.highWater = highWater;
		for (final Integer pageSequence : livePages) {
			// every live page must be a real, allocated page: non-negative and within the high-water envelope
			Assert.isPremiseValid(
				pageSequence >= 0 && pageSequence <= highWater,
				"Live page sequence " + pageSequence + " is out of range [0, " + highWater + "]."
			);
			stream.live.add(pageSequence);
		}
		this.streams.put(streamId, stream);
	}

	/**
	 * Read-path twin of {@link #collectChangedPages} and the single shared skeleton behind every paged index's reload:
	 * builds a fresh registry seeded for a just-reassembled paged index. A tree rebuilt from its persisted leaf pages
	 * has every leaf flagged dirty by the replaying inserts even though the leaves are byte-identical to what is already
	 * on disk; this clears each leaf's dirty flag, collects the live-page set and {@link #restore(int, int, Set)
	 * restores} the stream, so the first post-load commit suppresses every untouched leaf. Like
	 * {@link #collectChangedPages} it consumes the value-agnostic {@link PagedLeafHandle} contract rather than a concrete
	 * tree, so it serves the bucket-, long- and element-keyed trees alike.
	 *
	 * @param streamId              the page-stream id the reassembled tree's leaves belong to
	 * @param highWaterPageSequence the maximum `pageSequence` ever allocated for the stream
	 * @param handles               the reassembled tree's leaf handles (from its `leafPageHandles()`)
	 * @param <H>                   the concrete leaf-handle type the tree exposes
	 * @return the restored page-stream registry
	 */
	@Nonnull
	public static <H extends PagedLeafHandle> PageStreamRegistry restoredFrom(
		int streamId, int highWaterPageSequence, @Nonnull List<H> handles
	) {
		final Set<Integer> livePages = new HashSet<>(handles.size());
		for (final H handle : handles) {
			handle.clearDirty();
			livePages.add(handle.getPageSequence());
		}
		final PageStreamRegistry registry = new PageStreamRegistry();
		registry.restore(streamId, highWaterPageSequence, livePages);
		return registry;
	}

	/**
	 * Returns an unmodifiable view of the stream's published live-page set; empty when the stream is unknown. The view
	 * reflects the set live at call time only — {@link #publishStaged()} swaps in a fresh set, so a view held across a
	 * publish goes stale (it never corrupts: the live set is only ever replaced wholesale, never mutated in place).
	 * Re-fetch after a commit.
	 *
	 * @param streamId the stream to inspect
	 * @return the published live-page set view (never null)
	 */
	@Nonnull
	public Set<Integer> livePages(int streamId) {
		final PageStream stream = this.streams.get(streamId);
		return stream == null ? Collections.emptySet() : Collections.unmodifiableSet(stream.live);
	}

	/**
	 * Stages the next live-page set for the stream — the complete set of page sequences the just-flushed pages occupy
	 * (changed/new pages and unchanged pages alike, freed pages simply absent). The staged set does not become visible
	 * to {@link #livePages(int)} until {@link #publishStaged()}. Staging again before publishing replaces the pending
	 * set.
	 *
	 * @param streamId the stream being flushed
	 * @param liveSequences the full next live-page set (copied defensively)
	 */
	public void stage(int streamId, @Nonnull Set<Integer> liveSequences) {
		final PageStream stream = this.streams.computeIfAbsent(streamId, id -> new PageStream());
		final Set<Integer> staged = new HashSet<>(liveSequences.size());
		for (final Integer pageSequence : liveSequences) {
			// a staged page must be a real, allocated page: non-negative and within this stream's high-water envelope
			Assert.isPremiseValid(
				pageSequence >= 0 && pageSequence <= stream.highWater,
				"Staged page sequence " + pageSequence + " is out of range [0, " + stream.highWater + "]."
			);
			staged.add(pageSequence);
		}
		stream.staged = staged;
	}

	/**
	 * Walks one stream's leaf handles (in ascending key order) and reconciles their page sequences into the granular
	 * write-path emission for this commit — the single shared skeleton behind every paged index's flush. For each leaf:
	 * a not-yet-paged (split-born or fresh) leaf is assigned a freshly {@link #allocate(int) allocated} page sequence
	 * stamped onto the live node so the commit-merge carries it forward; the leaf's sequence is recorded into the ordered
	 * live-page list and the next live-page set. A leaf is (re)written — its {@code pageBuilder} payload collected and its
	 * dirty flag cleared — iff it is brand new or its transaction-aware dirty flag is set, an exact signal a content hash
	 * cannot match: every mutation site sets it, so a real change can never be suppressed. The complete next live-page set
	 * is {@link #stage(int, Set) staged} here and becomes live only when the commit is published; the pages a leaf merge
	 * dropped are returned as {@link #freedPageSequences(int, Set) freed} so the caller can remove them from storage.
	 *
	 * A clean (non-dirty) index must not call this — the caller gates on its own dirty signal.
	 *
	 * @param streamId    the stream being flushed
	 * @param handles     the stream's leaf handles in ascending key order (from the tree's {@code leafPageHandles()})
	 * @param pageBuilder materializes the per-leaf payload for each changed leaf
	 * @param <H>         the concrete leaf-handle type the tree exposes
	 * @param <P>         the per-leaf payload type the caller materializes
	 * @return the changed leaf pages, the ordered live page-sequence list, the high-water and the freed page sequences
	 */
	@Nonnull
	public <H extends PagedLeafHandle, P> PageEmission<P> collectChangedPages(
		int streamId, @Nonnull List<H> handles, @Nonnull PageBuilder<H, P> pageBuilder
	) {
		final int[] orderedPageSequences = new int[handles.size()];
		final List<P> changedPages = new ArrayList<>(handles.size());
		final Set<Integer> nextLive = new HashSet<>(handles.size());
		boolean anyFreshLeaf = false;
		int idx = 0;
		for (final H handle : handles) {
			int pageSequence = handle.getPageSequence();
			final boolean freshLeaf = pageSequence == PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE;
			if (freshLeaf) {
				// split-born / fresh leaf: allocate a page and stamp it onto the live node (the merge carries it forward)
				pageSequence = allocate(streamId);
				handle.setPageSequence(pageSequence);
				anyFreshLeaf = true;
			}
			orderedPageSequences[idx++] = pageSequence;
			nextLive.add(pageSequence);

			// a leaf is (re)written iff it is brand new or its transaction-aware dirty flag is set; once collected the
			// flag is cleared so the next commit suppresses the leaf unless it is mutated again
			if (freshLeaf || handle.isDirty()) {
				changedPages.add(pageBuilder.build(pageSequence, handle));
				handle.clearDirty();
			}
		}
		// pages live in the published set but absent from this commit's live leaves were dropped by a leaf merge: they
		// must be REMOVED from storage, not merely unreferenced — the append-only OffsetIndex never reclaims a record
		// that is neither superseded (page ids are advance-only, never re-keyed) nor explicitly removed
		final int[] freedPageSequences = freedPageSequences(streamId, nextLive);
		stage(streamId, nextLive);
		// the ordered live-page list is byte-identical to the persisted root iff no leaf was allocated (split/first page)
		// and none was freed (merge); when unchanged a caller with a pure page-list root can skip re-emitting it (O(1))
		final boolean pageListChanged = anyFreshLeaf || freedPageSequences.length > 0;
		return new PageEmission<>(
			changedPages, orderedPageSequences, highWater(streamId), freedPageSequences, pageListChanged
		);
	}

	/**
	 * Whether the stream has a pending (staged-but-unpublished) live-page set.
	 *
	 * @param streamId the stream to test
	 * @return true if a staged set is pending
	 */
	public boolean hasStaged(int streamId) {
		final PageStream stream = this.streams.get(streamId);
		return stream != null && stream.staged != null;
	}

	/**
	 * Publishes every pending staged live-page set as the new live set — the commit-is-durable half of the handshake.
	 * Streams with nothing staged are left untouched.
	 */
	public void publishStaged() {
		for (final PageStream stream : this.streams.values()) {
			if (stream.staged != null) {
				stream.live = stream.staged;
				stream.staged = null;
			}
		}
	}

	/**
	 * Drops every pending staged live-page set, leaving each stream's live set intact — the commit-aborted half of the
	 * handshake. The high-water is deliberately not rolled back (allocation is advance-only).
	 */
	public void discardStaged() {
		for (final PageStream stream : this.streams.values()) {
			stream.staged = null;
		}
	}

	/**
	 * Forgets a stream entirely (e.g. its sub-index was dropped). Any pending staged live-page set for it is discarded
	 * too.
	 *
	 * @param streamId the stream to forget
	 */
	public void forget(int streamId) {
		this.streams.remove(streamId);
	}

	/**
	 * Mutable per-stream state. Not a record because the live/staged sets and the high-water mutate in place under the
	 * single writer.
	 */
	private static final class PageStream {
		/**
		 * The largest page-sequence ever allocated for this stream; {@link PageStreamRegistry#NO_PAGE} until the first
		 * allocation. Advance-only.
		 */
		private int highWater = NO_PAGE;
		/**
		 * The published live-page set {@code {pageSequence}} — the leaf pages this stream has on disk.
		 */
		private Set<Integer> live = new HashSet<>();
		/**
		 * The pending live-page set staged by the in-flight flush; {@code null} when nothing is staged. Promoted to
		 * {@link #live} on {@link PageStreamRegistry#publishStaged()}, dropped on
		 * {@link PageStreamRegistry#discardStaged()}.
		 */
		@Nullable private Set<Integer> staged;
	}
}
