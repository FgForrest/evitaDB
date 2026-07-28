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

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.iterator.ConstantIntIterator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;

/**
 * This array keeps unique (distinct) integer values in an unordered fashion, providing fast positional access,
 * fast lookup by value, and full transactional isolation.
 *
 * It is a **composite** of two coordinated, count-augmented B+ trees, coupled by a stable `long` order-key:
 *
 * - the **value index** ({@link TransactionalIntToLongBPlusTree}) maps each record id to the order-key of the
 *   container that holds it (no boxing), and
 * - the **position tree** ({@link UnorderedLookupTree}) is an order-statistic tree whose leaves are containers of
 *   record ids in logical order; it answers `getRecordAt(position)` (count descent) and `findPosition` (order-key
 *   descent → prefix count).
 *
 * Both trees mutate **in place** when no transaction is open (the warm-up / committed delegate) and **path-copy**
 * inside a transaction, so multiple readers see the original data while a writer accumulates an isolated diff that
 * materialises atomically on commit — each transaction is bound to a single thread. If no transaction is open the
 * class is not thread safe for multiple writers.
 *
 * The previous dual-`int[]` {@link UnorderedLookup} delegate is retained only as the immutable flattened snapshot
 * DTO consumed downstream; this façade no longer renumbers a suffix or reallocates `O(N)` arrays per write, so a
 * single insert / remove is `O(log N)` with no humongous allocation, and a commit of `e` edits is `O(e·log N)`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public class TransactionalUnorderedIntArray
	implements TransactionalLayerProducer<Void, TransactionalUnorderedIntArray>,
	OrderKeyConsumer,
	Serializable {
	@Serial private static final long serialVersionUID = 4753581686040233219L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Order-statistic position tree (order-key → container of record ids in logical order).
	 */
	@Nonnull private final UnorderedLookupTree positionTree;
	/**
	 * Value index mapping each record id to the order-key of its container (no boxing).
	 */
	@Nonnull private final TransactionalIntToLongBPlusTree valueIndex;

	/**
	 * Creates an empty transactional unordered int array.
	 */
	public TransactionalUnorderedIntArray() {
		this(false);
	}

	/**
	 * Creates an empty transactional unordered int array, optionally head-aware. A head-aware array additionally
	 * tracks per-record chain-head marks (see {@link UnorderedLookupTree}) so {@link #findHeadCovering} can locate the
	 * head of the run covering a logical position in `O(log N)`; used by {@link io.evitadb.index.attribute.ChainIndex}.
	 * A non-head-aware array (the default, e.g. the SortIndex family) allocates no head structures.
	 *
	 * @param headAware whether the array tracks chain-head marks
	 */
	public TransactionalUnorderedIntArray(boolean headAware) {
		this.positionTree = createPositionTree(headAware);
		this.valueIndex = new TransactionalIntToLongBPlusTree();
	}

	/**
	 * Creates a transactional unordered int array wrapping the given delegate (record ids in logical order).
	 *
	 * @param delegate the initial unordered array of record ids
	 */
	public TransactionalUnorderedIntArray(@Nonnull int[] delegate) {
		this.positionTree = createPositionTree(false);
		this.valueIndex = new TransactionalIntToLongBPlusTree();
		bulkLoadInBase(delegate, null);
	}

	/**
	 * Creates a head-aware transactional unordered int array from the given delegate (record ids in logical order),
	 * marking the records at `sortedHeadPositions` (ascending logical positions) as chain heads during the bulk load.
	 * Used by {@link io.evitadb.index.attribute.ChainIndex} to rebuild a chain index from its persisted chains in a
	 * single `O(N)` pass, with the head marks landing in the committed base (never a discardable transaction layer).
	 *
	 * @param delegate            the initial unordered array of record ids
	 * @param sortedHeadPositions ascending logical positions of the head records (may be empty)
	 */
	public TransactionalUnorderedIntArray(@Nonnull int[] delegate, @Nonnull int[] sortedHeadPositions) {
		this.positionTree = createPositionTree(true);
		this.valueIndex = new TransactionalIntToLongBPlusTree();
		bulkLoadInBase(delegate, sortedHeadPositions);
	}

	/**
	 * Boundary-stable reload: rebuilds a head-aware, paged array from its persisted leaf pages, building exactly one
	 * tree leaf per page (the persisted boundaries preserved verbatim, no repack) with each leaf stamped by its page
	 * sequence and left non-dirty so a first post-load flush emits nothing. Used by
	 * {@link io.evitadb.index.attribute.ChainIndex} PAGED reload. The pages MUST be supplied in ascending logical
	 * order — the concatenation of their record ids IS the array. The build runs OUTSIDE the transaction (like
	 * {@link #bulkLoadInBase}) so the data lands in the committed BASE even when the index is loaded mid-transaction.
	 *
	 * @param pages the persisted leaf pages in ascending logical order
	 */
	public TransactionalUnorderedIntArray(@Nonnull List<UnorderedLookupTree.LeafPageInput> pages) {
		this.positionTree = createPositionTree(true);
		this.valueIndex = new TransactionalIntToLongBPlusTree();
		assembleFromPagesInBase(pages);
	}

	/**
	 * Assembles the position tree from the persisted leaf pages so the data establishes the committed BASE state.
	 * Mirrors {@link #bulkLoadInBase}'s transaction-detach reasoning: were the build to run inside a transaction, the
	 * data (head marks and page bookkeeping included) would land in per-transaction layers, and a later map.remove of
	 * this value would discard those layers and silently empty the array. The order-key consumer ({@code this})
	 * populates the value index.
	 *
	 * @param pages the persisted leaf pages in ascending logical order
	 */
	private void assembleFromPagesInBase(@Nonnull List<UnorderedLookupTree.LeafPageInput> pages) {
		Transaction.getTransaction().ifPresentOrElse(
			transaction -> {
				// detach so the assembled data (head marks + page bookkeeping) lands in the committed BASE, not a
				// discardable per-transaction layer (see the method javadoc)
				transaction.unbindTransactionFromThread();
				try {
					this.positionTree.assembleFromLeafPages(pages, this);
				} finally {
					transaction.bindTransactionToThread();
				}
			},
			() -> this.positionTree.assembleFromLeafPages(pages, this)
		);
	}

	/**
	 * Creates the backing position tree. The head-aware tree (used by {@link io.evitadb.index.attribute.ChainIndex}) is
	 * also PAGED: its leaves grow to {@link UnorderedLookupTree#PAGE_RECORDS} (1024) records so one leaf maps to one
	 * persisted page, while the routing spine keeps the {@link UnorderedLookupTree#DEFAULT_BLOCK_SIZE} fan-out. The
	 * non-head-aware tree (the SortIndex family) keeps the legacy default: {@code DEFAULT_BLOCK_SIZE}-wide, non-paged
	 * leaves and no head structures — byte-for-byte unaffected.
	 */
	@Nonnull
	private static UnorderedLookupTree createPositionTree(boolean headAware) {
		return headAware
			? new UnorderedLookupTree(
				UnorderedLookupTree.DEFAULT_BLOCK_SIZE, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP,
				true, UnorderedLookupTree.PAGE_RECORDS, true)
			: new UnorderedLookupTree();
	}

	/**
	 * Bulk-loads the position tree so the data establishes the committed BASE state. If we happen to be inside a
	 * transaction (e.g. ChainIndex creating a new chain mid-transaction), the build must run OUTSIDE the transaction's
	 * awareness - otherwise the data (head marks included) would land in per-transaction layers of the two trees, and a
	 * later map.remove of this value (TransactionalMap delete-cleanup releases a removed producer's layer) would discard
	 * those layers and silently empty the array. Detaching the transaction for the duration of the build makes the data
	 * land in the base, exactly as the former plain UnorderedLookup delegate did.
	 *
	 * @param delegate            record ids in logical order
	 * @param sortedHeadPositions ascending head positions to mark (head-aware build), or `null` for a plain build
	 */
	private void bulkLoadInBase(@Nonnull int[] delegate, @Nullable int[] sortedHeadPositions) {
		Transaction.getTransaction().ifPresentOrElse(
			transaction -> {
				// detach so the loaded data (head marks included) lands in the committed BASE, not a discardable
				// per-transaction layer (see the method javadoc)
				transaction.unbindTransactionFromThread();
				try {
					loadDelegate(delegate, sortedHeadPositions);
				} finally {
					transaction.bindTransactionToThread();
				}
			},
			() -> loadDelegate(delegate, sortedHeadPositions)
		);
	}

	/**
	 * Bulk-loads the position tree (`O(N)`); the order-key consumer ({@code this}) populates the value index. When
	 * `sortedHeadPositions` is non-null the head-aware build additionally sets the chain-head marks.
	 */
	private void loadDelegate(@Nonnull int[] delegate, @Nullable int[] sortedHeadPositions) {
		if (sortedHeadPositions == null) {
			this.positionTree.bulkLoad(delegate, this);
		} else {
			this.positionTree.bulkLoadWithHeads(delegate, sortedHeadPositions, this);
		}
	}

	/**
	 * Internal constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap the already-merged
	 * (committed) child trees.
	 */
	private TransactionalUnorderedIntArray(
		@Nonnull UnorderedLookupTree positionTree,
		@Nonnull TransactionalIntToLongBPlusTree valueIndex
	) {
		this.positionTree = positionTree;
		this.valueIndex = valueIndex;
	}

	/**
	 * Order-key consumer hook used by the position tree to keep the value index coherent (INV-COUPLE): records each
	 * `recordId → orderKey` assignment (insert overwrites an existing mapping).
	 */
	@Override
	public void accept(int recordId, long orderKey) {
		this.valueIndex.insert(recordId, orderKey);
	}

	/**
	 * Returns the array of positions corresponding to the ascending record id array {@link #getRecordIds()}; i.e.
	 * `getArray()[positions[i]] == getRecordIds()[i]`.
	 */
	public int[] getPositions() {
		final int[] permutation = getArray();
		final IntIntMap recordToPosition = new IntIntHashMap(permutation.length);
		for (int position = 0; position < permutation.length; position++) {
			recordToPosition.put(permutation[position], position);
		}
		final int[] sortedRecordIds = permutation.clone();
		Arrays.sort(sortedRecordIds);
		final int[] positions = new int[sortedRecordIds.length];
		for (int i = 0; i < sortedRecordIds.length; i++) {
			positions[i] = recordToPosition.get(sortedRecordIds[i]);
		}
		return positions;
	}

	/**
	 * Returns the record ids sorted by their id (ascending) as a bitmap.
	 */
	public Bitmap getRecordIds() {
		final int[] sortedRecordIds = getArray().clone();
		Arrays.sort(sortedRecordIds);
		return new BaseBitmap(sortedRecordIds);
	}

	/**
	 * Materializes, in a SINGLE position-tree walk plus a SINGLE `O(N log N)` sort, all three read-projection artifacts
	 * a {@link io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider} needs: the record ids in
	 * logical (value) order, the record positions aligned with the ascending-id order, and the ascending-id record-id
	 * bitmap. This replaces the former {@link #getArray()} + {@link #getPositions()} + {@link #getRecordIds()} trio,
	 * which walked the position tree three times over and additionally built an {@link IntIntHashMap} plus sorted twice.
	 *
	 * The single sort packs each `(recordId, position)` pair into one `long` — the record id in the high 32 bits, the
	 * position (always `0..N-1`, so non-negative) in the low 32 bits — and sorts the `long[]` once; the high half then
	 * reads off as the ascending record ids (signed order, matching {@link Arrays#sort(int[])}) and the low half as the
	 * position of each within the logical order.
	 *
	 * @return the three materialized artifacts (see {@link MaterializedOrder})
	 */
	@Nonnull
	public MaterializedOrder materialize() {
		// one tree walk: record ids in logical (value) order - this IS the sortedRecordIds artifact
		final int[] sortedRecordIds = this.positionTree.getArray();
		final int recordCount = sortedRecordIds.length;
		// pack (recordId, position) into one long each so a single sort yields BOTH the ascending id order and the
		// position aligned to it - record id in the high 32 bits (signed order preserved), position in the low 32 bits
		final long[] packed = new long[recordCount];
		for (int position = 0; position < recordCount; position++) {
			packed[position] = (((long) sortedRecordIds[position]) << 32) | (position & 0xFFFFFFFFL);
		}
		Arrays.sort(packed);
		final int[] ascendingRecordIds = new int[recordCount];
		final int[] recordPositions = new int[recordCount];
		for (int i = 0; i < recordCount; i++) {
			ascendingRecordIds[i] = (int) (packed[i] >> 32);
			recordPositions[i] = (int) packed[i];
		}
		return new MaterializedOrder(sortedRecordIds, recordPositions, new BaseBitmap(ascendingRecordIds));
	}

	/**
	 * The three read-projection artifacts produced together by {@link #materialize()} in a single walk + sort. All
	 * three are freshly allocated and owned by the caller; {@link #allRecords} is the ascending-id record-id bitmap,
	 * direction-independent and safe to share across both sort directions.
	 *
	 * @param sortedRecordIds record ids in logical (value) order — identical to {@link #getArray()}
	 * @param recordPositions position of each ascending-id record within {@link #sortedRecordIds} — identical to
	 *                        {@link #getPositions()}, i.e. `sortedRecordIds[recordPositions[i]] == allRecords[i]`
	 * @param allRecords      all record ids in natural (ascending) id order — identical to {@link #getRecordIds()}
	 */
	public record MaterializedOrder(
		@Nonnull int[] sortedRecordIds,
		@Nonnull int[] recordPositions,
		@Nonnull Bitmap allRecords
	) {
	}

	/**
	 * Method returns record id on specified index of the array.
	 */
	public int get(int index) {
		return this.positionTree.getRecordAt(index);
	}

	/**
	 * Binary-searches the ascending record ids on indexes `[fromIndex, toIndex)` and returns the index at which
	 * `recordId` belongs — the first index holding a greater or equal record id, or `toIndex` when every id in the
	 * range is smaller.
	 *
	 * Prefer this over a caller-side binary search built from {@link #get(int)}: the whole search shares a single
	 * resolved leaf, so probes that converge into one leaf cost an array read rather than a fresh tree descent. See
	 * {@link UnorderedLookupTree#findInsertionPositionInRange(int, int, int)} for why the search has to live inside
	 * the tree to get that.
	 *
	 * @param fromIndex first index of the searched range, inclusive
	 * @param toIndex   last index of the searched range, exclusive
	 * @param recordId  the record id whose insertion index is sought
	 * @return the insertion index within `[fromIndex, toIndex]`
	 */
	public int findInsertionPositionInRange(int fromIndex, int toIndex, int recordId) {
		return this.positionTree.findInsertionPositionInRange(fromIndex, toIndex, recordId);
	}

	/**
	 * Method returns last record in the array.
	 *
	 * @return record id
	 * @throws ArrayIndexOutOfBoundsException when array is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		return this.positionTree.getLastRecordId();
	}

	/**
	 * Method returns the underlying array of record ids (logical order).
	 */
	public int[] getArray() {
		return this.positionTree.getArray();
	}

	/**
	 * Method returns subset of underlying array of record ids.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 */
	public int[] getSubArray(int startIndex, int endIndex) {
		return Arrays.copyOfRange(this.positionTree.getArray(), startIndex, endIndex);
	}

	/**
	 * Creates a forward cursor over the logical order (ascending positions), emitting the record id at each position in
	 * amortized `O(1)` for a monotonically non-decreasing run of positions - the allocation-free alternative to calling
	 * {@link #get(int)} once per position (`O(log N)` each) when whole ranges are scanned in order. The cursor reads the
	 * same live position tree as {@link #get(int)} / {@link #indexOf(int)}, so it is transaction-consistent by the same
	 * argument and must be consumed within a single query / transaction scope with no interleaved mutation.
	 *
	 * @return a forward {@link UnorderedLookupTree.PositionCursor} over the logical order
	 */
	@Nonnull
	public UnorderedLookupTree.PositionCursor forwardPositionCursor() {
		return this.positionTree.forwardPositionCursor();
	}

	/**
	 * Creates a reverse cursor over the logical order: emit index `d` resolves to the record at logical position
	 * `size() - 1 - d`, so a forward scan of emit indices walks the array back-to-front without materializing a reversed
	 * copy. Same amortized `O(1)` per emit and the same single-scope / transaction-consistency contract as
	 * {@link #forwardPositionCursor()}.
	 *
	 * @return a reverse {@link UnorderedLookupTree.PositionCursor} over the logical order
	 */
	@Nonnull
	public UnorderedLookupTree.PositionCursor reversePositionCursor() {
		return this.positionTree.reversePositionCursor();
	}

	/**
	 * Method adds new record to the array, just after the record specified as `previousRecordId`
	 * ({@link Integer#MIN_VALUE} adds it to the head).
	 */
	public void add(int previousRecordId, int recordId) {
		// order-keys minted by the position tree are always non-negative (the first container is 0 and every mint is
		// additive), so Long.MIN_VALUE is a collision-proof "absent" sentinel that lets us skip the OptionalLong
		// allocation `search(int)` would incur - the same trick `indexOf` relies on. The guard stays (it protects
		// against index corruption), it is just no longer a second full descent plus a boxed result.
		Assert.isTrue(
			this.valueIndex.searchOrDefault(recordId, Long.MIN_VALUE) == Long.MIN_VALUE,
			() -> "Record with id " + recordId + " is already part of the array!"
		);
		if (previousRecordId == Integer.MIN_VALUE) {
			this.positionTree.insertAtPosition(0, recordId, this);
		} else {
			final long previousOrderKey = this.valueIndex.searchOrDefault(previousRecordId, Long.MIN_VALUE);
			Assert.isTrue(
				previousOrderKey != Long.MIN_VALUE,
				() -> "Record with id " + previousRecordId + " is not present in the array,"
					+ " cannot add record " + recordId + " after it!"
			);
			this.positionTree.insertAfter(previousOrderKey, previousRecordId, recordId, this);
		}
	}

	/**
	 * Method adds new record to the array on specified index.
	 */
	public void addOnIndex(int index, int recordId) {
		Assert.isTrue(
			this.valueIndex.searchOrDefault(recordId, Long.MIN_VALUE) == Long.MIN_VALUE,
			() -> "Record with id " + recordId + " is already part of the array!"
		);
		this.positionTree.insertAtPosition(index, recordId, this);
	}

	/**
	 * Method adds multiple record ids to the array (each just after the previous one).
	 */
	public void addAll(int previousRecordId, int... recordIds) {
		int currentPreviousRecordId = previousRecordId;
		for (final int recordId : recordIds) {
			add(currentPreviousRecordId, recordId);
			currentPreviousRecordId = recordId;
		}
	}

	/**
	 * Method adds multiple record ids to the end of the array.
	 *
	 * @param recordIds record ids to add
	 */
	public void appendAll(int... recordIds) {
		for (final int recordId : recordIds) {
			Assert.isTrue(
				this.valueIndex.searchOrDefault(recordId, Long.MIN_VALUE) == Long.MIN_VALUE,
				() -> "Record with id " + recordId + " is already part of the array!"
			);
			this.positionTree.insertAtPosition(this.positionTree.size(), recordId, this);
		}
	}

	/**
	 * Method removes record id from the array.
	 */
	public void remove(int recordId) {
		final long orderKey = this.valueIndex.search(recordId).orElseThrow(
			() -> new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array."
			)
		);
		this.positionTree.removeByOrderKey(orderKey, recordId, this);
		this.valueIndex.delete(recordId);
	}

	/**
	 * Method removes multiple record ids from the array.
	 */
	public void removeAll(int... recordIds) {
		for (final int recordId : recordIds) {
			remove(recordId);
		}
	}

	/**
	 * Method removes all records between two indexes.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 * @return removed records
	 */
	public int[] removeRange(int startIndex, int endIndex) {
		final int[] removed = Arrays.copyOfRange(this.positionTree.getArray(), startIndex, endIndex);
		for (final int recordId : removed) {
			remove(recordId);
		}
		return removed;
	}

	/**
	 * Returns length of the array.
	 */
	public int getLength() {
		return this.positionTree.size();
	}

	/**
	 * Returns true if array contain no record ids.
	 */
	public boolean isEmpty() {
		return this.positionTree.isEmpty();
	}

	/**
	 * Returns index (position) of the record id in the array.
	 *
	 * @return {@link Integer#MIN_VALUE} when the record is not found, the position otherwise
	 */
	public int indexOf(int recordId) {
		// order-keys minted by the position tree are always non-negative (the first container is 0 and every mint is
		// additive), so Long.MIN_VALUE is a collision-proof "absent" sentinel that lets us skip the OptionalLong
		// allocation search(int) would incur on this hot sort-position lookup path
		final long orderKey = this.valueIndex.searchOrDefault(recordId, Long.MIN_VALUE);
		return orderKey == Long.MIN_VALUE
			? Integer.MIN_VALUE
			: this.positionTree.findPositionByOrderKey(orderKey, recordId);
	}

	/**
	 * Returns true if record id is part of the array.
	 */
	public boolean contains(int recordId) {
		return this.valueIndex.search(recordId).isPresent();
	}

	/**
	 * Marks `recordId` as a chain head (idempotent - a no-op when it is already a head). Requires a head-aware array.
	 */
	public void markAsHead(int recordId) {
		this.positionTree.markHead(orderKeyOf(recordId), recordId);
	}

	/**
	 * Clears the chain-head mark of `recordId` (idempotent - a no-op when it is not a head). Requires a head-aware array.
	 */
	public void unmarkAsHead(int recordId) {
		this.positionTree.unmarkHead(orderKeyOf(recordId), recordId);
	}

	/**
	 * Returns the {@link HeadLocation} of the chain head covering logical `position` - the head at the greatest
	 * head-position `<= position`. Requires a head-aware, non-empty array. `O(log N)`.
	 *
	 * @param position logical position in `[0, getLength())`
	 * @return the covering head's position and record id
	 */
	@Nonnull
	public HeadLocation findHeadCovering(int position) {
		final long packed = this.positionTree.findHeadCovering(position);
		return new HeadLocation((int) (packed >> 32), (int) packed);
	}

	/**
	 * Resolves the order-key of `recordId`, throwing when it is absent from the array.
	 */
	private long orderKeyOf(int recordId) {
		return this.valueIndex.search(recordId).orElseThrow(
			() -> new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array."
			)
		);
	}

	/**
	 * Immutable location of a chain head in the array: its logical position and its record id.
	 *
	 * @param headPosition the logical position of the head record
	 * @param recordId     the head record id
	 */
	public record HeadLocation(int headPosition, int recordId) {
	}

	/*
		PAGING SPI (paged arrays only; delegates to the position tree)
	 */

	/**
	 * Returns whether the backing position tree spans more than one leaf (persisted as PAGED rather than SINGLE).
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isRootInternal() {
		return this.positionTree.isRootInternal();
	}

	/**
	 * Returns one page handle per leaf of the backing tree in ascending logical order. Requires a paged array.
	 *
	 * @return the ordered leaf-page handles
	 */
	@Nonnull
	public List<UnorderedLookupTree.LeafPageHandle> leafPageHandles() {
		return this.positionTree.leafPageHandles();
	}

	/**
	 * Returns the page handles of the leaves changed since the last flush, in ascending logical order. Requires a paged
	 * array.
	 *
	 * @return the changed (dirty) leaf-page handles
	 */
	@Nonnull
	public List<UnorderedLookupTree.LeafPageHandle> collectChangedPages() {
		return this.positionTree.collectChangedPages();
	}

	/**
	 * Returns, in ascending logical order, the page sequences currently assigned to the tree's leaves. Requires a paged
	 * array.
	 *
	 * @return the assigned page sequences
	 */
	@Nonnull
	public int[] livePageSequences() {
		return this.positionTree.livePageSequences();
	}

	/**
	 * Resets the page bookkeeping of every leaf (un-assigns page sequences, clears dirty flags). Requires a paged array.
	 */
	public void forgetPageStream() {
		this.positionTree.forgetPageStream();
	}

	/**
	 * Returns iterator that allows to iterate through all record ids of the array in logical order.
	 */
	public OfInt iterator() {
		return new ConstantIntIterator(getArray());
	}

	@Override
	public int hashCode() {
		/* we deliberately want Object.hashCode() default implementation */
		return super.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		/* we deliberately want Object.equals() default implementation */
		return super.equals(obj);
	}

	@Override
	public String toString() {
		return Arrays.toString(getArray());
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Nullable
	@Override
	public Void createLayer() {
		// the façade holds no diff of its own - all state lives in the two child producers
		return null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.positionTree.removeLayer(transactionalLayer);
		this.valueIndex.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public TransactionalUnorderedIntArray createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new TransactionalUnorderedIntArray(
			transactionalLayer.getStateCopyWithCommittedChanges(this.positionTree),
			transactionalLayer.getStateCopyWithCommittedChanges(this.valueIndex)
		);
	}

}
