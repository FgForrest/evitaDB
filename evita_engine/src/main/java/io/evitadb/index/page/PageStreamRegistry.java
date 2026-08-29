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
 * - the **ordered live-page list**, the same pages in ascending key order — the list the last published `PAGED` root
 *   record carries. It is what decides whether the root must be re-emitted this commit: the root is rewritten iff the
 *   list the flush collected differs from it. Kept alongside the set rather than derived from it because the set is
 *   unordered and the root's list is not.
 *
 * **Commit handshake.** A flush stages the next live set for each touched stream ({@link #stage(int, int[])}); the
 * staged sets become live only when the commit is known durable ({@link #publishStaged()}). A failed flush is never
 * followed by another flush of the same data — a failure during trunk incorporation suspends the catalog's transaction
 * processing, and one on the warm-up path makes the catalog unpublishable — so a staged set that never publishes is
 * simply abandoned in memory and the registry is rebuilt from disk on restart; nothing ever diffs against it. The
 * high-water, by contrast, advances *live* at allocation time — an aborted transaction never reaches flush so it
 * allocates nothing, while a flush that allocates then crashes before its durable write merely burns an id harmlessly
 * (advance-only).
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
	 * Returns the page sequences live in the given stream's published set that are NOT among {@code liveSequences} — the
	 * pages a leaf merge dropped this commit (freed pages, to be removed from storage). Reads the published set (not
	 * the staged one), so the result is stable across the flush and merge passes of a single commit.
	 *
	 * Single pass: fill an upper-bound-sized buffer, then return it as-is or as a trimmed copy.
	 *
	 * Note this is the FREED-page difference only. A `PAGED -> SINGLE` collapse must not reclaim from the published set:
	 * that set lags a whole flush behind for as long as nothing publishes, so the collapse would silently reclaim
	 * nothing. It uses {@link #pendingLivePageSequences(int)} instead.
	 *
	 * @param streamId the stream to inspect
	 * @param liveSequences the page sequences still live this commit
	 * @return the freed page sequences, or an empty array when none were dropped
	 */
	@Nonnull
	public int[] freedPageSequences(int streamId, @Nonnull Set<Integer> liveSequences) {
		final Set<Integer> publishedLive = livePages(streamId);
		if (publishedLive.isEmpty()) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		final int[] result = new int[publishedLive.size()];
		int count = 0;
		for (final Integer sequence : publishedLive) {
			if (!liveSequences.contains(sequence)) {
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
	 * page-less stream). Unlike the published live set alone ({@link #livePages(int)}, which lags behind a flush that has
	 * staged but not yet published), this reflects the CURRENT tree shape at any point of the flush, so
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
	 * The list is taken in the root's own order and seeds BOTH the live set and the ordered baseline the next flush
	 * diffs against — a stream restored with only its set would leave the first post-load flush comparing its collected
	 * list against an empty baseline and re-emitting a root that never changed.
	 *
	 * @param streamId  the stream to restore
	 * @param highWater the persisted high-water ({@link #NO_PAGE} when the stream has no pages)
	 * @param livePages the live page sequences on disk, in the persisted root's ascending key order (copied defensively)
	 */
	public void restore(int streamId, int highWater, @Nonnull int[] livePages) {
		Assert.isPremiseValid(highWater >= NO_PAGE, "High-water must be >= " + NO_PAGE + ".");
		final PageStream stream = new PageStream();
		stream.highWater = highWater;
		for (final int pageSequence : livePages) {
			// every live page must be a real, allocated page: non-negative and within the high-water envelope
			Assert.isPremiseValid(
				pageSequence >= 0 && pageSequence <= highWater,
				"Live page sequence " + pageSequence + " is out of range [0, " + highWater + "]."
			);
			stream.live.add(pageSequence);
		}
		// a page listed twice would silently shrink the set and desync it from the ordered list
		Assert.isPremiseValid(
			stream.live.size() == livePages.length,
			"Restored live page list contains a duplicate page sequence."
		);
		stream.liveOrdered = Arrays.copyOf(livePages, livePages.length);
		this.streams.put(streamId, stream);
	}

	/**
	 * Read-path twin of {@link #collectChangedPages} and the single shared skeleton behind every paged index's reload:
	 * builds a fresh registry seeded for a just-reassembled paged index. A tree rebuilt from its persisted leaf pages
	 * has every leaf flagged dirty by the replaying inserts even though the leaves are byte-identical to what is already
	 * on disk; this clears each leaf's dirty flag, collects the live-page list and {@link #restore(int, int, int[])
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
		// the handles arrive in ascending key order, so collecting them in order yields the persisted root's own page list
		final int[] livePages = new int[handles.size()];
		int idx = 0;
		for (final H handle : handles) {
			handle.clearDirty();
			livePages[idx++] = handle.getPageSequence();
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
	 * The list is taken in ascending key order — the order the root record persists — and stages the set and the ordered
	 * baseline together, so the two can never drift apart.
	 *
	 * @param streamId the stream being flushed
	 * @param liveSequences the full next live-page list, in ascending key order (copied defensively)
	 */
	public void stage(int streamId, @Nonnull int[] liveSequences) {
		final PageStream stream = this.streams.computeIfAbsent(streamId, id -> new PageStream());
		final Set<Integer> staged = new HashSet<>(liveSequences.length);
		for (final int pageSequence : liveSequences) {
			// a staged page must be a real, allocated page: non-negative and within this stream's high-water envelope
			Assert.isPremiseValid(
				pageSequence >= 0 && pageSequence <= stream.highWater,
				"Staged page sequence " + pageSequence + " is out of range [0, " + stream.highWater + "]."
			);
			staged.add(pageSequence);
		}
		// a page listed twice would silently shrink the set and desync it from the ordered list
		Assert.isPremiseValid(
			staged.size() == liveSequences.length,
			"Staged live page list contains a duplicate page sequence."
		);
		stream.staged = staged;
		stream.stagedOrdered = Arrays.copyOf(liveSequences, liveSequences.length);
	}

	/**
	 * Walks one stream's leaf handles (in ascending key order) and reconciles their page sequences into the granular
	 * write-path emission for this commit — the single shared skeleton behind every paged index's flush. For each leaf:
	 * a not-yet-paged (split-born or fresh) leaf is assigned a freshly {@link #allocate(int) allocated} page sequence
	 * stamped onto the live node so the commit-merge carries it forward; the leaf's sequence is recorded into the ordered
	 * live-page list and the next live-page set. A leaf is (re)written — its {@code pageBuilder} payload collected and its
	 * dirty flag cleared — iff it is brand new or its transaction-aware dirty flag is set, an exact signal a content hash
	 * cannot match: every mutation site sets it, so a real change can never be suppressed. The complete next live-page set
	 * is {@link #stage(int, int[]) staged} here and becomes live only when the commit is published; the pages a leaf merge
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
		stage(streamId, orderedPageSequences);
		// the root must be re-emitted iff the list this flush collected differs from the one the published root carries;
		// when unchanged a caller with a pure page-list root can skip re-emitting it (O(1))
		final PageStream stream = this.streams.get(streamId);
		final boolean pageListChanged = !Arrays.equals(orderedPageSequences, stream.liveOrdered);
		// `anyFreshLeaf` is reassigned in the collect loop above, so it is not effectively final; snapshot it into a
		// final local the failure-path supplier can capture (the eager cross-check below still reads the loop variable)
		final boolean anyFreshLeafSnapshot = anyFreshLeaf;
		// the same predicate derived from the live-set bookkeeping instead: a leaf was allocated (split / first page) or
		// one was dropped (merge). It cannot disagree with the direct comparison while the baseline is sound — leaves are
		// key-ordered, and a steal/merge/split each either preserves that order or changes the membership, so there is no
		// order-only change for the set-difference to miss. A disagreement therefore means the published baseline no
		// longer describes what is on disk, and the freed-page difference silently under-reports against a stale baseline
		// (`∅ - anything = ∅` reads as "nothing changed"), which would skip the root and strand the dropped page listed
		// in it. Surface it here, at the flush that caused it, rather than let it reach storage and fail at cold load.
		Assert.isPremiseValid(
			pageListChanged == (anyFreshLeaf || freedPageSequences.length > 0),
			// the whole diagnostic is built INSIDE the supplier: it runs only when the premise is already violated, so
			// this branch pays for information gathering solely on the error path, never on a healthy flush
			() -> "Page stream " + streamId + " has a stale published page baseline: the collected page list " +
				Arrays.toString(orderedPageSequences) + " disagrees with the published baseline " +
				Arrays.toString(stream.liveOrdered) + ". Cross-check inputs (why the disagreement was caught): " +
				"pageListChanged=" + pageListChanged + ", anyFreshLeaf=" + anyFreshLeafSnapshot + ", freedPages=" +
				Arrays.toString(freedPageSequences) + ", highWater=" + stream.highWater +
				", pagesInCollectedNotBaseline=" +
				Arrays.toString(pageSequenceDifference(orderedPageSequences, stream.liveOrdered)) +
				", pagesInBaselineNotCollected=" +
				Arrays.toString(pageSequenceDifference(stream.liveOrdered, orderedPageSequences)) +
				". A collected list that differs from the baseline while the freed/fresh signals report no structural " +
				"change means the published baseline no longer describes what is on disk — the known cause is a flush " +
				"that failed after staging its next baseline, which a later flush then promoted before re-collecting."
		);
		return new PageEmission<>(
			changedPages, orderedPageSequences, highWater(streamId), freedPageSequences, pageListChanged
		);
	}

	/**
	 * Returns the ascending set difference `from \ remove` — every value present in `from` but not in `remove`. Used
	 * only to build the stale-baseline diagnostic on the failure path (see {@link #collectChangedPages}), so a plain
	 * nested scan over the two short page-sequence lists is preferred to any allocation-heavier set machinery.
	 *
	 * A null `from` (the published baseline is null before the stream's first publish) yields an empty result; a null
	 * `remove` excludes nothing. This keeps the failure-path diagnostic from throwing a second, less useful exception
	 * that would mask the stale-baseline report it exists to produce.
	 *
	 * @param from   the source page-sequence list, or null
	 * @param remove the page-sequence list whose members are excluded, or null
	 * @return the ascending difference (never null)
	 */
	@Nonnull
	private static int[] pageSequenceDifference(@Nullable int[] from, @Nullable int[] remove) {
		if (from == null || from.length == 0) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		final int[] result = new int[from.length];
		int count = 0;
		for (final int candidate : from) {
			boolean present = false;
			if (remove != null) {
				for (final int excluded : remove) {
					if (candidate == excluded) {
						present = true;
						break;
					}
				}
			}
			if (!present) {
				result[count++] = candidate;
			}
		}
		final int[] trimmed = Arrays.copyOf(result, count);
		Arrays.sort(trimmed);
		return trimmed;
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
				stream.liveOrdered = stream.stagedOrdered;
				stream.staged = null;
				stream.stagedOrdered = null;
			}
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
		 * The published live-page list in ascending key order — the page list the root record on disk carries. Always the
		 * ordered view of {@link #live}: both are written together, so they can never drift apart.
		 */
		@Nullable private int[] liveOrdered = ArrayUtils.EMPTY_INT_ARRAY;
		/**
		 * The pending live-page set staged by the in-flight flush; {@code null} when nothing is staged. Promoted to
		 * {@link #live} on {@link PageStreamRegistry#publishStaged()}; a staged set whose flush never completes is
		 * abandoned when the catalog suspends and the registry is rebuilt from disk on restart.
		 */
		@Nullable private Set<Integer> staged;
		/**
		 * The ordered view of {@link #staged}; {@code null} exactly when {@link #staged} is. Promoted to
		 * {@link #liveOrdered} in lockstep with it.
		 */
		@Nullable private int[] stagedOrdered;
	}
}
