/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.array;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.PagedLeafHandle;
import io.evitadb.index.reference.TransactionalReference;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The **position tree** of the two-tree backing for {@link UnorderedLookup}: a count-augmented (order-statistic) B+
 * tree of distinct `int` record ids, ordered by their **logical position** and routed by a stable `long`
 * **order-key**.
 *
 * The leaves are **containers** holding up to {@link #DEFAULT_BLOCK_SIZE} record ids in logical order; each container carries
 * a `long` order-key. Internal nodes route by order-key separators **and** carry the record-id count of each child
 * subtree (the augmentation that makes positions implicit). Because order-key order is identical to logical order, a
 * single tree answers both:
 *
 * - **by position** (`getRecordAt` / select): descend choosing the child whose cumulative count brackets the target;
 * - **by order-key** (`findPositionByOrderKey`): descend by key, summing the counts of the left siblings to obtain
 *   the prefix count of records before the container, then add the in-container offset.
 *
 * Descent is **cursor based** (the root → leaf path is captured during descent) — there are **no parent pointers and
 * no sibling links** — so the very same structure path-copies cleanly under the transactional layer (mirroring
 * {@link io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree}). Mutations touch only the cursor path: `O(log N)`
 * count re-stamps, no suffix renumber, and small fixed-capacity blocks instead of the `O(N)` humongous arrays of the
 * array delegate.
 *
 * This tree is **not** the owner of the `recordId → orderKey` mapping — that is the job of the value index held by
 * the composite that drives this tree. Whenever a mutation assigns or re-stamps a record's order-key (a fresh insert
 * or a container split), the tree reports it through an {@link OrderKeyConsumer} so the composite can keep its value
 * index coherent (INV-COUPLE). Order-keys are minted **only on container split**; on gap exhaustion the whole key
 * space is re-spaced (rare, `O(#containers)`).
 *
 * The tree is a {@link TransactionalLayerProducer}: **outside** a transaction it mutates strictly **in place** (the
 * committed / warm-up delegate, byte-for-byte identical to the legacy behaviour); **inside** a transaction it
 * path-copies (copy-on-write) — only the touched root→leaf path's nodes decouple their arrays into the per-transaction
 * layer, the committed tree stays untouched, and the changes materialise on commit. The records must be distinct.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public class UnorderedLookupTree implements
	TransactionalLayerProducer<Void, UnorderedLookupTree>,
	ConsistencySensitiveDataStructure,
	Serializable {
	@Serial private static final long serialVersionUID = -7242020610200620162L;

	/**
	 * Default (and maximum) physical capacity of a single node block (both container record slots and internal child
	 * slots). A power of two keeps the blocks small enough to be TLAB-allocated and cache friendly. Node arrays are
	 * always allocated to this fixed size; the per-instance {@link #blockSize} is the LOGICAL split threshold (≤ this
	 * value) so tests can force splits/steals/merges at a small fan-out without changing the physical allocation.
	 */
	static final int DEFAULT_BLOCK_SIZE = 64;
	/**
	 * Leaf capacity used by a **paged** tree: one tree leaf holds up to this many record ids so that one leaf maps
	 * exactly to one persisted page (see {@link io.evitadb.index.attribute.ChainIndex} granular persistence). Decoupled
	 * from {@link #DEFAULT_BLOCK_SIZE} (the internal-node fan-out) so leaves can grow to page size while the routing
	 * spine keeps its small, cache-friendly fan-out. 1024 records ≈ 4 KiB (SSD-page-aligned) per leaf page.
	 */
	public static final int PAGE_RECORDS = 1024;
	/**
	 * Default spacing between freshly assigned order-keys. Wide enough that gap subdivision practically never
	 * exhausts before a tree of realistic height is built; exhaustion is nonetheless handled by re-spacing.
	 */
	static final long DEFAULT_ORDER_KEY_GAP = 1L << 40;
	/**
	 * Sentinel used during minting to signal "no container to the right" (rightmost container).
	 */
	private static final long NO_NEXT_KEY = Long.MAX_VALUE;
	/**
	 * Upper bound on tree height used to size the descent cursor. The tree is kept balanced (all leaves at equal depth)
	 * by the delete-side steal/merge consolidation, so for the production fan-out (64) height is bounded by
	 * `log32(N) < 7`. The generous cap of 64 also accommodates the small logical {@link #blockSize} values used in tests
	 * (a fan-out of 2 still tops out at `log2(Integer.MAX_VALUE) ≈ 31`).
	 */
	private static final int MAX_HEIGHT = 64;

	/**
	 * Stable identity of this tree, used by the transactional memory machinery to key its per-transaction diff layer.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Root node of the tree. Holds `null` when the tree is empty.
	 */
	private final TransactionalReference<Node<?>> root;
	/**
	 * Total number of record ids held by the tree (equals the length of the logical array).
	 */
	private final TransactionalReference<Integer> size;
	/**
	 * Spacing between freshly assigned order-keys (overridable for tests that force re-spacing).
	 */
	private final long orderKeyGap;
	/**
	 * Logical maximum number of entries per node before it splits (container record slots / internal child slots). Equal
	 * to {@link #DEFAULT_BLOCK_SIZE} in production; tests may lower it (≤ {@link #DEFAULT_BLOCK_SIZE}) to force frequent
	 * splits/steals/merges at small fan-out. The physical node arrays are always {@link #DEFAULT_BLOCK_SIZE}-sized.
	 */
	private final int blockSize;
	/**
	 * Minimum number of children a NON-ROOT internal node may hold before it underflows and must steal from / merge with
	 * a sibling. Derived as `max(2, (blockSize + 1) / 2)`, which guarantees two minimally-filled siblings always fit into
	 * one node on merge (`(minChildren - 1) + minChildren ≤ blockSize`). Leaf containers have NO minimum-occupancy floor:
	 * the delete side only ever removes EMPTY containers (never merges non-empty ones), so a record never moves between
	 * containers and never needs its order-key re-stamped — only whole child subtrees are moved during rebalancing.
	 */
	private final int minChildren;
	/**
	 * When TRUE this tree maintains, per leaf container, a `long` head bitmask (bit `i` set ⇔ the record in slot `i`
	 * is a chain head) and, per internal-node child, a head count — so {@link #findHeadCovering} locates the head of
	 * the run covering a logical position in `O(log N)` (used by {@link io.evitadb.index.attribute.ChainIndex}). When
	 * FALSE (the default, e.g. the SortIndex family) no head counts are allocated and the leaf masks stay `null`; the
	 * head query / mutation methods must not be called. The per-container head mask is a `long[]` sized by
	 * {@link #leafCapacity} (a leaf holds transiently up to `leafCapacity + 1` records), so head-awareness no longer
	 * caps the fan-out.
	 */
	private final boolean headAware;
	/**
	 * Physical record capacity of a single leaf **container** (its `recordIds` / head-mask arrays are sized to
	 * `leafCapacity + 1` to host the transient pre-split overflow slot). Decoupled from {@link #blockSize} (the
	 * internal-node fan-out): a **paged** tree sizes its leaves to {@link #PAGE_RECORDS} while the routing spine keeps
	 * the small {@link #blockSize} fan-out; a non-paged tree keeps the legacy {@link #DEFAULT_BLOCK_SIZE}-wide leaves so
	 * the SortIndex family is byte-for-byte unaffected.
	 */
	private final int leafCapacity;
	/**
	 * Logical fill threshold at which a leaf container splits (and the bulk-load leaf-packing size). A **paged** tree
	 * splits leaves at {@link #leafCapacity} (page-sized leaves); a non-paged tree keeps the legacy behaviour of
	 * splitting at {@link #blockSize} (so small-fan-out tests still force frequent leaf splits). Always
	 * `<= leafCapacity`, so the transient overflow occupancy (`+1`) fits the physical `leafCapacity + 1` array.
	 */
	private final int leafSplitThreshold;
	/**
	 * Number of 64-bit words in each leaf's head mask: `ceil((leafCapacity + 1) / 64)` on a head-aware tree (the `+1`
	 * accommodates the transient overflow slot at index `leafCapacity`), or `0` (no mask array allocated) otherwise.
	 */
	private final int maskWords;
	/**
	 * When TRUE this tree participates in granular page-based persistence: each leaf carries a logical page sequence and
	 * a dirty flag, and the tree exposes the {@link #leafPageHandles()} / {@link #collectChangedPages()} /
	 * {@link #livePageSequences()} / {@link #forgetPageStream()} enumeration SPI. Also selects the page-sized leaf split
	 * threshold ({@link #leafSplitThreshold}). FALSE (the default, e.g. the SortIndex family) means no page work at all —
	 * the SPI methods throw and the per-leaf page bookkeeping, while present, is never consulted.
	 */
	private final boolean paged;
	/**
	 * Memoized flattened permutation (logical position → record id). Nullified on every mutation. Not transactional —
	 * it is a pure read-cache derived from the current view and is recomputed lazily; only ever populated outside a
	 * transaction (mutations inside a transaction never read it back, they always invalidate it on entry).
	 */
	@Nullable private int[] memoizedArray;

	/**
	 * Creates a new empty tree with the production fan-out and the default order-key gap.
	 */
	public UnorderedLookupTree() {
		this(DEFAULT_BLOCK_SIZE, DEFAULT_ORDER_KEY_GAP, false);
	}

	/**
	 * Creates a new empty tree with the production fan-out and a custom order-key gap (used by tests to force the
	 * re-spacing path cheaply).
	 */
	UnorderedLookupTree(long orderKeyGap) {
		this(DEFAULT_BLOCK_SIZE, orderKeyGap, false);
	}

	/**
	 * Creates a new empty tree with a custom logical {@link #blockSize} (≤ {@link #DEFAULT_BLOCK_SIZE}) and order-key
	 * gap. Used by tests to force splits/steals/merges at a small fan-out.
	 *
	 * @param blockSize   logical split threshold; must be in `[3, DEFAULT_BLOCK_SIZE]`
	 * @param orderKeyGap the order-key spacing
	 */
	UnorderedLookupTree(int blockSize, long orderKeyGap) {
		this(blockSize, orderKeyGap, false);
	}

	/**
	 * Creates a new empty tree with a custom logical {@link #blockSize}, order-key gap and head-awareness
	 * ({@link #headAware}), using {@link #DEFAULT_BLOCK_SIZE}-wide (non-paged) leaves. A head-aware tree carries
	 * per-container head bitmasks and per-child head counts.
	 *
	 * @param blockSize   internal fan-out / leaf split threshold; must be in `[3, DEFAULT_BLOCK_SIZE]`
	 * @param orderKeyGap the order-key spacing
	 * @param headAware   whether the tree maintains head bitmasks / head counts
	 */
	UnorderedLookupTree(int blockSize, long orderKeyGap, boolean headAware) {
		this(blockSize, orderKeyGap, headAware, DEFAULT_BLOCK_SIZE, false);
	}

	/**
	 * Creates a new empty tree with a custom logical {@link #blockSize} (internal fan-out), order-key gap,
	 * head-awareness, per-leaf physical {@link #leafCapacity} and page participation ({@link #paged}). This is the
	 * decoupled constructor: `blockSize` sizes the routing spine (fan-out / min-occupancy) while `leafCapacity` sizes
	 * the leaf containers independently, so a paged {@link io.evitadb.index.attribute.ChainIndex} tree grows page-sized
	 * leaves ({@link #PAGE_RECORDS}) over a small fan-out.
	 *
	 * @param blockSize    internal-node fan-out / min-occupancy driver; must be in `[3, DEFAULT_BLOCK_SIZE]`
	 * @param orderKeyGap  the order-key spacing
	 * @param headAware    whether the tree maintains head bitmasks / head counts
	 * @param leafCapacity physical leaf record capacity; must be `>= blockSize`
	 * @param paged        whether the tree tracks per-leaf page bookkeeping and exposes the page-enumeration SPI (a
	 *                     paged tree splits leaves at `leafCapacity`; a non-paged tree splits at `blockSize`)
	 */
	UnorderedLookupTree(int blockSize, long orderKeyGap, boolean headAware, int leafCapacity, boolean paged) {
		if (blockSize < 3 || blockSize > DEFAULT_BLOCK_SIZE) {
			throw new GenericEvitaInternalError(
				"Block size must be in [3, " + DEFAULT_BLOCK_SIZE + "], got " + blockSize + "!"
			);
		}
		if (leafCapacity < blockSize) {
			throw new GenericEvitaInternalError(
				"Leaf capacity must be >= block size " + blockSize + ", got " + leafCapacity + "!"
			);
		}
		this.blockSize = blockSize;
		this.minChildren = Math.max(2, (blockSize + 1) / 2);
		this.orderKeyGap = orderKeyGap;
		this.headAware = headAware;
		this.leafCapacity = leafCapacity;
		this.leafSplitThreshold = paged ? leafCapacity : blockSize;
		this.maskWords = headAware ? ((leafCapacity + 1 + 63) / 64) : 0;
		this.paged = paged;
		this.root = new TransactionalReference<>(null);
		this.size = new TransactionalReference<>(0);
	}

	/**
	 * Internal constructor used by {@link #createCopyWithMergedTransactionalMemory(Void, TransactionalLayerMaintainer)}
	 * to rebuild a committed tree wrapping the already-merged root and size.
	 *
	 * @param blockSize    the logical fan-out to carry over
	 * @param orderKeyGap  the order-key spacing to carry over
	 * @param headAware    whether the tree maintains head bitmasks / head counts
	 * @param leafCapacity the physical leaf record capacity to carry over
	 * @param paged        whether the tree tracks per-leaf page bookkeeping
	 * @param root         the committed root node (or `null` for an empty tree)
	 * @param size         the committed record count
	 */
	private UnorderedLookupTree(int blockSize, long orderKeyGap, boolean headAware, int leafCapacity, boolean paged, @Nullable Node<?> root, int size) {
		this.blockSize = blockSize;
		this.minChildren = Math.max(2, (blockSize + 1) / 2);
		this.orderKeyGap = orderKeyGap;
		this.headAware = headAware;
		this.leafCapacity = leafCapacity;
		this.leafSplitThreshold = paged ? leafCapacity : blockSize;
		this.maskWords = headAware ? ((leafCapacity + 1 + 63) / 64) : 0;
		this.paged = paged;
		this.root = new TransactionalReference<>(root);
		this.size = new TransactionalReference<>(size);
	}

	/**
	 * Returns the number of record ids in the tree (length of the logical array).
	 */
	public int size() {
		final Integer theSize = this.size.get();
		return theSize == null ? 0 : theSize;
	}

	/**
	 * Same as {@link #size()}, but reads through an already-resolved transaction so a caller touching several
	 * transactional members pays the `CURRENT_TRANSACTION` ThreadLocal read once rather than once per member.
	 *
	 * @param transaction the caller-resolved current transaction, or `null` when outside a transaction
	 * @return the number of record ids visible to the given transaction
	 */
	int size(@Nullable Transaction transaction) {
		final Integer theSize = this.size.get(transaction);
		return theSize == null ? 0 : theSize;
	}

	/**
	 * Returns true when the tree holds no records.
	 */
	public boolean isEmpty() {
		return size() == 0;
	}

	/**
	 * Bulk-loads an empty tree from an array of record ids in logical order, building it bottom-up with fully-filled
	 * containers and evenly spaced order-keys. Reports every record's order-key through `assignments`. `O(N)`.
	 *
	 * @param recordIds   record ids in logical order (must be distinct)
	 * @param assignments callback receiving each `recordId → orderKey` assignment
	 */
	public void bulkLoad(@Nonnull int[] recordIds, @Nonnull OrderKeyConsumer assignments) {
		bulkLoadInternal(recordIds, null, assignments);
	}

	/**
	 * Head-aware bulk load: like {@link #bulkLoad} but additionally marks the records at `sortedHeadPositions`
	 * (ascending logical positions) as chain heads, setting the per-container head masks and per-child head counts
	 * during the same `O(N)` bottom-up build. Because the whole structure — head data included — is built in one pass
	 * and published as the committed BASE, the head marks never land in a discardable transaction layer (unlike a
	 * post-load {@code markHead} sequence). Requires a head-aware tree.
	 *
	 * @param recordIds           record ids in logical order (must be distinct)
	 * @param sortedHeadPositions ascending logical positions of the head records (may be empty)
	 * @param assignments         callback receiving each `recordId → orderKey` assignment
	 */
	public void bulkLoadWithHeads(@Nonnull int[] recordIds, @Nonnull int[] sortedHeadPositions, @Nonnull OrderKeyConsumer assignments) {
		if (!this.headAware) {
			throw new GenericEvitaInternalError("bulkLoadWithHeads requires a head-aware tree!");
		}
		bulkLoadInternal(recordIds, sortedHeadPositions, assignments);
	}

	/**
	 * Shared bulk-load implementation. When `sortedHeadPositions` is non-null (head-aware tree) it additionally sets
	 * the per-container head masks and per-child head counts during the build; when null the head structures stay clear
	 * (a non-head-aware tree allocates none). `O(N)`.
	 */
	private void bulkLoadInternal(@Nonnull int[] recordIds, @Nullable int[] sortedHeadPositions, @Nonnull OrderKeyConsumer assignments) {
		if (getRoot() != null) {
			throw new GenericEvitaInternalError("Bulk-load is only allowed on an empty tree!");
		}
		final int n = recordIds.length;
		if (n == 0) {
			return;
		}
		// 1. pack records into leaf-sized containers (leafSplitThreshold, not the internal fan-out) and report keys
		final int containerCount = (n + this.leafSplitThreshold - 1) / this.leafSplitThreshold;
		Node<?>[] level = new Node<?>[containerCount];
		long[] minKeys = new long[containerCount];
		int[] counts = new int[containerCount];
		int[] headCounts = this.headAware ? new int[containerCount] : null;
		int headCursor = 0;
		int pos = 0;
		for (int c = 0; c < containerCount; c++) {
			final LeafNode container = new LeafNode(true, this.leafCapacity, this.maskWords);
			final long key = (long) c * this.orderKeyGap;
			container.setOrderKey(key);
			final int cnt = Math.min(this.leafSplitThreshold, n - pos);
			final int[] containerRecordIds = container.getRecordIdsForUpdate();
			System.arraycopy(recordIds, pos, containerRecordIds, 0, cnt);
			container.setCount(cnt);
			for (int i = 0; i < cnt; i++) {
				assignments.accept(recordIds[pos + i], key);
			}
			if (this.headAware) {
				// set head bits for the head positions falling in this container's [pos, pos + cnt) range
				final long[] mask = container.getHeadMaskForUpdate();
				int heads = 0;
				if (sortedHeadPositions != null) {
					while (headCursor < sortedHeadPositions.length && sortedHeadPositions[headCursor] < pos + cnt) {
						final int offset = sortedHeadPositions[headCursor] - pos;
						mask[offset >>> 6] |= 1L << (offset & 63);
						heads++;
						headCursor++;
					}
				}
				headCounts[c] = heads;
			}
			level[c] = container;
			minKeys[c] = key;
			counts[c] = cnt;
			pos += cnt;
		}
		setSize(n);
		// 2. build the internal routing spine over the packed leaf level and install the root
		assembleSpineOverLeaves(level, minKeys, counts, headCounts);
	}

	/**
	 * Builds the internal routing spine bottom-up over an already-built leaf level and installs the resulting root.
	 * The leaf level is described by `level` (the leaf nodes in ascending logical order), `minKeys` (each leaf's
	 * order-key, i.e. its subtree's minimum key), `counts` (each leaf's record count) and — on a head-aware tree —
	 * `headCounts` (each leaf's head count). Shared by {@link #bulkLoadInternal} (repacked, fully-filled leaves) and
	 * {@link #assembleFromLeafPages} (page-boundary-preserving leaves): the two differ only in HOW the leaf level is
	 * produced, so this routing-spine construction — which never merges or splits the given leaves — is identical.
	 *
	 * @param level      the leaf (or, on subsequent iterations, internal) nodes of the current level, ascending
	 * @param minKeys    each node's subtree-minimum order-key, aligned with `level`
	 * @param counts     each node's subtree record count, aligned with `level`
	 * @param headCounts each node's subtree head count (head-aware trees only), aligned with `level`, or `null`
	 */
	private void assembleSpineOverLeaves(@Nonnull Node<?>[] level, @Nonnull long[] minKeys, @Nonnull int[] counts, @Nullable int[] headCounts) {
		int levelSize = level.length;
		// build internal levels bottom-up until a single root remains
		while (levelSize > 1) {
			final int parentCount = (levelSize + this.blockSize - 1) / this.blockSize;
			final int base = levelSize / parentCount;
			final int remainder = levelSize % parentCount;
			final Node<?>[] parents = new Node<?>[parentCount];
			final long[] parentMinKeys = new long[parentCount];
			final int[] parentCounts = new int[parentCount];
			final int[] parentHeadCounts = this.headAware ? new int[parentCount] : null;
			int childCursor = 0;
			for (int p = 0; p < parentCount; p++) {
				// distribute children evenly so the tail node never ends up with a single child
				final int childN = base + (p < remainder ? 1 : 0);
				final InternalNode internal = new InternalNode(true, this.headAware);
				final Node<?>[] internalChildren = internal.getChildrenForUpdate();
				final int[] internalCounts = internal.getCountsForUpdate();
				final long[] internalSeparators = internal.getSeparatorsForUpdate();
				final int[] internalHeadCounts = this.headAware ? internal.getHeadCountsForUpdateOrThrow() : null;
				// head-aware ⇒ the child head counts are mandatory; capture a non-null view (fail fast on breach)
				final int[] childHeadCounts = this.headAware ? requireHeadCounts(headCounts) : null;
				internal.setChildCount(childN);
				int subtree = 0;
				int subtreeHeads = 0;
				for (int j = 0; j < childN; j++) {
					internalChildren[j] = level[childCursor + j];
					internalCounts[j] = counts[childCursor + j];
					subtree += counts[childCursor + j];
					if (this.headAware) {
						internalHeadCounts[j] = childHeadCounts[childCursor + j];
						subtreeHeads += childHeadCounts[childCursor + j];
					}
					if (j >= 1) {
						internalSeparators[j - 1] = minKeys[childCursor + j];
					}
				}
				parents[p] = internal;
				parentMinKeys[p] = minKeys[childCursor];
				parentCounts[p] = subtree;
				if (this.headAware) {
					parentHeadCounts[p] = subtreeHeads;
				}
				childCursor += childN;
			}
			level = parents;
			minKeys = parentMinKeys;
			counts = parentCounts;
			headCounts = parentHeadCounts;
			levelSize = parentCount;
		}
		setRoot(level[0]);
		invalidateMemoizedState();
	}

	/**
	 * Boundary-stable reload: rebuilds an empty paged tree with **exactly one leaf per persisted page**, preserving
	 * the persisted leaf boundaries verbatim (no repack). Each page supplies its `pageSequence`, its ordered
	 * `recordIds` and — on a head-aware tree — its `headWords` (the leaf's head bitset, `bit i` ⇔ `recordIds[i]` is a
	 * chain head). Unlike {@link #bulkLoad}, which packs records into fully-filled containers with different
	 * boundaries than the split-history leaves that were persisted, this method keeps the given boundaries so a later
	 * PARTIAL flush never interleaves pages with mismatched boundaries on disk (the one-leaf-per-page reload
	 * correctness requirement). Order-keys are re-minted ephemerally (they are not persisted); each assembled leaf is
	 * stamped with its `pageSequence` and left **`dirty = false`** so a first post-load flush emits nothing
	 * (zero-emission). Requires an empty, paged tree (the pages MUST be supplied in ascending logical order — the
	 * concatenation of their record ids IS the logical array).
	 *
	 * @param pages       the persisted leaf pages in ascending logical order (one tree leaf built per page)
	 * @param assignments callback receiving each `recordId → orderKey` assignment (populates the value index)
	 */
	public void assembleFromLeafPages(@Nonnull List<LeafPageInput> pages, @Nonnull OrderKeyConsumer assignments) {
		requirePaged();
		if (getRoot() != null) {
			throw new GenericEvitaInternalError("assembleFromLeafPages is only allowed on an empty tree!");
		}
		final int pageCount = pages.size();
		if (pageCount == 0) {
			return;
		}
		// 1. build one leaf per page with FIXED boundaries (no repack) - stamp pageSequence, leave dirty=false
		final Node<?>[] level = new Node<?>[pageCount];
		final long[] minKeys = new long[pageCount];
		final int[] counts = new int[pageCount];
		final int[] headCounts = this.headAware ? new int[pageCount] : null;
		int total = 0;
		for (int c = 0; c < pageCount; c++) {
			final LeafPageInput page = pages.get(c);
			final int[] pageRecordIds = page.recordIds();
			final int cnt = pageRecordIds.length;
			// the physical leaf array is leafCapacity+1 wide (transient overflow slot); copy the page records verbatim
			final int[] leafRecordIds = new int[this.leafCapacity + 1];
			System.arraycopy(pageRecordIds, 0, leafRecordIds, 0, cnt);
			// re-mint an ephemeral order-key for this leaf (order-keys are not persisted, re-spaced by leaf index)
			final long key = (long) c * this.orderKeyGap;
			long[] leafMask = null;
			int heads = 0;
			if (this.headAware) {
				// widen the persisted head words (ceil(cnt/64) wide) into the leaf's maskWords-wide head mask
				leafMask = new long[this.maskWords];
				final long[] pageHeadWords = page.headWords();
				if (pageHeadWords != null) {
					System.arraycopy(pageHeadWords, 0, leafMask, 0, pageHeadWords.length);
					for (final long word : pageHeadWords) {
						heads += Long.bitCount(word);
					}
				}
			}
			// build the leaf directly (dirty=false) so a first post-load flush emits nothing; participating node
			// (transactionalLayer=true) so later transactions can layer changes over it
			final LeafNode container = new LeafNode(key, leafRecordIds, cnt, leafMask, page.pageSequence(), false, true);
			for (final int pageRecordId : pageRecordIds) {
				assignments.accept(pageRecordId, key);
			}
			level[c] = container;
			minKeys[c] = key;
			counts[c] = cnt;
			if (this.headAware) {
				headCounts[c] = heads;
			}
			total += cnt;
		}
		setSize(total);
		// 2. build the internal routing spine over the fixed leaf level and install the root
		assembleSpineOverLeaves(level, minKeys, counts, headCounts);
	}

	/**
	 * Immutable input describing one persisted leaf page fed to {@link #assembleFromLeafPages}: the page's logical
	 * sequence, its ordered record ids, and its head bitset (`bit i` ⇔ `recordIds[i]` is a chain head — `null` /
	 * ignored on a non-head-aware tree). The `headWords` array is `ceil(recordIds.length / 64)` words wide (the
	 * meaningful head-mask prefix); it is widened into the leaf's full mask at assembly time.
	 *
	 * @param pageSequence the persisted page sequence to stamp onto the assembled leaf
	 * @param recordIds    the leaf's ordered record ids (copied into the leaf, not aliased)
	 * @param headWords    the leaf's head bitset words, or `null` on a non-head-aware tree
	 */
	public record LeafPageInput(int pageSequence, @Nonnull int[] recordIds, @Nullable long[] headWords) {
	}

	/**
	 * Returns the record id located on the passed logical position.
	 *
	 * @throws GenericEvitaInternalError when the position is out of bounds
	 */
	public int getRecordAt(int position) {
		// Resolve the thread's transaction ONCE. `getRoot()` and `size()` read two different TransactionalReferences,
		// and each would otherwise start with its own `CURRENT_TRANSACTION` ThreadLocal read - two per positional
		// probe, at ~13 probes per sort-attribute insert across 40 low-cardinality attributes per entity. ThreadLocal
		// machinery is 5.25 % of busy-thread wall on that path (issue #1332). The dispatch itself stays HERE, in the
		// public read method, as INV-2 of the STM rules requires - only its duplication is removed.
		final Transaction transaction = Transaction.getCurrentTransactionIfAvailable();
		final Node<?> theRoot = getRoot(transaction);
		if (position < 0 || position >= size(transaction) || theRoot == null) {
			throw new GenericEvitaInternalError(
				"Position " + position + " not found!",
				"Unknown position in the array!"
			);
		}
		Node<?> node = theRoot;
		int remaining = position;
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			while (childIndex < childCount - 1 && remaining >= counts[childIndex]) {
				remaining -= counts[childIndex];
				childIndex++;
			}
			node = children[childIndex];
		}
		final LeafNode leaf = (LeafNode) node;
		return leaf.getRecordIds()[remaining];
	}

	/**
	 * Binary-searches the ascending record ids occupying logical positions `[fromPosition, toPosition)` for the place
	 * `recordId` belongs, and returns the record id **immediately preceding** that place —
	 * {@link Integer#MIN_VALUE} when it belongs at the very start of the array. That is exactly the
	 * `previousRecordId` contract of {@link TransactionalUnorderedIntArray#add(int, int)}.
	 *
	 * An empty range (`fromPosition == toPosition`) is legal and skips the search: the answer is then simply the
	 * record sitting at `fromPosition - 1`, which is what an insert of a brand-new value block needs.
	 *
	 * The whole search runs **inside the tree** rather than as a caller-side loop over {@link #getRecordAt(int)} for
	 * one reason: consecutive probes of a binary search converge, so after the first descent the following probes
	 * usually land in the leaf that descent already resolved. Keeping the search here lets that leaf be retained
	 * across probes in plain locals — a probe inside the retained window costs one array read instead of a fresh
	 * root-to-leaf order-statistic descent. `SortIndexChanges.computePreviousRecord` issues about 13 such probes per
	 * sort-attribute insert at 10M records with 1000 distinct values, across 40 low-cardinality attributes per
	 * entity, which is what makes those descents 19.4 % of busy-thread wall in the profile behind issue #1332.
	 *
	 * Returning the predecessor — rather than the insertion position for the caller to read separately — is what lets
	 * the **final** read share that same window. It is the read most likely to hit it: the predecessor sits one
	 * position below where the search converged, so it is almost always inside the leaf the last probe resolved.
	 *
	 * The retained window is safe **because it cannot outlive this method**: the search is a pure read and nothing
	 * mutates the tree between two probes, so a leaf resolved for one probe is still the leaf covering its position
	 * for the next. Do not hoist the window into a field or into the caller — that assumption stops holding the
	 * moment the window survives across a mutation.
	 *
	 * @param fromPosition first logical position of the searched range, inclusive
	 * @param toPosition   last logical position of the searched range, exclusive
	 * @param recordId     the record id whose predecessor is sought
	 * @return the preceding record id, or {@link Integer#MIN_VALUE} when `recordId` belongs at position zero
	 * @throws GenericEvitaInternalError when the range does not lie within the tree
	 */
	public int findPredecessorInRange(int fromPosition, int toPosition, int recordId) {
		// resolved once for the whole search, exactly as getRecordAt does it for a single probe (INV-2)
		final Transaction transaction = Transaction.getCurrentTransactionIfAvailable();
		final Node<?> theRoot = getRoot(transaction);
		if (fromPosition < 0 || toPosition > size(transaction) || fromPosition > toPosition
			|| (theRoot == null && fromPosition != toPosition)) {
			throw new GenericEvitaInternalError(
				"Range [" + fromPosition + ", " + toPosition + ") is not within the array!",
				"Unknown position in the array!"
			);
		}
		// the retained leaf window: the leaf resolved by the most recent descent and the logical positions it covers
		LeafNode windowLeaf = null;
		int windowBase = 0;
		int windowCount = 0;
		int low = fromPosition;
		int high = toPosition - 1;
		// stays at the range end when every id in the range is smaller than the sought one
		int insertionPosition = toPosition;
		while (low <= high) {
			final int middle = (low + high) >>> 1;
			final int middleRecordId;
			if (windowLeaf != null && middle >= windowBase && middle < windowBase + windowCount) {
				middleRecordId = windowLeaf.getRecordIds()[middle - windowBase];
			} else {
				// window miss - descend from the root and retain the leaf this probe lands in. The descent is
				// inlined rather than delegated because it cannot report both the leaf and its base position
				// without allocating; the sibling readers in this class inline it for the same reason.
				Node<?> node = theRoot;
				int remaining = middle;
				while (node instanceof final InternalNode internal) {
					final int childCount = internal.getChildCount();
					final int[] counts = internal.getCounts();
					final Node<?>[] children = internal.getChildren();
					int childIndex = 0;
					while (childIndex < childCount - 1 && remaining >= counts[childIndex]) {
						remaining -= counts[childIndex];
						childIndex++;
					}
					node = children[childIndex];
				}
				final LeafNode leaf = (LeafNode) node;
				windowLeaf = leaf;
				// `remaining` is the probe's offset inside the leaf, so the leaf starts `remaining` positions back
				windowBase = middle - remaining;
				// the LOGICAL record count - the physical array is leaf-capacity wide regardless of occupancy
				windowCount = leaf.getCount();
				middleRecordId = leaf.getRecordIds()[remaining];
			}
			if (middleRecordId < recordId) {
				low = middle + 1;
			} else {
				insertionPosition = middle;
				if (middleRecordId == recordId) {
					break;
				}
				high = middle - 1;
			}
		}
		// the predecessor sits immediately before the insertion point; a negative position means `recordId` belongs
		// at the very front of the array and therefore has no predecessor
		final int predecessorPosition = insertionPosition - 1;
		if (predecessorPosition < 0) {
			return Integer.MIN_VALUE;
		}
		if (windowLeaf != null && predecessorPosition >= windowBase
			&& predecessorPosition < windowBase + windowCount) {
			return windowLeaf.getRecordIds()[predecessorPosition - windowBase];
		}
		// Window miss - the predecessor lies outside the leaf the search ended in. It can even sit BELOW the searched
		// range, at `fromPosition - 1`, which is the last record of the preceding value block. Delegating to the
		// ordinary single-position read costs one redundant transaction resolution, which is negligible next to the
		// descent it performs, and keeps the descent written once.
		return getRecordAt(predecessorPosition);
	}

	/**
	 * Returns the last record id in the logical array.
	 *
	 * @throws ArrayIndexOutOfBoundsException when the tree is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		final Node<?> theRoot = getRoot();
		if (size() == 0 || theRoot == null) {
			throw new ArrayIndexOutOfBoundsException("Array is empty!");
		}
		Node<?> node = theRoot;
		while (node instanceof final InternalNode internal) {
			node = internal.getChildren()[internal.getChildCount() - 1];
		}
		final LeafNode container = (LeafNode) node;
		return container.getRecordIds()[container.getCount() - 1];
	}

	/**
	 * Returns the logical position of `recordId`, known to live in the container routed by `orderKey`.
	 *
	 * @return the prefix count of the container plus the record's in-container offset
	 * @throws GenericEvitaInternalError when the record is not found in the routed container, or when the
	 *                                   routed key resolves into an empty tree (internal-consistency failure,
	 *                                   since the caller guarantees the record lives in a routed container)
	 */
	public int findPositionByOrderKey(long orderKey, int recordId) {
		int prefix = 0;
		Node<?> node = getRoot();
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final long[] separators = internal.getSeparators();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			while (childIndex < childCount - 1 && orderKey >= separators[childIndex]) {
				prefix += counts[childIndex];
				childIndex++;
			}
			node = children[childIndex];
		}
		if (node == null) {
			// the caller guarantees the record lives in a routed container, so the tree can never be empty here
			throw new GenericEvitaInternalError(
				"Order-key " + orderKey + " could not be routed in an empty tree!",
				"Inconsistent lookup state!"
			);
		}
		return prefix + indexInContainer((LeafNode) node, recordId);
	}

	/**
	 * Returns the head of the run covering logical `position` — the head at the greatest head-position `<= position` —
	 * packed into a `long` as `(headPosition << 32) | (recordId & 0xFFFFFFFF)`. Because logical position 0 is always a
	 * head (the array is a concatenation of head-first runs) the covering head always exists for a valid position.
	 * Requires a head-aware, non-empty tree. `O(log N)` (two order-statistic descents).
	 *
	 * @param position logical position in `[0, size())`
	 * @return the covering head packed as `(headPosition << 32) | (recordId & 0xFFFFFFFF)`
	 */
	public long findHeadCovering(int position) {
		requireHeadAware();
		final int rank = headRank(position);
		return selectHead(rank);
	}

	/**
	 * Returns the number of chain heads at logical positions `[0, position]` (inclusive). Requires a head-aware tree.
	 * Package-private for testing.
	 */
	int headRank(int position) {
		requireHeadAware();
		final Node<?> theRoot = getRoot();
		if (position < 0 || position >= size() || theRoot == null) {
			throw new GenericEvitaInternalError(
				"Position " + position + " not found!",
				"Unknown position in the array!"
			);
		}
		Node<?> node = theRoot;
		int remaining = position;
		int rank = 0;
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final int[] headCounts = internal.getHeadCountsOrThrow();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			// same descent as getRecordAt (>=): land in the container that holds `position`
			while (childIndex < childCount - 1 && remaining >= counts[childIndex]) {
				remaining -= counts[childIndex];
				rank += headCounts[childIndex];
				childIndex++;
			}
			node = children[childIndex];
		}
		final LeafNode leaf = (LeafNode) node;
		// add the heads in slots [0, remaining] of the container across all mask words
		return rank + headRankInLeaf(leaf.getHeadMaskOrThrow(), remaining);
	}

	/**
	 * Returns the `rank`-th chain head (1-indexed) in logical order, packed into a `long` as
	 * `(headPosition << 32) | (recordId & 0xFFFFFFFF)`. Requires a head-aware tree. Package-private for testing.
	 */
	long selectHead(int rank) {
		requireHeadAware();
		final Node<?> theRoot = getRoot();
		if (theRoot == null || rank < 1) {
			throw new GenericEvitaInternalError(
				"Head rank " + rank + " out of range!",
				"Inconsistent lookup state!"
			);
		}
		Node<?> node = theRoot;
		int remainingRank = rank;
		int posPrefix = 0;
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final int[] headCounts = internal.getHeadCountsOrThrow();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			// descend by head counts (1-indexed select): move right while the rank overshoots this child's heads
			while (childIndex < childCount - 1 && remainingRank > headCounts[childIndex]) {
				remainingRank -= headCounts[childIndex];
				posPrefix += counts[childIndex];
				childIndex++;
			}
			node = children[childIndex];
		}
		final LeafNode leaf = (LeafNode) node;
		// locate the `remainingRank`-th set bit (1-indexed) across the container's mask words
		final int localOffset = selectHeadInLeaf(leaf.getHeadMaskOrThrow(), remainingRank);
		if (localOffset < 0) {
			throw new GenericEvitaInternalError(
				"Head rank " + rank + " not found!",
				"Inconsistent lookup state!"
			);
		}
		final int headPos = posPrefix + localOffset;
		final int recordId = leaf.getRecordIds()[localOffset];
		return ((long) headPos << 32) | (recordId & 0xFFFFFFFFL);
	}

	/**
	 * Marks `recordId` (living in the container routed by `orderKey`) as a chain head. Idempotent: a no-op when the
	 * record is already a head. Requires a head-aware tree.
	 */
	void markHead(long orderKey, int recordId) {
		requireHeadAware();
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(orderKey, cursor);
		final int offset = indexInContainer(container, recordId);
		final int word = offset >>> 6;
		final long bit = 1L << (offset & 63);
		if ((container.getHeadMaskOrThrow()[word] & bit) == 0L) {
			container.getHeadMaskForUpdate()[word] |= bit;
			propagateHeadCountDelta(cursor, +1);
		}
	}

	/**
	 * Clears the chain-head mark of `recordId` (living in the container routed by `orderKey`). Idempotent: a no-op when
	 * the record is not a head. Requires a head-aware tree.
	 */
	void unmarkHead(long orderKey, int recordId) {
		requireHeadAware();
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(orderKey, cursor);
		final int offset = indexInContainer(container, recordId);
		final int word = offset >>> 6;
		final long bit = 1L << (offset & 63);
		if ((container.getHeadMaskOrThrow()[word] & bit) != 0L) {
			container.getHeadMaskForUpdate()[word] &= ~bit;
			propagateHeadCountDelta(cursor, -1);
		}
	}

	/**
	 * Throws when a head operation is attempted on a non-head-aware tree (a programming error — only
	 * {@link io.evitadb.index.attribute.ChainIndex} enables head tracking).
	 */
	private void requireHeadAware() {
		if (!this.headAware) {
			throw new GenericEvitaInternalError(
				"Head operations require a head-aware tree!",
				"Inconsistent lookup state!"
			);
		}
	}

	/**
	 * Throws when a page operation is attempted on a non-paged tree (a programming error — only paged trees carry the
	 * per-leaf page bookkeeping the SPI enumerates).
	 */
	private void requirePaged() {
		if (!this.paged) {
			throw new GenericEvitaInternalError(
				"Page operations require a paged tree!",
				"Inconsistent lookup state!"
			);
		}
	}

	/**
	 * Returns `headCounts` when present, throwing otherwise. Head-awareness is a whole-tree property, so on a head-aware
	 * tree every internal node carries a non-`null` head-count array; a `null` here would mean the invariant was broken
	 * (a head-count operation reached a non-head-aware node) and must fail fast instead of dereferencing to an NPE.
	 */
	@Nonnull
	private static int[] requireHeadCounts(@Nullable int[] headCounts) {
		if (headCounts == null) {
			throw new GenericEvitaInternalError(
				"Head counts requested on a non-head-aware node!",
				"Inconsistent lookup state!"
			);
		}
		return headCounts;
	}

	/**
	 * Returns `headMask` when present, throwing otherwise. Head-awareness is a whole-tree property, so on a head-aware
	 * tree every leaf carries a non-`null` head mask; a `null` here would mean the invariant was broken (a head-mask
	 * operation reached a non-head-aware leaf) and must fail fast instead of dereferencing to an NPE.
	 */
	@Nonnull
	private static long[] requireHeadMask(@Nullable long[] headMask) {
		if (headMask == null) {
			throw new GenericEvitaInternalError(
				"Head mask requested on a non-head-aware leaf!",
				"Inconsistent lookup state!"
			);
		}
		return headMask;
	}

	/**
	 * Inserts `recordId` at the logical `index` (descending by position to the proper container). Reports the
	 * resulting `recordId → orderKey` assignment(s) through `assignments`.
	 */
	public void insertAtPosition(int index, int recordId, @Nonnull OrderKeyConsumer assignments) {
		if (getRoot() == null) {
			final LeafNode container = new LeafNode(true, this.leafCapacity, this.maskWords);
			container.setOrderKey(0L);
			container.getRecordIdsForUpdate()[0] = recordId;
			container.setCount(1);
			setRoot(container);
			setSize(1);
			assignments.accept(recordId, 0L);
			invalidateMemoizedState();
			return;
		}
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByPosition(index, cursor);
		final int offset = Math.min(cursor.leafOffset, container.getCount());
		insertIntoContainer(container, offset, recordId, cursor, assignments);
		invalidateMemoizedState();
	}

	/**
	 * Inserts `recordId` immediately after `previousRecordId` inside the container routed by `previousOrderKey`.
	 * Reports the resulting `recordId → orderKey` assignment(s) through `assignments`.
	 *
	 * @throws GenericEvitaInternalError when `previousRecordId` is not found in the routed container, or when
	 *                                   the descent is attempted on an empty tree (internal-consistency failure)
	 */
	public void insertAfter(long previousOrderKey, int previousRecordId, int recordId, @Nonnull OrderKeyConsumer assignments) {
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(previousOrderKey, cursor);
		final int offset = indexInContainer(container, previousRecordId) + 1;
		insertIntoContainer(container, offset, recordId, cursor, assignments);
		invalidateMemoizedState();
	}

	/**
	 * Removes `recordId` from the container routed by `orderKey`. Reports any order-key re-stamps caused by container
	 * collapse through `reassignments` (currently none — empty containers are simply unlinked).
	 *
	 * @throws GenericEvitaInternalError when the record is not found in the routed container, or when the
	 *                                   descent is attempted on an empty tree (internal-consistency failure)
	 */
	public void removeByOrderKey(long orderKey, int recordId, @Nonnull OrderKeyConsumer reassignments) {
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(orderKey, cursor);
		final int offset = indexInContainer(container, recordId);
		final int count = container.getCount();
		final int[] recordIds = container.getRecordIdsForUpdate();
		System.arraycopy(recordIds, offset + 1, recordIds, offset, count - offset - 1);
		container.setCount(count - 1);
		if (this.headAware) {
			// a removed record leaving the tree also drops its head mark; decrement head counts iff it was a head
			final long[] mask = container.getHeadMaskForUpdate();
			final boolean removedHead = ((mask[offset >>> 6] >>> (offset & 63)) & 1L) != 0L;
			removeHeadSlot(mask, offset);
			if (removedHead) {
				propagateHeadCountDelta(cursor, -1);
			}
		}
		propagateCountDelta(cursor, -1);
		setSize(size() - 1);
		if (container.getCount() == 0) {
			removeEmptyContainer(cursor);
		}
		invalidateMemoizedState();
	}

	/**
	 * Returns the flattened unordered array of record ids in logical order.
	 */
	@Nonnull
	public int[] getArray() {
		// inside a transaction the flattened view depends on the per-transaction layer, so it must never be memoized
		// on the shared instance - otherwise the transactional view would poison the committed read-cache (and vice
		// versa). Only the committed (no-transaction) view is cached.
		if (Transaction.isTransactionAvailable()) {
			final int[] result = new int[size()];
			final Node<?> theRoot = getRoot();
			if (theRoot != null) {
				flattenInto(theRoot, result, new int[1]);
			}
			return result;
		}
		if (this.memoizedArray == null) {
			final int[] result = new int[size()];
			final Node<?> theRoot = getRoot();
			if (theRoot != null) {
				flattenInto(theRoot, result, new int[1]);
			}
			this.memoizedArray = result;
		}
		return this.memoizedArray;
	}

	/**
	 * Creates a forward {@link PositionCursor} over the tree's logical positions in ascending order. Resolving a run of
	 * ascending positions through the cursor costs amortized `O(1)` per emit (it walks leaf-to-leaf, staying inside the
	 * current leaf while the requested position falls in its range), instead of the `O(log N)` root descent that a
	 * per-position {@link #getRecordAt(int)} would pay. Serves sparse mask emits, skip-consumers and full scans without
	 * materialising the flattened array.
	 *
	 * The cursor reads the live (transaction-aware) tree, so it must be created and fully consumed within a single
	 * query / transaction scope with no interleaved mutation of this tree.
	 *
	 * @return a forward position cursor
	 */
	@Nonnull
	public PositionCursor forwardPositionCursor() {
		return new PositionCursor(false);
	}

	/**
	 * Creates a reverse {@link PositionCursor} that emits the logical array in descending order: emit index `d` resolves
	 * to the record at logical position `size() - 1 - d`. Mirrors the ascending tree leaf-to-leaf from the right without
	 * materialising a reversed copy (the descending counterpart of {@link #forwardPositionCursor()}); same amortized
	 * `O(1)` per emit and same single-scope consumption constraint.
	 *
	 * @return a reverse position cursor
	 */
	@Nonnull
	public PositionCursor reversePositionCursor() {
		return new PositionCursor(true);
	}

	/*
		PAGING SPI (paged trees only)
	 */

	/**
	 * Returns whether the tree spans more than one leaf (its root is an internal node). The granular-persistence
	 * predicate: a single-leaf tree is persisted inline (SINGLE), a multi-leaf tree as individual leaf pages (PAGED).
	 *
	 * @return true when the root is internal (≥ 2 leaves)
	 */
	public boolean isRootInternal() {
		return getRoot() instanceof InternalNode;
	}

	/**
	 * Returns one {@link LeafPageHandle} per leaf in ascending logical order — the page-emission view of a paged tree.
	 * Each handle wraps a live leaf node (reached from the current, transaction-aware root), so stamping a page sequence
	 * through it mutates the node the commit-merge carries forward, and the exposed record ids / head-mask words are
	 * read-your-writes. Requires a paged tree.
	 *
	 * @return the ordered leaf-page handles (empty for an empty tree)
	 */
	@Nonnull
	public List<LeafPageHandle> leafPageHandles() {
		requirePaged();
		final List<LeafNode> leaves = collectLeaves();
		final List<LeafPageHandle> handles = new ArrayList<>(leaves.size());
		for (final LeafNode leaf : leaves) {
			handles.add(new LeafPageHandleImpl(leaf));
		}
		return handles;
	}

	/**
	 * Returns the page handles of the leaves changed since the last flush (dirty leaves) in ascending logical order —
	 * the pages the current commit must (re)write. Requires a paged tree.
	 *
	 * @return the changed (dirty) leaf-page handles in ascending order
	 */
	@Nonnull
	public List<LeafPageHandle> collectChangedPages() {
		requirePaged();
		final List<LeafNode> leaves = collectLeaves();
		final List<LeafPageHandle> changed = new ArrayList<>();
		for (final LeafNode leaf : leaves) {
			if (leaf.isDirty()) {
				changed.add(new LeafPageHandleImpl(leaf));
			}
		}
		return changed;
	}

	/**
	 * Returns, in ascending logical order, the page sequences currently assigned to the tree's leaves (skipping
	 * not-yet-paged leaves). Used to enumerate the live leaf pages before a PAGED->SINGLE collapse. Requires a paged tree.
	 *
	 * @return the assigned page sequences, or an empty array when no leaf has a page yet
	 */
	@Nonnull
	public int[] livePageSequences() {
		requirePaged();
		final List<LeafNode> leaves = collectLeaves();
		int assigned = 0;
		for (final LeafNode leaf : leaves) {
			if (leaf.getPageSequence() != PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE) {
				assigned++;
			}
		}
		final int[] result = new int[assigned];
		int i = 0;
		for (final LeafNode leaf : leaves) {
			final int seq = leaf.getPageSequence();
			if (seq != PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE) {
				result[i++] = seq;
			}
		}
		return result;
	}

	/**
	 * Resets the page bookkeeping of every leaf — un-assigns the page sequence and clears the dirty flag — so the tree
	 * starts from a clean baseline (e.g. after a PAGED->SINGLE collapse, the caller having already emitted removals for
	 * the prior live pages via {@link #livePageSequences()}). Requires a paged tree.
	 */
	public void forgetPageStream() {
		requirePaged();
		for (final LeafNode leaf : collectLeaves()) {
			// create-free reset: this runs at flush time after commit() has forbidden new layer creation, and the walk
			// visits EVERY leaf including collapse survivors this transaction never touched (which carry no layer) - so
			// it must NOT route through the create-on-write setPageSequence/clearDirty (see LeafNode.forgetPage)
			leaf.forgetPage();
		}
	}

	/**
	 * A live, write-path handle over a single leaf page of a paged tree: the value-agnostic page bookkeeping
	 * ({@link PagedLeafHandle}) plus the leaf's ordered record ids and head-mask words, from which the granular write
	 * path materializes the persisted page payload. It wraps the live leaf node, so page-sequence stamps mutate the node
	 * the commit-merge carries forward and the exposed content is read-your-writes.
	 */
	public interface LeafPageHandle extends PagedLeafHandle {

		/**
		 * Returns a copy of this leaf's record ids in logical order (only the valid slots).
		 *
		 * @return the leaf's ordered record ids
		 */
		@Nonnull
		int[] recordIds();

		/**
		 * Returns a copy of this leaf's head-mask words (bit `i` ⇔ slot `i` is a chain head), or `null` on a
		 * non-head-aware tree.
		 *
		 * @return the leaf's head-mask words, or `null`
		 */
		@Nullable
		long[] headMask();
	}

	@Override
	public String toString() {
		return "UnorderedLookupTree" + Arrays.toString(getArray());
	}

	@Override
	public Void createLayer() {
		return null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// remove the tree's own diff layer
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		// recurse into the size and root references and the whole node graph so that a tree which was created
		// and discarded within the same transaction (e.g. a removed sub-index) does not leave any of its inner
		// transactional objects ALIVE - which would otherwise be detected as stale during the commit sweep (INV-5).
		// The current (in-transaction) root MUST be read BEFORE the root reference's own layer is dropped - otherwise
		// getRoot() would fall back to the committed root (null for a tree built within this transaction) and the
		// node-graph recursion would be skipped, leaking every node layer.
		final Node<?> theRoot = getRoot();
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		if (theRoot != null) {
			removeLayerRecursively(theRoot, transactionalLayer);
		}
	}

	@Nonnull
	@Override
	public UnorderedLookupTree createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// the reference merge yields the (possibly unchanged) root NODE; we must then merge that node's own diff
		// layer and the whole subtree below it, exactly like the reference B+ tree does
		final Node<?> referencedRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElse(null);
		final Node<?> theRoot = referencedRoot == null
			? null
			: transactionalLayer.getStateCopyWithCommittedChanges(referencedRoot);
		final int theSize = transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElse(0);
		return new UnorderedLookupTree(
			this.blockSize, this.orderKeyGap, this.headAware, this.leafCapacity, this.paged, theRoot, theSize);
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		final List<String> errors = new ArrayList<>();
		final Node<?> theRoot = getRoot();
		if (theRoot == null) {
			if (size() != 0) {
				errors.add("Empty tree (null root) but size() reports " + size() + "!");
			}
		} else {
			final int[] leafDepth = new int[]{-1};
			final long[] lastKey = new long[]{Long.MIN_VALUE};
			final long[] recordCount = new long[1];
			final long[] headCount = this.headAware ? new long[1] : null;
			verifyConsistency(theRoot, 0, true, leafDepth, lastKey, errors, recordCount, headCount);
			if (recordCount[0] != size()) {
				errors.add("Tracked size " + size() + " does not match counted records " + recordCount[0] + "!");
			}
		}
		return errors.isEmpty()
			? new ConsistencyReport(ConsistencyState.CONSISTENT, "The position tree is balanced and consistent.")
			: new ConsistencyReport(ConsistencyState.BROKEN, String.join("\n", errors));
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Recursively verifies the subtree rooted at `node`: equal leaf depth (balance), non-root internal min-occupancy,
	 * block-size bounds, per-child subtree-count augmentation, order-key separators (each separator equals the minimum
	 * order-key of the child it borders) and global strict order-key monotonicity across containers in logical order.
	 * On a head-aware tree (`headCount` non-null) it additionally verifies the per-child head-count augmentation and that
	 * no container carries a head bit beyond its valid record slots, accumulating the subtree's head total into
	 * `headCount`. Leaf containers are intentionally NOT checked for a minimum occupancy floor — the delete side only
	 * removes EMPTY containers and never merges non-empty ones, so under-full containers are a legal (memory-only) state.
	 * Accumulates the total record count into `recordCount` and returns the record count of `node`'s subtree.
	 */
	private int verifyConsistency(
		@Nonnull Node<?> node, int depth, boolean isRoot,
		@Nonnull int[] leafDepth, @Nonnull long[] lastKey,
		@Nonnull List<String> errors, @Nonnull long[] recordCount, @Nullable long[] headCount
	) {
		if (node instanceof final LeafNode leaf) {
			if (leafDepth[0] == -1) {
				leafDepth[0] = depth;
			} else if (leafDepth[0] != depth) {
				errors.add("Unbalanced tree: container at depth " + depth + " but expected " + leafDepth[0] + "!");
			}
			final int count = leaf.getCount();
			if (!isRoot && count < 1) {
				errors.add("Empty non-root container encountered!");
			}
			if (count > this.leafSplitThreshold) {
				errors.add("Container overflow: " + count + " > leaf split threshold " + this.leafSplitThreshold + "!");
			}
			final long key = leaf.getOrderKey();
			if (lastKey[0] != Long.MIN_VALUE && key <= lastKey[0]) {
				errors.add("Container order-key " + key + " is not strictly greater than its predecessor " + lastKey[0] + "!");
			}
			lastKey[0] = key;
			recordCount[0] += count;
			if (headCount != null) {
				// no head bit may be set beyond the container's valid record slots
				final long[] mask = leaf.getHeadMaskOrThrow();
				if (anyBitSetFrom(mask, count)) {
					errors.add("Container has head bits set beyond its record count " + count + "!");
				}
				headCount[0] += headBitCount(mask);
			}
			return count;
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		if (isRoot) {
			if (childCount < 2) {
				errors.add("Root internal node has fewer than 2 children (" + childCount + ")!");
			}
		} else if (childCount < this.minChildren) {
			errors.add("Internal underflow: " + childCount + " children < minimum " + this.minChildren + "!");
		}
		if (childCount > this.blockSize) {
			errors.add("Internal overflow: " + childCount + " children > block size " + this.blockSize + "!");
		}
		final Node<?>[] children = internal.getChildren();
		final int[] counts = internal.getCounts();
		final int[] headCounts = internal.getHeadCounts();
		final long[] separators = internal.getSeparators();
		int sum = 0;
		for (int i = 0; i < childCount; i++) {
			if (i > 0) {
				final long childMin = minOrderKey(children[i]);
				if (separators[i - 1] != childMin) {
					errors.add("Separator[" + (i - 1) + "]=" + separators[i - 1] + " != child subtree min order-key " + childMin + "!");
				}
			}
			final long headBefore = headCount == null ? 0L : headCount[0];
			final int childRecords = verifyConsistency(children[i], depth + 1, false, leafDepth, lastKey, errors, recordCount, headCount);
			if (counts[i] != childRecords) {
				errors.add("Stored subtree count " + counts[i] + " != actual " + childRecords + " at child " + i + "!");
			}
			if (headCount != null && headCounts != null) {
				final long childHeads = headCount[0] - headBefore;
				if (headCounts[i] != childHeads) {
					errors.add("Stored head count " + headCounts[i] + " != actual " + childHeads + " at child " + i + "!");
				}
			}
			sum += childRecords;
		}
		return sum;
	}

	/**
	 * Returns the minimum order-key in the subtree rooted at `node` (the order-key of its leftmost container).
	 */
	private static long minOrderKey(@Nonnull Node<?> node) {
		Node<?> current = node;
		while (current instanceof final InternalNode internal) {
			current = internal.getChildren()[0];
		}
		return ((LeafNode) current).getOrderKey();
	}

	/**
	 * Returns the current view of the root node — the transactional view when a layer exists, the committed node
	 * otherwise. Returns `null` for an empty tree.
	 */
	@Nullable
	private Node<?> getRoot() {
		return this.root.get();
	}

	/**
	 * Same as {@link #getRoot()}, but reads through an already-resolved transaction - see
	 * {@link #size(Transaction)} for why the two are paired.
	 *
	 * @param transaction the caller-resolved current transaction, or `null` when outside a transaction
	 * @return the root node visible to the given transaction, or `null` for an empty tree
	 */
	@Nullable
	private Node<?> getRoot(@Nullable Transaction transaction) {
		return this.root.get(transaction);
	}

	/**
	 * Replaces the root node, dropping any transactional layer held by the previous root that gets discarded.
	 */
	private void setRoot(@Nullable Node<?> newRoot) {
		final Node<?> currentRoot = getRoot();
		if (currentRoot != null && currentRoot != newRoot
			&& Transaction.getTransactionalMemoryLayerIfExists(currentRoot) != null) {
			currentRoot.removeLayer();
		}
		this.root.set(newRoot);
	}

	/**
	 * Sets the total record count.
	 */
	private void setSize(int newSize) {
		this.size.set(newSize);
	}

	/**
	 * Recursively removes the transactional diff layers of the passed node and its descendants. Walks the current
	 * transactional view of the tree. Nodes hold only primitive `int`/`long` payloads, so there are no per-value
	 * {@link TransactionalLayerProducer}s to sweep.
	 *
	 * @param node               the node whose layer (and that of its subtree) is to be removed
	 * @param transactionalLayer the maintainer that owns the diff layers
	 */
	private static void removeLayerRecursively(
		@Nonnull Node<?> node,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (node instanceof final InternalNode internal) {
			final Node<?>[] children = internal.getChildren();
			final int childCount = internal.getChildCount();
			for (int i = 0; i < childCount; i++) {
				removeLayerRecursively(children[i], transactionalLayer);
			}
		} else if (node instanceof LeafNode) {
			// leaf payload is primitive ints, never TransactionalLayerProducers - nothing to recurse into
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + node);
		}
		// the node's own removeLayer asserts a layer exists - only call it when one is actually open
		if (Transaction.getTransactionalMemoryLayerIfExists(node) != null) {
			node.removeLayer(transactionalLayer);
		}
	}

	/**
	 * Inserts `recordId` into `container` at the given in-container `offset`, propagates the count up the cursor path,
	 * reports the assignment(s) and splits the container on overflow.
	 */
	private void insertIntoContainer(@Nonnull LeafNode container, int offset, int recordId, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final int count = container.getCount();
		final int[] recordIds = container.getRecordIdsForUpdate();
		System.arraycopy(recordIds, offset, recordIds, offset + 1, count - offset);
		recordIds[offset] = recordId;
		container.setCount(count + 1);
		if (this.headAware) {
			// the freshly inserted record is never a head - open a clear bit at `offset` (no head-count change)
			insertHeadSlot(container.getHeadMaskForUpdate(), offset);
		}
		propagateCountDelta(cursor, +1);
		setSize(size() + 1);
		if (container.getCount() > this.leafSplitThreshold) {
			splitContainer(container, offset, recordId, cursor, assignments);
		} else {
			assignments.accept(recordId, container.getOrderKey());
		}
	}

	/**
	 * Descends from the root to the container holding logical `position`, capturing the cursor path and storing the
	 * in-container offset in {@link Cursor#leafOffset}.
	 *
	 * @throws GenericEvitaInternalError when invoked on an empty tree (callers must handle the empty case before
	 *                                   descending)
	 */
	@Nonnull
	private LeafNode descendByPosition(int position, @Nonnull Cursor cursor) {
		cursor.depth = 0;
		Node<?> node = getRoot();
		if (node == null) {
			// positional descent is only invoked on a non-empty tree (the empty case is handled by the caller)
			throw new GenericEvitaInternalError(
				"Cannot descend by position " + position + " into an empty tree!",
				"Inconsistent lookup state!"
			);
		}
		int remaining = position;
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			// for an insertion at a child boundary we stay in the left child (append at its end)
			while (childIndex < childCount - 1 && remaining > counts[childIndex]) {
				remaining -= counts[childIndex];
				childIndex++;
			}
			cursor.push(internal, childIndex);
			node = children[childIndex];
		}
		cursor.leafOffset = remaining;
		return (LeafNode) node;
	}

	/**
	 * Descends from the root to the container routed by `orderKey`, capturing the cursor path.
	 *
	 * @throws GenericEvitaInternalError when invoked on an empty tree (descent is only valid for records known to
	 *                                   live in the tree)
	 */
	@Nonnull
	private LeafNode descendByKey(long orderKey, @Nonnull Cursor cursor) {
		cursor.depth = 0;
		Node<?> node = getRoot();
		if (node == null) {
			// key descent is only invoked for records known to live in the tree, so it is never empty here
			throw new GenericEvitaInternalError(
				"Cannot descend by order-key " + orderKey + " into an empty tree!",
				"Inconsistent lookup state!"
			);
		}
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final long[] separators = internal.getSeparators();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			while (childIndex < childCount - 1 && orderKey >= separators[childIndex]) {
				childIndex++;
			}
			cursor.push(internal, childIndex);
			node = children[childIndex];
		}
		return (LeafNode) node;
	}

	/**
	 * Adjusts the stored subtree counts of every internal node on the cursor path by `delta`.
	 */
	private static void propagateCountDelta(@Nonnull Cursor cursor, int delta) {
		for (int level = 0; level < cursor.depth; level++) {
			final InternalNode node = cursor.path[level];
			final int[] counts = node.getCountsForUpdate();
			counts[cursor.idx[level]] += delta;
		}
	}

	/**
	 * Adjusts the stored head counts of every internal node on the cursor path by `delta`. Only meaningful on a
	 * head-aware tree (every path node then carries a non-null `headCounts`); a no-op guard tolerates a non-head-aware
	 * node defensively.
	 */
	private static void propagateHeadCountDelta(@Nonnull Cursor cursor, int delta) {
		for (int level = 0; level < cursor.depth; level++) {
			final InternalNode node = cursor.path[level];
			final int[] headCounts = node.getHeadCountsForUpdate();
			if (headCounts != null) {
				headCounts[cursor.idx[level]] += delta;
			}
		}
	}

	/**
	 * In-place head-mask transform for inserting a NON-head slot at in-container `offset`: bits `[offset, ...)` shift up
	 * one across the mask words (each word carries its old MSB into the next word's bit 0) and the freshly opened bit
	 * `offset` stays clear. `offset` is at most `leafCapacity` (the transient overflow slot) and the mask has
	 * `ceil((leafCapacity + 1) / 64)` words, so the top shifted bit never falls off the array.
	 */
	private static void insertHeadSlot(@Nonnull long[] mask, int offset) {
		final int wordIndex = offset >>> 6;
		final int bitIndex = offset & 63;
		// shift the words strictly above `wordIndex` up by one bit, each receiving the previous word's OLD MSB into bit 0
		for (int w = mask.length - 1; w > wordIndex; w--) {
			mask[w] = (mask[w] << 1) | (mask[w - 1] >>> 63);
		}
		// within `wordIndex`: keep bits [0, bitIndex), shift bits [bitIndex, 62] up one, open a clear bit at `bitIndex`
		// (its old MSB was already carried into wordIndex + 1 above, read before this rewrite)
		final long low = (1L << bitIndex) - 1;
		mask[wordIndex] = (mask[wordIndex] & low) | ((mask[wordIndex] & ~low) << 1);
	}

	/**
	 * In-place head-mask transform for removing slot `offset`: drops its bit and shifts bits `(offset, ...]` down one
	 * across the mask words (each higher word pulls the next word's bit 0 into its MSB). Guards the `>>> 64` no-op when
	 * `offset` sits at the top bit (63) of its word.
	 */
	private static void removeHeadSlot(@Nonnull long[] mask, int offset) {
		final int wordIndex = offset >>> 6;
		final int bitIndex = offset & 63;
		final long low = (1L << bitIndex) - 1;                    // bits [0, bitIndex)
		// bits (bitIndex, 63] of `wordIndex` shift down to [bitIndex, 62]; bit 63 is filled from the next word's bit 0
		final long high = bitIndex == 63 ? 0L : ((mask[wordIndex] >>> (bitIndex + 1)) << bitIndex);
		final long carry = wordIndex + 1 < mask.length ? ((mask[wordIndex + 1] & 1L) << 63) : 0L;
		mask[wordIndex] = (mask[wordIndex] & low) | high | carry;
		// shift every higher word down by one bit, each pulling the next word's bit 0 into its MSB
		for (int w = wordIndex + 1; w < mask.length; w++) {
			final long nextCarry = (w + 1 < mask.length) ? ((mask[w + 1] & 1L) << 63) : 0L;
			mask[w] = (mask[w] >>> 1) | nextCarry;
		}
	}

	/**
	 * Fills `dest` (a fresh right-container mask) with `src` shifted right by `shift` bits: `dest` bit `i` = `src` bit
	 * `i + shift`. Multi-word capable (`shift` may exceed 64 when `leafCapacity` does). Guards the `<< 64` no-op.
	 */
	private static void shiftMaskRight(@Nonnull long[] src, int shift, @Nonnull long[] dest) {
		final int wordShift = shift >>> 6;
		final int bitShift = shift & 63;
		for (int i = 0; i < dest.length; i++) {
			final int srcLo = i + wordShift;
			long value = srcLo < src.length ? (src[srcLo] >>> bitShift) : 0L;
			if (bitShift != 0) {
				final int srcHi = srcLo + 1;
				if (srcHi < src.length) {
					value |= src[srcHi] << (64 - bitShift);
				}
			}
			dest[i] = value;
		}
	}

	/**
	 * Clears bits `[from, ...)` of `mask` in place (keeps bits `[0, from)`), used to trim the left container after a split.
	 */
	private static void clearMaskFrom(@Nonnull long[] mask, int from) {
		final int wordIndex = from >>> 6;
		final int bitIndex = from & 63;
		if (wordIndex < mask.length) {
			mask[wordIndex] &= (1L << bitIndex) - 1;
			for (int w = wordIndex + 1; w < mask.length; w++) {
				mask[w] = 0L;
			}
		}
	}

	/**
	 * Returns the total number of set head bits across the mask words (the container's head count).
	 */
	private static int headBitCount(@Nonnull long[] mask) {
		int count = 0;
		for (final long word : mask) {
			count += Long.bitCount(word);
		}
		return count;
	}

	/**
	 * Returns the number of set head bits in slots `[0, inclusiveOffset]` across the mask words (the in-container head
	 * rank). Guards the `1L << 64` no-op when `inclusiveOffset` sits at the top bit (63) of its word.
	 */
	private static int headRankInLeaf(@Nonnull long[] mask, int inclusiveOffset) {
		final int fullWords = inclusiveOffset >>> 6;
		int count = 0;
		for (int w = 0; w < fullWords; w++) {
			count += Long.bitCount(mask[w]);
		}
		final int bit = inclusiveOffset & 63;
		final long inclusive = bit == 63 ? -1L : ((1L << (bit + 1)) - 1);
		return count + Long.bitCount(mask[fullWords] & inclusive);
	}

	/**
	 * Returns the in-container slot offset of the `rank`-th set head bit (1-indexed) across the mask words, or `-1` when
	 * fewer than `rank` bits are set.
	 */
	private static int selectHeadInLeaf(@Nonnull long[] mask, int rank) {
		int seen = 0;
		for (int w = 0; w < mask.length; w++) {
			long word = mask[w];
			while (word != 0L) {
				final int tz = Long.numberOfTrailingZeros(word);
				seen++;
				if (seen == rank) {
					return (w << 6) + tz;
				}
				word &= word - 1;
			}
		}
		return -1;
	}

	/**
	 * Returns whether any head bit is set at slot `from` or above across the mask words (consistency check: no head bit
	 * may sit beyond a container's valid record slots).
	 */
	private static boolean anyBitSetFrom(@Nonnull long[] mask, int from) {
		final int wordIndex = from >>> 6;
		if (wordIndex >= mask.length) {
			return false;
		}
		final int bitIndex = from & 63;
		// high-bits mask (all bits at bitIndex and above): -(1L << bitIndex) == ~((1L << bitIndex) - 1)
		if ((mask[wordIndex] & -(1L << bitIndex)) != 0L) {
			return true;
		}
		for (int w = wordIndex + 1; w < mask.length; w++) {
			if (mask[w] != 0L) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the number of chain heads held in the subtree rooted at `node` (leaf: popcount of its head mask; internal:
	 * sum of its per-child head counts). Only called on head-aware trees.
	 */
	private static int subtreeHeadCount(@Nonnull Node<?> node) {
		if (node instanceof final LeafNode leaf) {
			return headBitCount(leaf.getHeadMaskOrThrow());
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		final int[] headCounts = internal.getHeadCountsOrThrow();
		int sum = 0;
		for (int i = 0; i < childCount; i++) {
			sum += headCounts[i];
		}
		return sum;
	}

	/**
	 * Splits an overflowing container into two, mints an order-key for the new right container, reports the affected
	 * `recordId → orderKey` assignments (the newly inserted record plus the records moved to the right container) and
	 * propagates the split up the cursor path.
	 *
	 * @param newOffset the in-container offset at which the new record was just inserted
	 * @param newRecordId the newly inserted record id
	 */
	private void splitContainer(@Nonnull LeafNode container, int newOffset, int newRecordId, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final int total = container.getCount();
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final long rightKey = mintOrderKey(container, cursor, assignments);
		// the offspring node participates in the transactional layer so that any later in-savepoint
		// mutation routes through a snapshot-able layer and can be rolled back per-entity
		final LeafNode right = new LeafNode(true, this.leafCapacity, this.maskWords);
		right.setOrderKey(rightKey);
		final int[] containerRecordIds = container.getRecordIdsForUpdate();
		final int[] rightRecordIds = right.getRecordIdsForUpdate();
		System.arraycopy(containerRecordIds, leftCount, rightRecordIds, 0, rightCount);
		right.setCount(rightCount);
		container.setCount(leftCount);
		Arrays.fill(containerRecordIds, leftCount, total, 0);
		// partition the head mask along the same split point: right gets bits [leftCount, total) shifted down to
		// [0, rightCount); the container keeps bits [0, leftCount). Multi-word capable (leafCapacity may exceed 64).
		int rightHeadCount = 0;
		if (this.headAware) {
			final long[] leftMask = container.getHeadMaskForUpdate();
			final long[] rightMask = right.getHeadMaskForUpdate();
			shiftMaskRight(leftMask, leftCount, rightMask);
			clearMaskFrom(leftMask, leftCount);
			rightHeadCount = headBitCount(rightMask);
		}
		// report the new record (in whichever half it landed) and every other record that moved to the right half
		final long newRecordKey = newOffset < leftCount ? container.getOrderKey() : rightKey;
		assignments.accept(newRecordId, newRecordKey);
		for (int i = 0; i < rightCount; i++) {
			final int movedRecordId = rightRecordIds[i];
			if (movedRecordId != newRecordId) {
				assignments.accept(movedRecordId, rightKey);
			}
		}
		propagateSplit(cursor, cursor.depth - 1, right, rightKey, rightCount, rightHeadCount);
	}

	/**
	 * Propagates a node split up the cursor: inserts `newRight` (with `newRightMinKey` / `newRightCount`) into the
	 * parent at `level`, creating a new root when the split reaches the top and splitting parents on overflow.
	 *
	 * @throws GenericEvitaInternalError when the split reaches the top yet the root is missing (a split can only
	 *                                   propagate up from an existing leaf)
	 */
	private void propagateSplit(@Nonnull Cursor cursor, int level, @Nonnull Node<?> newRight, long newRightMinKey, int newRightCount, int newRightHeadCount) {
		Node<?> right = newRight;
		long rightMinKey = newRightMinKey;
		int rightCount = newRightCount;
		int rightHeadCount = newRightHeadCount;
		while (true) {
			if (level < 0) {
				// the split reached the root - grow a new root above the two halves
				final Node<?> oldRoot = getRoot();
				if (oldRoot == null) {
					// a split can only propagate up from an existing leaf, so the root is necessarily present
					throw new GenericEvitaInternalError(
						"Split propagated above a missing root!",
						"Inconsistent lookup state!"
					);
				}
				final InternalNode newRoot = new InternalNode(true, this.headAware);
				final Node<?>[] children = newRoot.getChildrenForUpdate();
				final int[] counts = newRoot.getCountsForUpdate();
				final long[] separators = newRoot.getSeparatorsForUpdate();
				children[0] = oldRoot;
				children[1] = right;
				counts[0] = subtreeCount(oldRoot);
				counts[1] = rightCount;
				separators[0] = rightMinKey;
				if (this.headAware) {
					final int[] headCounts = newRoot.getHeadCountsForUpdateOrThrow();
					headCounts[0] = subtreeHeadCount(oldRoot);
					headCounts[1] = rightHeadCount;
				}
				newRoot.setChildCount(2);
				// the new root replaces the old one, but the old root remains its first child, so do NOT drop the
				// old root's layer here - simply publish the new root reference
				this.root.set(newRoot);
				return;
			}
			final InternalNode parent = cursor.path[level];
			final int ci = cursor.idx[level];
			// the existing child's stored count was already incremented for the inserted record; shed the moved part
			parent.getCountsForUpdate()[ci] -= rightCount;
			if (parent.getHeadCounts() != null) {
				parent.getHeadCountsForUpdateOrThrow()[ci] -= rightHeadCount;
			}
			insertIntoInternal(parent, ci, right, rightMinKey, rightCount, rightHeadCount);
			if (parent.getChildCount() <= this.blockSize) {
				return;
			}
			// parent overflowed - split it and continue up the cursor
			final long[] promotedKey = new long[1];
			final int[] promotedCount = new int[1];
			final int[] promotedHeadCount = new int[1];
			right = splitInternal(parent, promotedKey, promotedCount, promotedHeadCount);
			rightMinKey = promotedKey[0];
			rightCount = promotedCount[0];
			rightHeadCount = promotedHeadCount[0];
			level--;
		}
	}

	/**
	 * Inserts `newChild` (separator `minKey`, subtree count `childCount`, head count `childHeadCount`) into `parent`
	 * immediately after child `ci`. Head counts are mirrored only when `parent` carries them (head-aware tree).
	 */
	private static void insertIntoInternal(@Nonnull InternalNode parent, int ci, @Nonnull Node<?> newChild, long minKey, int childCount, int childHeadCount) {
		final int parentChildCount = parent.getChildCount();
		final Node<?>[] children = parent.getChildrenForUpdate();
		final int[] counts = parent.getCountsForUpdate();
		final long[] separators = parent.getSeparatorsForUpdate();
		final int target = ci + 1;
		System.arraycopy(children, target, children, target + 1, parentChildCount - target);
		System.arraycopy(counts, target, counts, target + 1, parentChildCount - target);
		// the separator between child ci and the new child goes at index ci, shifting the rest right
		System.arraycopy(separators, ci, separators, ci + 1, parentChildCount - 1 - ci);
		children[target] = newChild;
		counts[target] = childCount;
		separators[ci] = minKey;
		if (parent.getHeadCounts() != null) {
			final int[] headCountsForUpdate = parent.getHeadCountsForUpdateOrThrow();
			System.arraycopy(headCountsForUpdate, target, headCountsForUpdate, target + 1, parentChildCount - target);
			headCountsForUpdate[target] = childHeadCount;
		}
		parent.setChildCount(parentChildCount + 1);
	}

	/**
	 * Splits an overflowing internal node, returning the new right node and reporting (through the single-element
	 * out-parameters) the promoted separator key, the right node's subtree count and (on a head-aware tree) the right
	 * node's head count.
	 */
	@Nonnull
	private static InternalNode splitInternal(
		@Nonnull InternalNode node,
		@Nonnull long[] promotedKey,
		@Nonnull int[] promotedCount,
		@Nonnull int[] promotedHeadCount
	) {
		final int total = node.getChildCount();
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final boolean headAware = node.getHeadCounts() != null;
		// the offspring node participates in the transactional layer so that any later in-savepoint
		// mutation routes through a snapshot-able layer and can be rolled back per-entity
		final InternalNode right = new InternalNode(true, headAware);
		final Node<?>[] nodeChildren = node.getChildrenForUpdate();
		final int[] nodeCounts = node.getCountsForUpdate();
		final long[] nodeSeparators = node.getSeparatorsForUpdate();
		final Node<?>[] rightChildren = right.getChildrenForUpdate();
		final int[] rightCounts = right.getCountsForUpdate();
		final long[] rightSeparators = right.getSeparatorsForUpdate();
		System.arraycopy(nodeChildren, leftCount, rightChildren, 0, rightCount);
		System.arraycopy(nodeCounts, leftCount, rightCounts, 0, rightCount);
		// right keeps the separators strictly inside its child range (rightCount - 1 of them)
		System.arraycopy(nodeSeparators, leftCount, rightSeparators, 0, rightCount - 1);
		right.setChildCount(rightCount);
		// the separator between the two halves is promoted to the parent (not kept in either node)
		promotedKey[0] = nodeSeparators[leftCount - 1];
		int rightSubtreeCount = 0;
		for (int i = 0; i < rightCount; i++) {
			rightSubtreeCount += rightCounts[i];
		}
		promotedCount[0] = rightSubtreeCount;
		int rightHeadCount = 0;
		if (headAware) {
			final int[] nodeHeadCounts = node.getHeadCountsForUpdateOrThrow();
			final int[] rightHeadCounts = right.getHeadCountsForUpdateOrThrow();
			System.arraycopy(nodeHeadCounts, leftCount, rightHeadCounts, 0, rightCount);
			for (int i = 0; i < rightCount; i++) {
				rightHeadCount += rightHeadCounts[i];
			}
			Arrays.fill(nodeHeadCounts, leftCount, total, 0);
		}
		promotedHeadCount[0] = rightHeadCount;
		// clear the moved slots in the left node
		Arrays.fill(nodeChildren, leftCount, total, null);
		Arrays.fill(nodeCounts, leftCount, total, 0);
		Arrays.fill(nodeSeparators, leftCount - 1, total - 1, 0L);
		node.setChildCount(leftCount);
		return right;
	}

	/**
	 * Mints a fresh order-key for a new right container inserted immediately after the container at the bottom of the
	 * cursor, re-spacing the whole key space when the gap to the next container is exhausted.
	 *
	 * @throws GenericEvitaInternalError when the configured order-key gap is too small to subdivide between two
	 *                                   adjacent containers even after a full re-spacing (a misconfiguration that
	 *                                   would otherwise mint a colliding order-key)
	 */
	private long mintOrderKey(@Nonnull LeafNode container, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final long nextKey = nextContainerKey(cursor);
		if (nextKey == NO_NEXT_KEY) {
			// rightmost container - append a full gap to the right
			long candidate = container.getOrderKey() + this.orderKeyGap;
			if (candidate <= container.getOrderKey()) {
				respaceOrderKeys(assignments);
				candidate = container.getOrderKey() + this.orderKeyGap;
			}
			return candidate;
		}
		long gap = nextKey - container.getOrderKey();
		if (gap < 2) {
			respaceOrderKeys(assignments);
			gap = nextContainerKey(cursor) - container.getOrderKey();
			if (gap < 2) {
				// even an evenly re-spaced key space cannot subdivide a single gap - the configured order-key gap
				// is too small to host another container between two neighbours, which would mint a colliding key
				throw new GenericEvitaInternalError(
					"Order-key gap " + this.orderKeyGap + " is too small to subdivide between adjacent containers!",
					"Inconsistent lookup state!"
				);
			}
		}
		return container.getOrderKey() + gap / 2;
	}

	/**
	 * Returns the order-key of the container immediately following the one at the bottom of the cursor, or
	 * {@link #NO_NEXT_KEY} when it is the rightmost container. Found by walking up the cursor to the first ancestor
	 * that has a right sibling subtree (whose separator is that subtree's minimum order-key).
	 */
	private static long nextContainerKey(@Nonnull Cursor cursor) {
		for (int level = cursor.depth - 1; level >= 0; level--) {
			final InternalNode node = cursor.path[level];
			final int ci = cursor.idx[level];
			if (ci < node.getChildCount() - 1) {
				return node.getSeparators()[ci];
			}
		}
		return NO_NEXT_KEY;
	}

	/**
	 * Reassigns every container's order-key to an evenly spaced value (preserving logical order) and rebuilds all
	 * internal separators. Rare; `O(#containers)`. Inside a transaction this decouples every node it touches into the
	 * transactional layer - acceptable because re-spacing is rare.
	 */
	private void respaceOrderKeys(@Nonnull OrderKeyConsumer assignments) {
		final Node<?> theRoot = getRoot();
		if (theRoot != null) {
			reassignKeys(theRoot, new long[]{0L}, assignments);
			recomputeSeparators(theRoot);
		}
	}

	/**
	 * Reassigns container order-keys in logical (in-order) sequence, spaced by {@link #orderKeyGap}, reporting each
	 * record's new order-key so the value index stays coherent (re-spacing re-stamps every record).
	 */
	private void reassignKeys(@Nonnull Node<?> node, @Nonnull long[] counter, @Nonnull OrderKeyConsumer assignments) {
		if (node instanceof final LeafNode leaf) {
			final long newKey = counter[0] * this.orderKeyGap;
			leaf.setOrderKey(newKey);
			counter[0]++;
			final int count = leaf.getCount();
			final int[] recordIds = leaf.getRecordIds();
			for (int i = 0; i < count; i++) {
				assignments.accept(recordIds[i], newKey);
			}
		} else {
			final InternalNode internal = (InternalNode) node;
			final int childCount = internal.getChildCount();
			final Node<?>[] children = internal.getChildren();
			for (int i = 0; i < childCount; i++) {
				reassignKeys(children[i], counter, assignments);
			}
		}
	}

	/**
	 * Recomputes the separators of every internal node in the subtree rooted at `node` from the current container
	 * order-keys, returning the minimum order-key in the subtree.
	 */
	private static long recomputeSeparators(@Nonnull Node<?> node) {
		if (node instanceof final LeafNode leaf) {
			return leaf.getOrderKey();
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		final Node<?>[] children = internal.getChildren();
		final long min = recomputeSeparators(children[0]);
		final long[] separators = internal.getSeparatorsForUpdate();
		for (int i = 1; i < childCount; i++) {
			separators[i - 1] = recomputeSeparators(children[i]);
		}
		return min;
	}

	/**
	 * Removes an emptied container from the tree and restores B+ balance. The container is unlinked from its immediate
	 * parent (keeping separators exact), then internal-node underflow is repaired up the cursor by {@link #consolidate}.
	 *
	 * Crucially this performs INTERNAL rebalancing only — it moves whole child SUBTREES between internal siblings (steal)
	 * or fuses two internal siblings (merge), and never moves records between leaf containers. Container membership is
	 * therefore untouched, so no record's order-key is ever re-stamped (no {@link OrderKeyConsumer} traffic on delete).
	 * The price is that under-full (sparse) containers are left in place — a memory-only effect, identical to the legacy
	 * behaviour, and recompacted whenever the tree is rebuilt from a flat array via {@link #bulkLoad}.
	 */
	private void removeEmptyContainer(@Nonnull Cursor cursor) {
		if (cursor.depth == 0) {
			// the emptied container was the root - the tree is now empty
			setRoot(null);
			return;
		}
		// unlink the emptied container from its immediate parent (separators stay exact - see removeChildAt)
		final int leafLevel = cursor.depth - 1;
		final InternalNode parent = cursor.path[leafLevel];
		final int ci = cursor.idx[leafLevel];
		// capture the child before the structural mutation; once detached it is unreachable from the committed root, so
		// its transactional layer (touched while emptying it) must be dropped here or the commit sweep flags it stale
		final Node<?> removedChild = parent.getChildren()[ci];
		removeChildAt(parent, ci);
		dropLayerIfPresent(removedChild);
		// removing the HEAD child (index 0) raises this subtree's minimum order-key, so the separator that borders this
		// subtree on its left in some ancestor is now stale - refresh it up the cursor BEFORE rebalancing. This must run
		// even when no underflow follows, and it also feeds consolidate's merge `bridge` (which reads that separator).
		if (ci == 0) {
			propagateNewMinimumUp(cursor, leafLevel);
		}
		// repair internal underflow upward
		consolidate(cursor, leafLevel);
	}

	/**
	 * Refreshes the ancestor separator that borders the subtree rooted at `cursor.path[fromLevel]` after that subtree's
	 * minimum order-key changed (its head child was removed). Walks up the cursor to the first ancestor where the
	 * subtree is NOT the leftmost child and rewrites that ancestor's bordering separator to the subtree's new minimum;
	 * if every ancestor was entered through its leftmost child the subtree holds the global minimum and no separator
	 * borders it on the left. Keeps separators EXACT (the consistency invariant) and feeds the merge bridge separator.
	 *
	 * @param cursor    the descent cursor captured to the removed container
	 * @param fromLevel the level of the internal node whose subtree minimum just rose
	 */
	private static void propagateNewMinimumUp(@Nonnull Cursor cursor, int fromLevel) {
		final long newMinimum = minOrderKey(cursor.path[fromLevel]);
		for (int level = fromLevel; level >= 1; level--) {
			final int indexInParent = cursor.idx[level - 1];
			if (indexInParent > 0) {
				cursor.path[level - 1].getSeparatorsForUpdate()[indexInParent - 1] = newMinimum;
				return;
			}
		}
	}

	/**
	 * Repairs internal-node underflow from `level` upward after a child was removed at that level. Each underflowing
	 * non-root internal first tries to STEAL a whole child subtree from an adjacent internal sibling that has one to
	 * spare, and otherwise MERGEs with an adjacent sibling (which removes a child from the grandparent and propagates the
	 * underflow check one level up). The root is collapsed onto its only child when it drops to a single child — a
	 * uniform, all-paths height reduction that keeps every leaf at equal depth. Only whole subtrees move between
	 * internals, so leaf container membership — and hence every record's order-key — is left untouched.
	 *
	 * @param cursor the descent cursor captured to the just-removed container
	 * @param level  the level (internal node `cursor.path[level]`) that just lost a child and may now underflow
	 */
	private void consolidate(@Nonnull Cursor cursor, int level) {
		while (level >= 0) {
			final InternalNode node = cursor.path[level];
			if (level == 0) {
				// the root has no minimum-occupancy floor; only collapse it when it degenerates to a single child
				final int rootChildCount = node.getChildCount();
				if (rootChildCount == 1) {
					setRoot(node.getChildren()[0]);
				} else if (rootChildCount == 0) {
					setRoot(null);
				}
				return;
			}
			if (node.getChildCount() >= this.minChildren) {
				// no underflow - the structure above is unaffected
				return;
			}
			final InternalNode grandParent = cursor.path[level - 1];
			final int gi = cursor.idx[level - 1];
			// 1) try to steal a child subtree from the left sibling
			if (gi > 0) {
				final InternalNode left = (InternalNode) grandParent.getChildren()[gi - 1];
				if (left.getChildCount() > this.minChildren) {
					stealChildFromLeft(node, left, grandParent, gi);
					return;
				}
			}
			// 2) try to steal a child subtree from the right sibling
			if (gi < grandParent.getChildCount() - 1) {
				final InternalNode right = (InternalNode) grandParent.getChildren()[gi + 1];
				if (right.getChildCount() > this.minChildren) {
					stealChildFromRight(node, right, grandParent, gi);
					return;
				}
			}
			// 3) no sibling can spare a child - merge with one (prefer the left), then re-check the grandparent
			if (gi > 0) {
				final InternalNode left = (InternalNode) grandParent.getChildren()[gi - 1];
				mergeInternals(left, node, grandParent, gi);
				dropLayerIfPresent(node);
			} else {
				final InternalNode right = (InternalNode) grandParent.getChildren()[gi + 1];
				mergeInternals(node, right, grandParent, gi + 1);
				dropLayerIfPresent(right);
			}
			level--;
		}
	}

	/**
	 * Moves the LAST child subtree of `left` to the FRONT of the underflowing `node`, fixing up the bordering separator
	 * in `grandParent` and the per-child counts. `gi` is the index of `node` within `grandParent` (`> 0`).
	 */
	private static void stealChildFromLeft(
		@Nonnull InternalNode node,
		@Nonnull InternalNode left,
		@Nonnull InternalNode grandParent,
		int gi
	) {
		final int nc = node.getChildCount();
		final int lc = left.getChildCount();
		final Node<?> moved = left.getChildren()[lc - 1];
		final int movedCount = left.getCounts()[lc - 1];
		final long movedMinKey = minOrderKey(moved);
		// the current separator before `node` is the min order-key of node's old first child
		final long oldNodeMinKey = grandParent.getSeparators()[gi - 1];
		// prepend the moved subtree to node
		final Node<?>[] nodeChildren = node.getChildrenForUpdate();
		final int[] nodeCounts = node.getCountsForUpdate();
		final long[] nodeSeparators = node.getSeparatorsForUpdate();
		System.arraycopy(nodeChildren, 0, nodeChildren, 1, nc);
		System.arraycopy(nodeCounts, 0, nodeCounts, 1, nc);
		System.arraycopy(nodeSeparators, 0, nodeSeparators, 1, nc - 1);
		nodeChildren[0] = moved;
		nodeCounts[0] = movedCount;
		nodeSeparators[0] = oldNodeMinKey;
		node.setChildCount(nc + 1);
		// drop the moved subtree from left's tail
		final Node<?>[] leftChildren = left.getChildrenForUpdate();
		final int[] leftCounts = left.getCountsForUpdate();
		final long[] leftSeparators = left.getSeparatorsForUpdate();
		leftChildren[lc - 1] = null;
		leftCounts[lc - 1] = 0;
		leftSeparators[lc - 2] = 0L;
		left.setChildCount(lc - 1);
		// the separator before node now borders the moved subtree
		grandParent.getSeparatorsForUpdate()[gi - 1] = movedMinKey;
		final int[] gpCounts = grandParent.getCountsForUpdate();
		gpCounts[gi - 1] -= movedCount;
		gpCounts[gi] += movedCount;
		// mirror the head counts through the same moves (head-aware tree only)
		if (node.getHeadCounts() != null) {
			final int movedHeadCount = left.getHeadCountsOrThrow()[lc - 1];
			final int[] nodeHeadCounts = node.getHeadCountsForUpdateOrThrow();
			System.arraycopy(nodeHeadCounts, 0, nodeHeadCounts, 1, nc);
			nodeHeadCounts[0] = movedHeadCount;
			left.getHeadCountsForUpdateOrThrow()[lc - 1] = 0;
			final int[] gpHeadCounts = grandParent.getHeadCountsForUpdateOrThrow();
			gpHeadCounts[gi - 1] -= movedHeadCount;
			gpHeadCounts[gi] += movedHeadCount;
		}
	}

	/**
	 * Moves the FIRST child subtree of `right` to the END of the underflowing `node`, fixing up the bordering separator
	 * in `grandParent` and the per-child counts. `gi` is the index of `node` within `grandParent` (`right` is at `gi+1`).
	 */
	private static void stealChildFromRight(
		@Nonnull InternalNode node,
		@Nonnull InternalNode right,
		@Nonnull InternalNode grandParent,
		int gi
	) {
		final int nc = node.getChildCount();
		final int rc = right.getChildCount();
		final Node<?> moved = right.getChildren()[0];
		final int movedCount = right.getCounts()[0];
		final long movedMinKey = minOrderKey(moved);
		// after removing right's first child, right's new minimum is the min order-key of its old second child
		final long newRightMinKey = right.getSeparators()[0];
		// append the moved subtree to node's tail
		final Node<?>[] nodeChildren = node.getChildrenForUpdate();
		final int[] nodeCounts = node.getCountsForUpdate();
		final long[] nodeSeparators = node.getSeparatorsForUpdate();
		nodeChildren[nc] = moved;
		nodeCounts[nc] = movedCount;
		nodeSeparators[nc - 1] = movedMinKey;
		node.setChildCount(nc + 1);
		// drop the moved subtree from right's head (shift the remainder left)
		final Node<?>[] rightChildren = right.getChildrenForUpdate();
		final int[] rightCounts = right.getCountsForUpdate();
		final long[] rightSeparators = right.getSeparatorsForUpdate();
		System.arraycopy(rightChildren, 1, rightChildren, 0, rc - 1);
		System.arraycopy(rightCounts, 1, rightCounts, 0, rc - 1);
		System.arraycopy(rightSeparators, 1, rightSeparators, 0, rc - 2);
		rightChildren[rc - 1] = null;
		rightCounts[rc - 1] = 0;
		rightSeparators[rc - 2] = 0L;
		right.setChildCount(rc - 1);
		// the separator after node now borders right's new minimum
		grandParent.getSeparatorsForUpdate()[gi] = newRightMinKey;
		final int[] gpCounts = grandParent.getCountsForUpdate();
		gpCounts[gi] += movedCount;
		gpCounts[gi + 1] -= movedCount;
		// mirror the head counts through the same moves (head-aware tree only)
		if (node.getHeadCounts() != null) {
			final int movedHeadCount = right.getHeadCountsOrThrow()[0];
			node.getHeadCountsForUpdateOrThrow()[nc] = movedHeadCount;
			final int[] rightHeadCounts = right.getHeadCountsForUpdateOrThrow();
			System.arraycopy(rightHeadCounts, 1, rightHeadCounts, 0, rc - 1);
			rightHeadCounts[rc - 1] = 0;
			final int[] gpHeadCounts = grandParent.getHeadCountsForUpdateOrThrow();
			gpHeadCounts[gi] += movedHeadCount;
			gpHeadCounts[gi + 1] -= movedHeadCount;
		}
	}

	/**
	 * Fuses `absorbed` into `survivor` (children appended in logical order) and unlinks `absorbed` (at `absorbedIndex`,
	 * always `= survivorIndex + 1`) from `grandParent`, folding its subtree count into the survivor's grandparent slot.
	 * The bridge separator between the two halves is the min order-key of the absorbed subtree (taken from the
	 * grandparent's exact separator). The caller drops the absorbed node's transactional layer.
	 */
	private static void mergeInternals(
		@Nonnull InternalNode survivor,
		@Nonnull InternalNode absorbed,
		@Nonnull InternalNode grandParent,
		int absorbedIndex
	) {
		final int sc = survivor.getChildCount();
		final int ac = absorbed.getChildCount();
		final long bridge = grandParent.getSeparators()[absorbedIndex - 1];
		final int absorbedCount = grandParent.getCounts()[absorbedIndex];
		// append absorbed's children / counts / separators onto the survivor
		final Node<?>[] sChildren = survivor.getChildrenForUpdate();
		final int[] sCounts = survivor.getCountsForUpdate();
		final long[] sSeparators = survivor.getSeparatorsForUpdate();
		final Node<?>[] aChildren = absorbed.getChildren();
		final int[] aCounts = absorbed.getCounts();
		final long[] aSeparators = absorbed.getSeparators();
		System.arraycopy(aChildren, 0, sChildren, sc, ac);
		System.arraycopy(aCounts, 0, sCounts, sc, ac);
		sSeparators[sc - 1] = bridge;
		System.arraycopy(aSeparators, 0, sSeparators, sc, ac - 1);
		survivor.setChildCount(sc + ac);
		// unlink the absorbed slot from the grandparent, dropping the separator that bordered it on the left and folding
		// its subtree count into the survivor's slot
		final int gcc = grandParent.getChildCount();
		final Node<?>[] gChildren = grandParent.getChildrenForUpdate();
		final int[] gCounts = grandParent.getCountsForUpdate();
		final long[] gSeparators = grandParent.getSeparatorsForUpdate();
		gCounts[absorbedIndex - 1] += absorbedCount;
		System.arraycopy(gChildren, absorbedIndex + 1, gChildren, absorbedIndex, gcc - absorbedIndex - 1);
		System.arraycopy(gCounts, absorbedIndex + 1, gCounts, absorbedIndex, gcc - absorbedIndex - 1);
		System.arraycopy(gSeparators, absorbedIndex, gSeparators, absorbedIndex - 1, gcc - absorbedIndex - 1);
		final int newGcc = gcc - 1;
		grandParent.setChildCount(newGcc);
		gChildren[newGcc] = null;
		gCounts[newGcc] = 0;
		gSeparators[newGcc - 1] = 0L;
		// mirror the head counts through the same fuse + unlink (head-aware tree only)
		if (survivor.getHeadCounts() != null) {
			final int absorbedHeadCount = grandParent.getHeadCountsOrThrow()[absorbedIndex];
			final int[] sHeadCounts = survivor.getHeadCountsForUpdateOrThrow();
			System.arraycopy(absorbed.getHeadCountsOrThrow(), 0, sHeadCounts, sc, ac);
			final int[] gHeadCounts = grandParent.getHeadCountsForUpdateOrThrow();
			gHeadCounts[absorbedIndex - 1] += absorbedHeadCount;
			System.arraycopy(gHeadCounts, absorbedIndex + 1, gHeadCounts, absorbedIndex, gcc - absorbedIndex - 1);
			gHeadCounts[newGcc] = 0;
		}
	}

	/**
	 * Drops the transactional diff layer of `node` when one is currently open. Used when a node is detached from the
	 * tree mid-transaction (container emptied, internal node collapsed or spliced out): the node is no longer reachable
	 * from the committed root, so its layer would never be swept by the commit walk and would be reported stale.
	 */
	private static void dropLayerIfPresent(@Nonnull Node<?> node) {
		if (Transaction.getTransactionalMemoryLayerIfExists(node) != null) {
			node.removeLayer();
		}
	}

	/**
	 * Removes the child at index `ci` from `internal`, keeping every remaining separator EXACT. The separator dropped is
	 * the one on the LEFT of the removed child (`ci - 1`, equal to the removed child's own min order-key), so the
	 * separator that was on its right slides over to border the surviving left neighbour — which is precisely that
	 * neighbour's correct min-key boundary. When removing the head child (`ci == 0`) the head separator (`0`) is dropped
	 * instead (the new head child needs no separator before it).
	 */
	private static void removeChildAt(@Nonnull InternalNode internal, int ci) {
		final int childCount = internal.getChildCount();
		final Node<?>[] children = internal.getChildrenForUpdate();
		final int[] counts = internal.getCountsForUpdate();
		final long[] separators = internal.getSeparatorsForUpdate();
		System.arraycopy(children, ci + 1, children, ci, childCount - ci - 1);
		System.arraycopy(counts, ci + 1, counts, ci, childCount - ci - 1);
		if (childCount >= 2) {
			final int separatorIndex = ci > 0 ? ci - 1 : 0;
			System.arraycopy(separators, separatorIndex + 1, separators, separatorIndex, childCount - separatorIndex - 2);
		}
		final int newChildCount = childCount - 1;
		internal.setChildCount(newChildCount);
		children[newChildCount] = null;
		counts[newChildCount] = 0;
		if (newChildCount >= 1) {
			separators[newChildCount - 1] = 0L;
		}
		// mirror the head counts through the same unlink (head-aware tree only)
		if (internal.getHeadCounts() != null) {
			final int[] headCounts = internal.getHeadCountsForUpdateOrThrow();
			System.arraycopy(headCounts, ci + 1, headCounts, ci, childCount - ci - 1);
			headCounts[newChildCount] = 0;
		}
	}

	/**
	 * Returns the number of record ids held in the subtree rooted at `node`.
	 */
	private static int subtreeCount(@Nonnull Node<?> node) {
		if (node instanceof final LeafNode leaf) {
			return leaf.getCount();
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		final int[] counts = internal.getCounts();
		int sum = 0;
		for (int i = 0; i < childCount; i++) {
			sum += counts[i];
		}
		return sum;
	}

	/**
	 * Returns the slot index of `recordId` within `container`.
	 */
	private static int indexInContainer(@Nonnull LeafNode container, int recordId) {
		final int count = container.getCount();
		final int[] recordIds = container.getRecordIds();
		for (int i = 0; i < count; i++) {
			if (recordIds[i] == recordId) {
				return i;
			}
		}
		throw new GenericEvitaInternalError(
			"Record id " + recordId + " not found in its routed container!",
			"Inconsistent lookup state!"
		);
	}

	/**
	 * Recursively flattens the subtree rooted at `node` into `result`, advancing the single-element position holder.
	 */
	private static void flattenInto(@Nonnull Node<?> node, @Nonnull int[] result, @Nonnull int[] positionHolder) {
		if (node instanceof final LeafNode leaf) {
			System.arraycopy(leaf.getRecordIds(), 0, result, positionHolder[0], leaf.getCount());
			positionHolder[0] += leaf.getCount();
		} else {
			final InternalNode internal = (InternalNode) node;
			final int childCount = internal.getChildCount();
			final Node<?>[] children = internal.getChildren();
			for (int i = 0; i < childCount; i++) {
				flattenInto(children[i], result, positionHolder);
			}
		}
	}

	/**
	 * Drops the memoized flattened array after a mutation.
	 */
	private void invalidateMemoizedState() {
		this.memoizedArray = null;
	}

	/**
	 * Collects the tree's leaf containers in ascending logical order (the page-emission order). `O(N / leafCapacity)`.
	 */
	@Nonnull
	private List<LeafNode> collectLeaves() {
		final List<LeafNode> leaves = new ArrayList<>();
		final Node<?> theRoot = getRoot();
		if (theRoot != null) {
			collectLeavesInto(theRoot, leaves);
		}
		return leaves;
	}

	/**
	 * Recursively appends the leaf containers under `node` to `leaves` in ascending logical order.
	 */
	private static void collectLeavesInto(@Nonnull Node<?> node, @Nonnull List<LeafNode> leaves) {
		if (node instanceof final LeafNode leaf) {
			leaves.add(leaf);
		} else {
			final InternalNode internal = (InternalNode) node;
			final int childCount = internal.getChildCount();
			final Node<?>[] children = internal.getChildren();
			for (int i = 0; i < childCount; i++) {
				collectLeavesInto(children[i], leaves);
			}
		}
	}

	/**
	 * Default {@link LeafPageHandle} backed by a single live leaf node — reads the leaf's transaction-aware state so the
	 * emitter sees read-your-writes content and stamps land on the node the commit-merge carries forward.
	 */
	private static final class LeafPageHandleImpl implements LeafPageHandle {
		@Nonnull private final LeafNode leaf;

		LeafPageHandleImpl(@Nonnull LeafNode leaf) {
			this.leaf = leaf;
		}

		@Override
		public int getPageSequence() {
			return this.leaf.getPageSequence();
		}

		@Override
		public boolean isDirty() {
			return this.leaf.isDirty();
		}

		@Override
		public void clearDirty() {
			this.leaf.clearDirty();
		}

		@Override
		public void setPageSequence(int pageSequence) {
			this.leaf.setPageSequence(pageSequence);
		}

		@Nonnull
		@Override
		public int[] recordIds() {
			return Arrays.copyOf(this.leaf.getRecordIds(), this.leaf.getCount());
		}

		@Nullable
		@Override
		public long[] headMask() {
			final long[] mask = this.leaf.getHeadMask();
			return mask == null ? null : mask.clone();
		}
	}

	/**
	 * Stateful, forward-only cursor over the tree's logical positions, created via {@link #forwardPositionCursor()}
	 * (ascending) or {@link #reversePositionCursor()} (descending). Resolving a monotonically non-decreasing sequence of
	 * emit indices costs amortized `O(1)` per emit: the cursor keeps the current leaf and its logical base position and
	 * only walks to the in-order successor (forward) / predecessor (reverse) leaf when the requested position leaves the
	 * current leaf's window - each tree edge is crossed at most once across a full traversal.
	 *
	 * Emit indices passed to {@link #recordAt(int)} must be non-decreasing and within `[0, size())`; the cursor cannot
	 * move backwards. It reads the live (transaction-aware) tree and must be consumed within a single query / transaction
	 * scope with no interleaved mutation.
	 */
	public final class PositionCursor {
		/**
		 * Descent path to the current leaf (internal nodes + the child index taken at each level).
		 */
		@Nonnull private final Cursor cursor = new Cursor();
		/**
		 * When TRUE emit index `d` resolves to the record at logical position `size() - 1 - d` (descending emit); the
		 * cursor then walks leaves right-to-left. When FALSE the emit index IS the ascending logical position.
		 */
		private final boolean descending;
		/**
		 * The leaf currently under the cursor (`null` only for an empty tree).
		 */
		@Nullable private LeafNode leaf;
		/**
		 * Ascending logical position of slot 0 of {@link #leaf}.
		 */
		private int leafBase;
		/**
		 * Record count of {@link #leaf} (the leaf spans ascending positions `[leafBase, leafBase + leafCount)`).
		 */
		private int leafCount;
		/**
		 * Highest emit index served so far, guarding the non-decreasing contract (`-1` before the first call).
		 */
		private int lastEmitIndex = -1;

		/**
		 * Positions the cursor at the leftmost (forward) or rightmost (reverse) leaf of the tree.
		 *
		 * @param descending whether the cursor emits in descending logical order
		 */
		private PositionCursor(boolean descending) {
			this.descending = descending;
			final Node<?> theRoot = getRoot();
			if (theRoot == null) {
				this.leaf = null;
				this.leafBase = 0;
				this.leafCount = 0;
			} else if (descending) {
				this.leaf = descendRightmost(theRoot);
				this.leafCount = this.leaf.getCount();
				this.leafBase = size() - this.leafCount;
			} else {
				this.leaf = descendLeftmost(theRoot);
				this.leafBase = 0;
				this.leafCount = this.leaf.getCount();
			}
		}

		/**
		 * Returns the record id at the given emit index - the ascending logical position for a forward cursor, or
		 * `size() - 1 - emitIndex` for a reverse cursor. Emit indices must be supplied in non-decreasing order.
		 *
		 * @param emitIndex the emit index in `[0, size())`, non-decreasing across calls
		 * @return the record id at the resolved logical position
		 * @throws GenericEvitaInternalError when the index is out of bounds or violates the non-decreasing contract
		 */
		public int recordAt(int emitIndex) {
			final int total = size();
			if (emitIndex < 0 || emitIndex >= total) {
				throw new GenericEvitaInternalError(
					"Position " + emitIndex + " not found!",
					"Unknown position in the array!"
				);
			}
			if (emitIndex < this.lastEmitIndex) {
				// a forward-only cursor cannot rewind - a decreasing emit index is a caller programming error
				throw new GenericEvitaInternalError(
					"Position cursor cannot move backwards (from " + this.lastEmitIndex + " to " + emitIndex + ")!",
					"Inconsistent lookup state!"
				);
			}
			this.lastEmitIndex = emitIndex;
			final int ascendingPos = this.descending ? total - 1 - emitIndex : emitIndex;
			if (this.descending) {
				// ascending position is non-increasing as emitIndex grows - walk to earlier leaves
				while (ascendingPos < this.leafBase) {
					advanceToPreviousLeaf();
				}
			} else {
				// ascending position is non-decreasing - walk to later leaves
				while (ascendingPos >= this.leafBase + this.leafCount) {
					advanceToNextLeaf();
				}
			}
			//noinspection ConstantConditions - a valid in-bounds emit index always resolves onto a non-null leaf
			return this.leaf.getRecordIds()[ascendingPos - this.leafBase];
		}

		/**
		 * Descends to the leftmost leaf of the subtree rooted at `node`, recording the path in {@link #cursor}.
		 */
		@Nonnull
		private LeafNode descendLeftmost(@Nonnull Node<?> node) {
			this.cursor.depth = 0;
			Node<?> current = node;
			while (current instanceof final InternalNode internal) {
				this.cursor.push(internal, 0);
				current = internal.getChildren()[0];
			}
			return (LeafNode) current;
		}

		/**
		 * Descends to the rightmost leaf of the subtree rooted at `node`, recording the path in {@link #cursor}.
		 */
		@Nonnull
		private LeafNode descendRightmost(@Nonnull Node<?> node) {
			this.cursor.depth = 0;
			Node<?> current = node;
			while (current instanceof final InternalNode internal) {
				final int lastChild = internal.getChildCount() - 1;
				this.cursor.push(internal, lastChild);
				current = internal.getChildren()[lastChild];
			}
			return (LeafNode) current;
		}

		/**
		 * Advances the cursor to the in-order successor leaf (the next leaf to the right) and updates the leaf window.
		 *
		 * @throws GenericEvitaInternalError when there is no next leaf (an in-bounds emit index never triggers this)
		 */
		private void advanceToNextLeaf() {
			this.leafBase += this.leafCount;
			// ascend to the first level that still has a right sibling
			int level = this.cursor.depth - 1;
			while (level >= 0 && this.cursor.idx[level] >= this.cursor.path[level].getChildCount() - 1) {
				level--;
			}
			if (level < 0) {
				throw new GenericEvitaInternalError(
					"No next leaf while walking positions!",
					"Inconsistent lookup state!"
				);
			}
			this.cursor.idx[level]++;
			this.cursor.depth = level + 1;
			this.leaf = descendLeftmostFromChild(level);
			this.leafCount = this.leaf.getCount();
		}

		/**
		 * Advances the cursor to the in-order predecessor leaf (the next leaf to the left) and updates the leaf window.
		 *
		 * @throws GenericEvitaInternalError when there is no previous leaf (an in-bounds emit index never triggers this)
		 */
		private void advanceToPreviousLeaf() {
			// ascend to the first level that still has a left sibling
			int level = this.cursor.depth - 1;
			while (level >= 0 && this.cursor.idx[level] == 0) {
				level--;
			}
			if (level < 0) {
				throw new GenericEvitaInternalError(
					"No previous leaf while walking positions!",
					"Inconsistent lookup state!"
				);
			}
			this.cursor.idx[level]--;
			this.cursor.depth = level + 1;
			this.leaf = descendRightmostFromChild(level);
			this.leafCount = this.leaf.getCount();
			this.leafBase -= this.leafCount;
		}

		/**
		 * Descends to the leftmost leaf under the child currently selected at `level`, pushing the intermediate levels.
		 */
		@Nonnull
		private LeafNode descendLeftmostFromChild(int level) {
			Node<?> node = this.cursor.path[level].getChildren()[this.cursor.idx[level]];
			while (node instanceof final InternalNode internal) {
				this.cursor.push(internal, 0);
				node = internal.getChildren()[0];
			}
			return (LeafNode) node;
		}

		/**
		 * Descends to the rightmost leaf under the child currently selected at `level`, pushing the intermediate levels.
		 */
		@Nonnull
		private LeafNode descendRightmostFromChild(int level) {
			Node<?> node = this.cursor.path[level].getChildren()[this.cursor.idx[level]];
			while (node instanceof final InternalNode internal) {
				final int lastChild = internal.getChildCount() - 1;
				this.cursor.push(internal, lastChild);
				node = internal.getChildren()[lastChild];
			}
			return (LeafNode) node;
		}
	}

	/**
	 * Mutable descent cursor capturing the internal-node path from the root to a leaf (plus the in-container offset
	 * for positional descents). Reused per operation; never escapes the structure.
	 */
	private static final class Cursor {
		/**
		 * Internal nodes along the descent path, `path[0]` being the root.
		 */
		@Nonnull final InternalNode[] path = new InternalNode[MAX_HEIGHT];
		/**
		 * The child index taken at each level of {@link #path}.
		 */
		@Nonnull final int[] idx = new int[MAX_HEIGHT];
		/**
		 * Number of internal levels captured (0 when the root is a leaf).
		 */
		int depth;
		/**
		 * In-container offset reached by a positional descent.
		 */
		int leafOffset;

		/**
		 * Pushes one descent step onto the cursor.
		 */
		void push(@Nonnull InternalNode node, int childIndex) {
			this.path[this.depth] = node;
			this.idx[this.depth] = childIndex;
			this.depth++;
		}
	}

	/**
	 * Base type for both node types. Each node is its own {@link TransactionalLayerProducer} (its diff layer is an
	 * instance of the very same concrete class): a node opens a diff copy of itself in the per-transaction layer on
	 * first write, exactly like the reference B+ trees. The recursive `N` type binds the layer / copy type to the
	 * concrete node type so the transactional accessors stay strongly typed.
	 */
	interface Node<N extends Node<N>> extends TransactionalLayerProducer<N, N>, Serializable {
	}

	/**
	 * Leaf node = a **container** holding up to {@link #DEFAULT_BLOCK_SIZE} record ids in logical order, identified by a
	 * `long` order-key.
	 *
	 * Mirrors `BPlusLeafTreeNode`: each mutable field (`orderKey`, `recordIds`, `count`) is copied-on-write into the
	 * per-transaction layer on first write through the `...ForUpdate()` accessors; read-only accessors read from the
	 * layer if present.
	 */
	static final class LeafNode implements Node<LeafNode>, Snapshotable<LeafNode.LeafNodeMemento> {
		@Serial private static final long serialVersionUID = -2510718704128926730L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves (the same class) as their transactional memory layer; were this layer to also use transactional
		 * memory it would create an infinite loop. This flag prevents that behavior - it is `false` for nodes created
		 * mid-transaction during splits (which mutate in place within the txn and are rebuilt as participating nodes
		 * on commit) and `true` for participating nodes.
		 */
		private final boolean transactionalLayer;
		/**
		 * Stable order-key identifying this container's slot in the logical order.
		 */
		private long orderKey;
		/**
		 * Record ids in logical order (only the first {@link #count} slots are valid).
		 */
		private int[] recordIds;
		/**
		 * Number of valid record ids in this container.
		 */
		private int count;
		/**
		 * Head bitmask words: bit `i` (word `i >>> 6`, bit `i & 63`) is set ⇔ the record in slot `i` of
		 * {@link #recordIds} is a chain head. Only allocated for head-aware trees (see
		 * {@link UnorderedLookupTree#headAware}) — `null` (no allocation) otherwise, so the SortIndex family pays nothing.
		 * Sized to `ceil((leafCapacity + 1) / 64)` words so the transient overflow slot at index `leafCapacity` fits.
		 * Copied-on-write into the per-transaction layer via {@link #getHeadMaskForUpdate()} like {@link #recordIds}.
		 */
		@Nullable private long[] headMask;
		/**
		 * The logical persistence page this leaf occupies, or {@link PagedLeafHandle#UNASSIGNED_PAGE_SEQUENCE} until the
		 * granular write path allocates one (a split-born or fresh leaf). Routed through the transactional layer like
		 * {@link #orderKey} (value type) and carried across the commit-merge, so an in-place-rebuilt leaf reuses its page.
		 * Only consulted on a paged tree ({@link UnorderedLookupTree#paged}); a savepoint snapshots it (unlike the
		 * flush-time-only page registry).
		 */
		private int pageSequence;
		/**
		 * The granular-storage change-detection flag: `true` when this leaf's page content changed since the last flush.
		 * Set by the content mutators ({@link #setCount}, {@link #getRecordIdsForUpdate()}, {@link #getHeadMaskForUpdate()})
		 * — NOT by {@link #setOrderKey} (order-keys are ephemeral, re-minted at load, so a re-space must not re-emit every
		 * page). Transaction-aware (routed through the layer) so a change made inside a transaction is visible at flush yet
		 * isolated from concurrent readers; the emitter clears it once the page is collected. Only consulted on a paged tree.
		 */
		private boolean dirty;

		/**
		 * Creates a new empty container sized for `leafCapacity` records with `maskWords` head-mask words.
		 *
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 * @param leafCapacity       physical record capacity (the `recordIds` array is `leafCapacity + 1` wide to host the
		 *                           transient pre-split overflow slot)
		 * @param maskWords          number of head-mask words to allocate (`0` ⇒ no mask array, non-head-aware tree)
		 */
		LeafNode(boolean transactionalLayer, int leafCapacity, int maskWords) {
			this.recordIds = new int[leafCapacity + 1];
			this.count = 0;
			this.orderKey = 0L;
			this.headMask = maskWords > 0 ? new long[maskWords] : null;
			this.pageSequence = PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE;
			this.dirty = false;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Internal constructor used by {@link #createLayer()} and {@link #createCopyWithMergedTransactionalMemory}.
		 *
		 * @param orderKey           the container order-key
		 * @param recordIds          the record id array (used directly, not copied)
		 * @param count              the number of valid record ids
		 * @param headMask           the head-mask words (`null` for a non-head-aware tree; used directly, not copied)
		 * @param pageSequence       the logical persistence page sequence
		 * @param dirty              the granular-storage change-detection flag
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		LeafNode(long orderKey, @Nonnull int[] recordIds, int count, @Nullable long[] headMask, int pageSequence, boolean dirty, boolean transactionalLayer) {
			this.orderKey = orderKey;
			this.recordIds = recordIds;
			this.count = count;
			this.headMask = headMask;
			this.pageSequence = pageSequence;
			this.dirty = dirty;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Returns the container order-key (read-only view).
		 */
		long getOrderKey() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.orderKey : layer.orderKey;
		}

		/**
		 * Sets the container order-key, decoupling into the transactional layer when present.
		 */
		void setOrderKey(long orderKey) {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.orderKey = orderKey;
			} else {
				layer.orderKey = orderKey;
			}
		}

		/**
		 * Returns the head-mask words for READ-ONLY purposes (the layer copy when present), or `null` on a
		 * non-head-aware tree. Callers must NOT mutate the returned array.
		 */
		@Nullable
		long[] getHeadMask() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.headMask : layer.headMask;
		}

		/**
		 * Returns the head-mask words for READ-ONLY purposes, failing fast when this leaf is not head-aware (a broken
		 * whole-tree invariant) rather than letting a caller dereference `null`. Callers must NOT mutate the array.
		 */
		@Nonnull
		long[] getHeadMaskOrThrow() {
			return requireHeadMask(getHeadMask());
		}

		/**
		 * Returns the head-mask words for UPDATE, decoupling an independent copy into the transactional layer on first
		 * write so the committed array stays untouched, and flagging the leaf dirty (a head mark/unmark changes the
		 * persisted page). Only called on a head-aware tree (where the mask array is non-`null`).
		 */
		@Nonnull
		long[] getHeadMaskForUpdate() {
			final long[] currentMask = requireHeadMask(this.headMask);
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.dirty = true;
				return currentMask;
			} else {
				//noinspection ArrayEquality
				if (layer.headMask == currentMask) {
					layer.headMask = currentMask.clone();
				}
				layer.dirty = true;
				return requireHeadMask(layer.headMask);
			}
		}

		/**
		 * Returns this leaf's logical persistence page sequence (the layer value when present), or
		 * {@link PagedLeafHandle#UNASSIGNED_PAGE_SEQUENCE} when none has been assigned yet.
		 */
		int getPageSequence() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.pageSequence : layer.pageSequence;
		}

		/**
		 * Stamps this leaf's logical persistence page sequence, decoupling into the transactional layer when present.
		 * Structural bookkeeping only — does NOT flag the leaf dirty.
		 */
		void setPageSequence(int pageSequence) {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.pageSequence = pageSequence;
			} else {
				layer.pageSequence = pageSequence;
			}
		}

		/**
		 * Returns whether this leaf's page content changed since the last flush (the layer value when present).
		 */
		boolean isDirty() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.dirty : layer.dirty;
		}

		/**
		 * Clears this leaf's dirty flag, decoupling into the transactional layer when present. Called by the page emitter
		 * once the leaf's page has been collected for the current flush.
		 */
		void clearDirty() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.dirty = false;
			} else {
				layer.dirty = false;
			}
		}

		/**
		 * Resets this leaf's page bookkeeping — un-assigns the page sequence and clears the dirty flag — as part of a
		 * `PAGED->SINGLE` collapse (see {@link #forgetPageStream()}). Writes through an EXISTING transactional layer when
		 * one is present, but NEVER creates one — unlike {@link #setPageSequence(int)} / {@link #clearDirty()}, which route
		 * through the create-on-write path. This runs at flush time, INSIDE the commit, after
		 * {@link io.evitadb.core.transaction.memory.TransactionalLayerMaintainer#commit} has forbidden new layer creation;
		 * because `forgetPageStream` walks EVERY leaf (a collapse survivor can be a leaf this transaction never touched, so
		 * it carries no layer), a create-on-write reset would trip the "already committed" premise on that untouched leaf.
		 * A leaf with no layer has no pending diff, so resetting its committed baseline field in place is what the merge
		 * carries forward anyway — mirroring the create-free read path ({@link #getPageSequence()} / {@link #isDirty()}).
		 * The reset MUST fire even for an untouched leaf: its baseline still holds an assigned page sequence whose page was
		 * already emitted for removal and forgotten from the registry, so leaving it assigned would thread a dangling page
		 * into a later `SINGLE->PAGED` regrow.
		 */
		void forgetPage() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				this.pageSequence = PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE;
				this.dirty = false;
			} else {
				layer.pageSequence = PagedLeafHandle.UNASSIGNED_PAGE_SEQUENCE;
				layer.dirty = false;
			}
		}

		/**
		 * Returns the number of valid record ids (read-only view).
		 */
		int getCount() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.count : layer.count;
		}

		/**
		 * Sets the number of valid record ids, decoupling into the transactional layer when present.
		 */
		void setCount(int count) {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.count = count;
				this.dirty = true;
			} else {
				layer.count = count;
				layer.dirty = true;
			}
		}

		/**
		 * Returns the record id array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		int[] getRecordIds() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.recordIds : layer.recordIds;
		}

		/**
		 * Returns the record id array for UPDATE, decoupling an independent copy into the transactional layer on first
		 * write so the committed array stays untouched.
		 */
		@Nonnull
		int[] getRecordIdsForUpdate() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.dirty = true;
				return this.recordIds;
			} else {
				//noinspection ArrayEquality
				if (layer.recordIds == this.recordIds) {
					layer.recordIds = new int[this.recordIds.length];
					System.arraycopy(this.recordIds, 0, layer.recordIds, 0, this.recordIds.length);
				}
				layer.dirty = true;
				return layer.recordIds;
			}
		}

		@Override
		public LeafNode createLayer() {
			return new LeafNode(this.orderKey, this.recordIds, this.count, this.headMask, this.pageSequence, this.dirty, false);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. The mutable
		 * fields are the order-key, the record-id array and the count; the record-id array is cloned (the ints are
		 * value types) so that a later mutation, or a repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this container's order-key, record ids and count
		 */
		@Nonnull
		@Override
		public LeafNodeMemento snapshot() {
			return new LeafNodeMemento(
				this.orderKey, this.recordIds.clone(), this.count,
				this.headMask == null ? null : this.headMask.clone(), this.pageSequence, this.dirty);
		}

		/**
		 * Restores the state captured by {@link #snapshot}. A fresh clone of the memento's record-id array is installed
		 * so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull LeafNodeMemento memento) {
			this.orderKey = memento.orderKey();
			this.recordIds = memento.recordIds().clone();
			this.count = memento.count();
			this.headMask = memento.headMask() == null ? null : memento.headMask().clone();
			this.pageSequence = memento.pageSequence();
			this.dirty = memento.dirty();
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public LeafNode createCopyWithMergedTransactionalMemory(
			@Nullable LeafNode leafLayer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final long theOrderKey;
			final int[] theRecordIds;
			final int theCount;
			final long[] theHeadMask;
			final int thePageSequence;
			final boolean theDirty;
			if (leafLayer == null) {
				theOrderKey = this.orderKey;
				theRecordIds = this.recordIds;
				theCount = this.count;
				theHeadMask = this.headMask;
				thePageSequence = this.pageSequence;
				theDirty = this.dirty;
			} else {
				theOrderKey = leafLayer.orderKey;
				theRecordIds = leafLayer.recordIds;
				theCount = leafLayer.count;
				theHeadMask = leafLayer.headMask;
				thePageSequence = leafLayer.pageSequence;
				theDirty = leafLayer.dirty;
			}
			// primitive int record ids / head-mask words never carry their own transactional layer, nothing to merge
			if (leafLayer != null) {
				return new LeafNode(theOrderKey, theRecordIds, theCount, theHeadMask, thePageSequence, theDirty, true);
			} else if (!this.transactionalLayer) {
				// nodes created during splits are built with transactionalLayer=false so they do not allocate STM
				// layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
				return new LeafNode(theOrderKey, theRecordIds, theCount, theHeadMask, thePageSequence, theDirty, true);
			} else {
				return this;
			}
		}

		@Override
		public String toString() {
			final int[] theRecordIds = getRecordIds();
			final int theCount = getCount();
			final StringBuilder sb = new StringBuilder(8 + (theCount << 2));
			sb.append('[');
			for (int i = 0; i < theCount; i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(theRecordIds[i]);
			}
			sb.append(']');
			return sb.toString();
		}

		/**
		 * Immutable savepoint memento of a container's copy-on-write state. The record-id array and (when present) the
		 * head-mask words are private clones owned by the memento (see {@link #snapshot}); the order-key, count, page
		 * sequence and dirty flag are primitive value types.
		 *
		 * @param orderKey     the container order-key
		 * @param recordIds    clone of the record-id array
		 * @param count        the number of valid record ids
		 * @param headMask     clone of the head-mask words, or `null` when not head-aware
		 * @param pageSequence the logical persistence page sequence
		 * @param dirty        the granular-storage change-detection flag
		 */
		record LeafNodeMemento(
			long orderKey,
			@Nonnull int[] recordIds,
			int count,
			@Nullable long[] headMask,
			int pageSequence,
			boolean dirty
		) {
		}
	}

	/**
	 * Internal node routing by `long` order-key separators and carrying the record-id count of each child subtree.
	 * Holds up to {@link #DEFAULT_BLOCK_SIZE} children and `childCount - 1` separators (`separators[i]` is the minimum
	 * order-key found in `children[i + 1]`).
	 *
	 * Mirrors `BPlusInternalTreeNode`: each mutable field (`children`, `separators`, `counts`, `childCount`) is
	 * copied-on-write into the per-transaction layer on first write through the `...ForUpdate()` accessors; read-only
	 * accessors read from the layer if present.
	 */
	static final class InternalNode implements Node<InternalNode>, Snapshotable<InternalNode.InternalNodeMemento> {
		@Serial private static final long serialVersionUID = 1791772842933035170L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers (see the matching field
		 * on {@link LeafNode}).
		 */
		private final boolean transactionalLayer;
		/**
		 * Child nodes (only the first {@link #childCount} slots are valid).
		 */
		private Node<?>[] children;
		/**
		 * Separator order-keys; `separators[i]` is the minimum order-key in `children[i + 1]`'s subtree.
		 */
		private long[] separators;
		/**
		 * Record-id count of each child subtree, aligned with {@link #children}.
		 */
		private int[] counts;
		/**
		 * Head count of each child subtree, aligned with {@link #children}. Non-`null` only for head-aware trees (see
		 * {@link UnorderedLookupTree#headAware}); `null` (never allocated) otherwise. When non-`null` it is maintained in
		 * lock-step with {@link #counts} by every structural operation.
		 */
		@Nullable private int[] headCounts;
		/**
		 * Number of valid children.
		 */
		private int childCount;

		/**
		 * Creates a new empty internal node.
		 *
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 * @param allocateHeadCounts whether to allocate the per-child head-count array (head-aware trees only)
		 */
		@SuppressWarnings("CheckForOutOfMemoryOnLargeArrayAllocation")
		InternalNode(boolean transactionalLayer, boolean allocateHeadCounts) {
			this.children = new Node<?>[DEFAULT_BLOCK_SIZE + 1];
			this.separators = new long[DEFAULT_BLOCK_SIZE];
			this.counts = new int[DEFAULT_BLOCK_SIZE + 1];
			this.headCounts = allocateHeadCounts ? new int[DEFAULT_BLOCK_SIZE + 1] : null;
			this.childCount = 0;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Internal constructor used by {@link #createLayer()} and {@link #createCopyWithMergedTransactionalMemory}.
		 *
		 * @param children           the children array (used directly, not copied)
		 * @param separators         the separators array (used directly, not copied)
		 * @param counts             the per-child subtree counts array (used directly, not copied)
		 * @param headCounts         the per-child head-count array (used directly, not copied), or `null` when not head-aware
		 * @param childCount         the number of valid children
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		InternalNode(@Nonnull Node<?>[] children, @Nonnull long[] separators, @Nonnull int[] counts, @Nullable int[] headCounts, int childCount, boolean transactionalLayer) {
			this.children = children;
			this.separators = separators;
			this.counts = counts;
			this.headCounts = headCounts;
			this.childCount = childCount;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Returns the number of valid children (read-only view).
		 */
		int getChildCount() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.childCount : layer.childCount;
		}

		/**
		 * Sets the number of valid children, decoupling into the transactional layer when present.
		 */
		void setChildCount(int childCount) {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.childCount = childCount;
			} else {
				layer.childCount = childCount;
			}
		}

		/**
		 * Returns the children array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		Node<?>[] getChildren() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.children : layer.children;
		}

		/**
		 * Returns the children array for UPDATE, decoupling an independent copy into the transactional layer on first
		 * write.
		 */
		@Nonnull
		Node<?>[] getChildrenForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.children;
			} else {
				//noinspection ArrayEquality
				if (layer.children == this.children) {
					layer.children = new Node<?>[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.children.length);
				}
				return layer.children;
			}
		}

		/**
		 * Returns the separators array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		long[] getSeparators() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.separators : layer.separators;
		}

		/**
		 * Returns the separators array for UPDATE, decoupling an independent copy into the transactional layer on
		 * first write.
		 */
		@Nonnull
		long[] getSeparatorsForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.separators;
			} else {
				//noinspection ArrayEquality
				if (layer.separators == this.separators) {
					layer.separators = new long[this.separators.length];
					System.arraycopy(this.separators, 0, layer.separators, 0, this.separators.length);
				}
				return layer.separators;
			}
		}

		/**
		 * Returns the per-child subtree counts array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		int[] getCounts() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.counts : layer.counts;
		}

		/**
		 * Returns the per-child subtree counts array for UPDATE, decoupling an independent copy into the transactional
		 * layer on first write.
		 */
		@Nonnull
		int[] getCountsForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.counts;
			} else {
				//noinspection ArrayEquality
				if (layer.counts == this.counts) {
					layer.counts = new int[this.counts.length];
					System.arraycopy(this.counts, 0, layer.counts, 0, this.counts.length);
				}
				return layer.counts;
			}
		}

		/**
		 * Returns the per-child head-count array for READ-ONLY purposes (the layer copy when present), or `null` for a
		 * non-head-aware node.
		 */
		@Nullable
		int[] getHeadCounts() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.headCounts : layer.headCounts;
		}

		/**
		 * Returns the per-child head-count array for UPDATE, decoupling an independent copy into the transactional layer
		 * on first write. Returns `null` for a non-head-aware node (that path is gated out by the caller).
		 */
		@Nullable
		int[] getHeadCountsForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.headCounts;
			} else {
				//noinspection ArrayEquality
				if (layer.headCounts != null && layer.headCounts == this.headCounts) {
					layer.headCounts = new int[this.headCounts.length];
					System.arraycopy(this.headCounts, 0, layer.headCounts, 0, this.headCounts.length);
				}
				return layer.headCounts;
			}
		}

		/**
		 * Returns the per-child head-count array for READ-ONLY purposes, failing fast when this node is not head-aware
		 * (a broken whole-tree invariant) rather than letting a caller dereference `null`.
		 */
		@Nonnull
		int[] getHeadCountsOrThrow() {
			return requireHeadCounts(getHeadCounts());
		}

		/**
		 * Returns the per-child head-count array for UPDATE, failing fast when this node is not head-aware (a broken
		 * whole-tree invariant) rather than letting a caller dereference `null`.
		 */
		@Nonnull
		int[] getHeadCountsForUpdateOrThrow() {
			return requireHeadCounts(getHeadCountsForUpdate());
		}

		@Override
		public InternalNode createLayer() {
			return new InternalNode(this.children, this.separators, this.counts, this.headCounts, this.childCount, false);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. The mutable
		 * fields are the children, separators and counts arrays and the child count; all three arrays are cloned
		 * (shallow for children — the child nodes own their own transactional layers and are snapshotted independently;
		 * the separators and counts are value types) so that a later mutation, or a repeated {@link #restore}, cannot
		 * corrupt the memento.
		 *
		 * @return an independent snapshot of this internal node's array structure
		 */
		@Nonnull
		@Override
		public InternalNodeMemento snapshot() {
			return new InternalNodeMemento(
				this.children.clone(), this.separators.clone(), this.counts.clone(),
				this.headCounts == null ? null : this.headCounts.clone(), this.childCount);
		}

		/**
		 * Restores the array structure captured by {@link #snapshot}. Fresh clones of the memento's arrays are
		 * installed so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull InternalNodeMemento memento) {
			this.children = memento.children().clone();
			this.separators = memento.separators().clone();
			this.counts = memento.counts().clone();
			this.headCounts = memento.headCounts() == null ? null : memento.headCounts().clone();
			this.childCount = memento.childCount();
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public InternalNode createCopyWithMergedTransactionalMemory(
			@Nullable InternalNode internalLayer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final Node<?>[] theChildren;
			final long[] theSeparators;
			final int[] theCounts;
			final int[] theHeadCounts;
			final int theChildCount;
			if (internalLayer == null) {
				theChildren = this.children;
				theSeparators = this.separators;
				theCounts = this.counts;
				theHeadCounts = this.headCounts;
				theChildCount = this.childCount;
			} else {
				theChildren = internalLayer.children;
				theSeparators = internalLayer.separators;
				theCounts = internalLayer.counts;
				theHeadCounts = internalLayer.headCounts;
				theChildCount = internalLayer.childCount;
			}

			// merge the committed copies of every child; only allocate a fresh children array when at least one
			// child actually changed under the transaction
			Node<?>[] newChildren = null;
			for (int i = 0; i < theChildCount; i++) {
				final Node<?> child = transactionalLayer.getStateCopyWithCommittedChanges(theChildren[i]);
				if (newChildren == null && child != theChildren[i]) {
					newChildren = new Node<?>[theChildren.length];
					System.arraycopy(theChildren, 0, newChildren, 0, i);
				}
				if (newChildren != null) {
					newChildren[i] = child;
				}
			}

			if (newChildren != null) {
				return new InternalNode(newChildren, theSeparators, theCounts, theHeadCounts, theChildCount, true);
			} else if (internalLayer != null) {
				return new InternalNode(theChildren, theSeparators, theCounts, theHeadCounts, theChildCount, true);
			} else if (!this.transactionalLayer) {
				// nodes created during splits are built with transactionalLayer=false so they do not allocate STM
				// layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
				return new InternalNode(theChildren, theSeparators, theCounts, theHeadCounts, theChildCount, true);
			} else {
				return this;
			}
		}

		@Override
		public String toString() {
			return "Internal(children=" + getChildCount() + ')';
		}

		/**
		 * Immutable savepoint memento of an internal node's copy-on-write array structure. The arrays are private
		 * clones owned by the memento (see {@link #snapshot}); the child-node references they hold are shared by design
		 * and the separators / counts / head-counts are value types.
		 *
		 * @param children   clone of the child-pointer array
		 * @param separators clone of the separator-key array
		 * @param counts     clone of the per-child subtree-count array
		 * @param headCounts clone of the per-child head-count array, or `null` when not head-aware
		 * @param childCount the number of valid children
		 */
		record InternalNodeMemento(
			@Nonnull Node<?>[] children,
			@Nonnull long[] separators,
			@Nonnull int[] counts,
			@Nullable int[] headCounts,
			int childCount
		) {
		}
	}

}
