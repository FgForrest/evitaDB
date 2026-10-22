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

package io.evitadb.index.fulltext;

import com.carrotsearch.hppc.IntByteHashMap;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * The length, in tokens, of one searchable field's value for every entity that has one — quantized to a single byte
 * per entity.
 *
 * ## Why it is kept
 *
 * The impact byte stored with every posting already folds the field length in, so ranking never reads this table.
 * It is kept because storing only that product would make two later things impossible without a reindex: changing
 * the schema's length pivot, and a BM25F that needs term frequency and field length as separate factors. At one byte
 * per (field, entity) it is a fraction of a per cent of the index.
 *
 * ## The quantization
 *
 * Lengths go through {@link NumberUtils#intToByte4(int)}, the encoding Lucene uses for its own length norm: exact for
 * short fields, logarithmic beyond, and monotone throughout, so a longer field never decodes shorter than a shorter
 * one. The encoded value `0` stands for "no length": a field whose value produced no token is not recorded at all.
 *
 * ## The layout — chosen per 65,536-key block, not per table
 *
 * Primary keys are split on the same boundary the posting bitmaps use (`pk >>> 16`). Each block is stored in
 * whichever of two shapes is smaller for the entities it actually holds:
 *
 * - **sparse** — parallel sorted arrays of the low 16 bits and the lengths, 3 bytes per entity;
 * - **dense** — one `byte[65536]` indexed by the low bits, 1 byte per *slot*, present or not.
 *
 * A single table-wide choice was the alternative, and it was measured wrong in both directions: the CMS corpus keeps
 * 972,611 documents in a 972,611-slot span, where dense is ideal, while the e-commerce corpus spreads 118,772 products
 * over a 1.4M-slot span, where a dense table pays about twelve times what the data needs (the index-core
 * measurements of the fulltext decision record). Deciding per block serves both, and keeps an insert's array copy
 * bounded by one block however large the table grows.
 *
 * A sparse block becomes dense once it holds more than {@link #DENSE_PROMOTION_SIZE} entities — the point at which
 * the dense form gets smaller — and a dense block returns to sparse only below half of that, so a block hovering at
 * the boundary does not rebuild its representation on every write.
 *
 * ## Transactional behaviour
 *
 * Inside a transaction every write goes to a {@link FieldLengthTableChanges} layer of per-entity overrides, and the
 * commit merge produces a new table: the block spine is copied (one entry per 65,536 keys) and every block the
 * transaction touched is cloned once and written there, while untouched blocks are shared with the previous version.
 * A commit touching an entity in a dense block therefore copies that block's 64 KiB; one in a sparse block copies
 * three bytes per entity the block holds. A table no transaction wrote to is carried forward as the same instance.
 *
 * Outside a transaction (the warm-up bulk path) the blocks are written in place, and while a warm-up savepoint is open
 * each write that changes a length first journals the entity's previous one, so a rolled-back entity mutation
 * restores every length it changed. The restore is per entity, not per block: a block the rolled-back write promoted
 * to dense may stay dense. That is no divergence - the shape of a block already depends on its history, through the
 * hysteresis above.
 *
 * ## Persistence
 *
 * The block is also the unit a flush writes: one page per block, keyed by the field and the block's key, so a flush
 * rewrites only the blocks that changed since the previous one - {@link #collectChangedBlocks()} - instead of the whole
 * table. What changed is known from where the writes went: inside a transaction, the blocks of the layer's overrides
 * (the engine flushes a transaction before the commit merges it); outside one, the blocks written in place, which
 * every such write marks. A block that left the table since it was last written becomes a removal, which takes the set
 * of blocks on disk - kept, like the marks, in bookkeeping that is not transactional and is carried by reference into
 * the committed copy at every merge.
 *
 * Not thread-safe for writes - one writer at a time, as with every index structure, which is what the
 * `@NotThreadSafe` annotation states. Readers of a committed instance are safe concurrently and never disturbed,
 * because a committed instance is never written again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class FieldLengthTable implements TransactionalLayerProducer<FieldLengthTableChanges, FieldLengthTable> {

	/**
	 * Number of primary keys one block covers.
	 */
	static final int BLOCK_SIZE = 1 << 16;

	/**
	 * Entity count above which a sparse block is converted to dense: a sparse entry costs three bytes, a dense block
	 * costs {@link #BLOCK_SIZE} bytes whatever it holds.
	 */
	static final int DENSE_PROMOTION_SIZE = BLOCK_SIZE / 3;

	/**
	 * Entity count below which a dense block is converted back to sparse — half the promotion size, the hysteresis
	 * that keeps a block on the boundary from flipping on every write.
	 */
	static final int SPARSE_DEMOTION_SIZE = DENSE_PROMOTION_SIZE / 2;

	/**
	 * Initial capacity of a new sparse block.
	 */
	private static final int INITIAL_SPARSE_CAPACITY = 8;

	/**
	 * Identity of this instance in the transactional memory.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * High 16 bits of the primary keys each block covers, ascending, unsigned.
	 */
	@Nonnull private char[] blockKeys;

	/**
	 * The blocks, parallel to {@link #blockKeys}: a `byte[]` of {@link #BLOCK_SIZE} for a dense block, a
	 * {@link SparseBlock} for a sparse one.
	 */
	@Nonnull private Object[] blocks;

	/**
	 * Number of entities each block holds, parallel to {@link #blockKeys}.
	 */
	@Nonnull private int[] blockSizes;

	/**
	 * Number of live blocks.
	 */
	private int blockCount;

	/**
	 * Number of entities the table holds a length for.
	 */
	private int size;

	/**
	 * Which blocks are on disk and which were written in place since the last flush; shared with every committed copy.
	 */
	@Nonnull private final FlushState flushState;

	/**
	 * One block of the table as a flush writes it: the entities it holds, in ascending order of their primary keys.
	 *
	 * @param blockKey high 16 bits of the primary keys the block covers
	 * @param lows     low 16 bits of the primary keys, strictly ascending
	 * @param lengths  the encoded lengths, parallel to `lows`, never `0`
	 */
	public record LengthBlock(int blockKey, @Nonnull char[] lows, @Nonnull byte[] lengths) implements Serializable {
		@Serial private static final long serialVersionUID = 4948225412202134449L;

		/**
		 * Verifies the block is non-empty, ordered and holds only real lengths.
		 */
		public LengthBlock {
			Assert.isPremiseValid(
				blockKey >= 0 && blockKey <= 0xFFFF,
				() -> "A length block key must be a 16-bit value, " + blockKey + " was passed!"
			);
			Assert.isPremiseValid(
				lows.length > 0 && lows.length == lengths.length,
				() -> "Length block " + blockKey + " must hold at least one entity with one length each, but has " +
					lows.length + " keys and " + lengths.length + " lengths!"
			);
			// a plain check per entity - a dense block loads tens of thousands, too many for a message lambda each
			for (int i = 0; i < lows.length; i++) {
				if ((i > 0 && lows[i] <= lows[i - 1]) || lengths[i] == 0) {
					throw new GenericEvitaInternalError(
						"Length block " + blockKey + " is not strictly ascending or holds a zero length at " + i + "!"
					);
				}
			}
		}

		/**
		 * Builds the block from its slots, one encoded length per low 16 bits of the primary key.
		 *
		 * @param blockKey high 16 bits of the primary keys the block covers
		 * @param slots    {@link #BLOCK_SIZE} encoded lengths, `0` for an absent entity
		 * @return the block, or `null` when no slot is occupied
		 */
		@Nullable
		static LengthBlock fromSlots(int blockKey, @Nonnull byte[] slots) {
			int count = 0;
			for (final byte slot : slots) {
				if (slot != 0) {
					count++;
				}
			}
			if (count == 0) {
				return null;
			}
			final char[] lows = new char[count];
			final byte[] lengths = new byte[count];
			int position = 0;
			for (int low = 0; low < slots.length; low++) {
				if (slots[low] != 0) {
					lows[position] = (char) low;
					lengths[position] = slots[low];
					position++;
				}
			}
			return new LengthBlock(blockKey, lows, lengths);
		}

	}

	/**
	 * What a flush of the table writes.
	 *
	 * @param blockKeys        the keys of every block the table holds after the flush, ascending - the page list
	 * @param changedBlocks    the blocks written since the previous flush, ascending by key
	 * @param removedBlockKeys the keys of the blocks on disk that left the table, ascending
	 */
	public record LengthBlockEmission(
		@Nonnull int[] blockKeys,
		@Nonnull List<LengthBlock> changedBlocks,
		@Nonnull int[] removedBlockKeys
	) {
	}

	/**
	 * Creates an empty table.
	 */
	public FieldLengthTable() {
		this(new char[4], new Object[4], new int[4], 0, 0, new FlushState(ArrayUtils.EMPTY_INT_ARRAY));
	}

	/**
	 * Adopts the passed spine - the constructor the commit merge builds the next version with.
	 *
	 * @param blockKeys  high 16 bits of each block's keys
	 * @param blocks     the blocks
	 * @param blockSizes entity count of each block
	 * @param blockCount number of live blocks
	 * @param size       number of entities
	 * @param flushState the flush bookkeeping, shared with the version the copy is made from
	 */
	private FieldLengthTable(
		@Nonnull char[] blockKeys,
		@Nonnull Object[] blocks,
		@Nonnull int[] blockSizes,
		int blockCount,
		int size,
		@Nonnull FlushState flushState
	) {
		this.blockKeys = blockKeys;
		this.blocks = blocks;
		this.blockSizes = blockSizes;
		this.blockCount = blockCount;
		this.size = size;
		this.flushState = flushState;
	}

	/**
	 * Restores a table from its persisted blocks. The restored table is clean: its blocks are the ones on disk, so the
	 * first flush after the load writes nothing for an unchanged table.
	 *
	 * Each block takes the shape its entity count makes the smaller one; the shape it had before it was written is not
	 * persisted, and need not be, since it never changes a length.
	 *
	 * @param blocks the blocks in ascending key order
	 * @return the restored table
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the blocks are not in ascending key order
	 */
	@Nonnull
	public static FieldLengthTable fromPersistedBlocks(@Nonnull LengthBlock[] blocks) {
		final int capacity = Math.max(4, blocks.length);
		final char[] blockKeys = new char[capacity];
		final Object[] blockArray = new Object[capacity];
		final int[] blockSizes = new int[capacity];
		final int[] persistedBlockKeys = new int[blocks.length];
		int size = 0;
		for (int i = 0; i < blocks.length; i++) {
			final LengthBlock block = blocks[i];
			final int position = i;
			Assert.isPremiseValid(
				i == 0 || block.blockKey() > blocks[i - 1].blockKey(),
				() -> "Persisted length blocks must be in strictly ascending key order, but block " +
					block.blockKey() + " is at position " + position + "!"
			);
			final int count = block.lows().length;
			if (count > DENSE_PROMOTION_SIZE) {
				final byte[] dense = new byte[BLOCK_SIZE];
				for (int j = 0; j < count; j++) {
					dense[block.lows()[j]] = block.lengths()[j];
				}
				blockArray[i] = dense;
			} else {
				final SparseBlock sparse = new SparseBlock(Math.max(INITIAL_SPARSE_CAPACITY, count));
				System.arraycopy(block.lows(), 0, sparse.lows, 0, count);
				System.arraycopy(block.lengths(), 0, sparse.lengths, 0, count);
				sparse.count = count;
				blockArray[i] = sparse;
			}
			blockKeys[i] = (char) block.blockKey();
			blockSizes[i] = count;
			persistedBlockKeys[i] = block.blockKey();
			size += count;
		}
		return new FieldLengthTable(
			blockKeys, blockArray, blockSizes, blocks.length, size, new FlushState(persistedBlockKeys)
		);
	}

	/**
	 * Encodes a length in tokens into its stored byte.
	 *
	 * @param length length in tokens, zero or more
	 * @return the encoded byte as an unsigned value, `0` for a zero length
	 */
	public static int encode(int length) {
		Assert.isPremiseValid(length >= 0, () -> "A field length cannot be negative: " + length + "!");
		return Byte.toUnsignedInt(NumberUtils.intToByte4(length));
	}

	/**
	 * Decodes a stored byte back to a length in tokens. Exact for short lengths and a lower bound for long ones.
	 *
	 * @param encoded the stored byte as an unsigned value
	 * @return the length the byte stands for
	 */
	public static int decode(int encoded) {
		return NumberUtils.byte4ToInt((byte) encoded);
	}

	/**
	 * Records the field length of an entity, replacing any previous one. A zero length removes the entity, because
	 * a value without tokens contributes nothing a length could normalize. Written only by the owning
	 * {@link FulltextIndex}.
	 *
	 * @param primaryKey primary key of the entity
	 * @param length     length of the entity's value of the field, in tokens
	 */
	void put(int primaryKey, int length) {
		write(primaryKey, encode(length));
	}

	/**
	 * Forgets the field length of an entity. Removing an entity the table does not hold changes nothing. Written only
	 * by the owning {@link FulltextIndex}.
	 *
	 * @param primaryKey primary key of the entity
	 */
	void remove(int primaryKey) {
		write(primaryKey, 0);
	}

	/**
	 * Returns the stored byte of an entity's field length, as the caller's transaction sees it.
	 *
	 * @param primaryKey primary key of the entity
	 * @return the encoded length as an unsigned value, `0` when the table holds no length for the entity
	 */
	public int getEncoded(int primaryKey) {
		final FieldLengthTableChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		if (layer != null) {
			final int overridden = layer.getEncoded(primaryKey);
			if (overridden >= 0) {
				return overridden;
			}
		}
		return getEncodedInPlace(primaryKey);
	}

	/**
	 * Returns an entity's field length, decoded from its stored byte.
	 *
	 * @param primaryKey primary key of the entity
	 * @return the length in tokens — exact for short fields, a lower bound for long ones — or `0` when the table
	 * holds no length for the entity
	 */
	public int getLength(int primaryKey) {
		return decode(getEncoded(primaryKey));
	}

	/**
	 * Returns the number of entities the table holds a length for, as the caller's transaction sees it.
	 *
	 * @return the entity count
	 */
	public int size() {
		final FieldLengthTableChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.size : this.size + layer.getSizeDelta();
	}

	/**
	 * Returns the heap this table occupies, in bytes: the table object, its three parallel arrays at their allocated
	 * capacity, and every live block - a dense block is its full 64 KiB slot array, a sparse one its object and its two
	 * arrays at their allocated capacity.
	 *
	 * The {@link FlushState} is not charged: it is flush bookkeeping shared by every committed copy of the table, the
	 * way a {@link io.evitadb.index.page.PageStreamRegistry} is for a paged tree, and no index charges that either. A
	 * running transaction's layer belongs to the transaction.
	 *
	 * @return the heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// id, blockCount and size, then the blockKeys / blocks / blockSizes / flushState slots
		long size = layout.sizeOfObject(Long.BYTES + 2L * Integer.BYTES + 4L * layout.referenceSize())
			+ layout.sizeOfArray(this.blockKeys.length, Character.BYTES)
			+ layout.sizeOfArray(this.blocks.length, layout.referenceSize())
			+ layout.sizeOfArray(this.blockSizes.length, Integer.BYTES);
		for (int i = 0; i < this.blockCount; i++) {
			if (this.blocks[i] instanceof final SparseBlock sparse) {
				// count, then the lows / lengths slots
				size += layout.sizeOfObject(Integer.BYTES + 2L * layout.referenceSize())
					+ layout.sizeOfArray(sparse.lows.length, Character.BYTES)
					+ layout.sizeOfArray(sparse.lengths.length, Byte.BYTES);
			} else {
				size += layout.sizeOfArray(((byte[]) this.blocks[i]).length, Byte.BYTES);
			}
		}
		return size;
	}

	/**
	 * Returns the keys of the blocks the last flush left on disk - the ones a {@link #collectChangedBlocks()} diffs
	 * against, and the ones a dropped table must reclaim. The set is replaced wholesale by every collect, never changed
	 * in place, so the returned array keeps describing the flush it was taken after; the caller must not modify it.
	 *
	 * @return keys of the blocks on disk, ascending; empty for a table never flushed
	 */
	@Nonnull
	public int[] getPersistedBlockKeys() {
		return this.flushState.persistedBlockKeys;
	}

	/**
	 * Returns whether the block covering the primary key is stored densely in this instance - the committed shape,
	 * whatever a running transaction wrote. Exposed for tests of the per-block representation choice.
	 *
	 * @param primaryKey any primary key the block covers
	 * @return true for a dense block, false for a sparse or absent one
	 */
	boolean isDenseBlock(int primaryKey) {
		final int blockIndex = findBlock(primaryKey);
		return blockIndex >= 0 && this.blocks[blockIndex] instanceof byte[];
	}

	/**
	 * Returns what a flush must write: every block that changed since the previous flush, as the caller's transaction
	 * sees it, the blocks on disk that left the table, and the keys of every block the table now holds. The returned
	 * set of blocks becomes the set on disk the next flush diffs against, and the in-place marks are cleared.
	 *
	 * Publishing at collect time is safe for the reason the dictionary's page registry records: a failed flush is
	 * never followed by another flush of the same data.
	 *
	 * @return the changed blocks, the removed block keys and the full block list
	 */
	@Nonnull
	public LengthBlockEmission collectChangedBlocks() {
		final FieldLengthTableChanges layer = Transaction.getTransactionalMemoryLayerIfExists(this);
		final int[] overriddenKeys = layer == null
			? ArrayUtils.EMPTY_INT_ARRAY
			: sortedByBlock(layer.getOverrides().keys().toArray());
		final BitSet candidates = this.flushState.drainWrittenInPlace();
		for (final int primaryKey : overriddenKeys) {
			candidates.set(primaryKey >>> 16);
		}
		final BitSet present = new BitSet(BLOCK_SIZE);
		for (int i = 0; i < this.blockCount; i++) {
			present.set(this.blockKeys[i]);
		}

		final int candidateCount = candidates.cardinality();
		final List<LengthBlock> changed = new ArrayList<>(candidateCount);
		final int[] removed = new int[candidateCount];
		int removedCount = 0;
		final byte[] slots = new byte[candidateCount == 0 ? 0 : BLOCK_SIZE];
		int overrideIndex = 0;
		for (int blockKey = candidates.nextSetBit(0); blockKey >= 0; blockKey = candidates.nextSetBit(blockKey + 1)) {
			// the block as the caller sees it: this instance's own content, then the transaction's overrides of it,
			// which come in block order because both walks ascend
			Arrays.fill(slots, (byte) 0);
			copyBlockInto(blockKey, slots);
			// without a layer the override array is empty and the loop would not run anyway; the check states that
			// where the nullable `layer` is dereferenced
			if (layer != null) {
				while (overrideIndex < overriddenKeys.length && overriddenKeys[overrideIndex] >>> 16 == blockKey) {
					final int primaryKey = overriddenKeys[overrideIndex++];
					slots[primaryKey & 0xFFFF] = (byte) layer.getEncoded(primaryKey);
				}
			}
			final LengthBlock block = LengthBlock.fromSlots(blockKey, slots);
			if (block != null) {
				present.set(blockKey);
				changed.add(block);
			} else {
				present.clear(blockKey);
				if (this.flushState.isPersisted(blockKey)) {
					removed[removedCount++] = blockKey;
				}
			}
		}
		Assert.isPremiseValid(
			overrideIndex == overriddenKeys.length,
			"Every override of the transaction must belong to a collected block!"
		);

		final int[] blockKeys = new int[present.cardinality()];
		for (int i = 0, blockKey = present.nextSetBit(0); blockKey >= 0; blockKey = present.nextSetBit(blockKey + 1)) {
			blockKeys[i++] = blockKey;
		}
		this.flushState.setPersisted(blockKeys);
		return new LengthBlockEmission(blockKeys, changed, Arrays.copyOf(removed, removedCount));
	}

	@Nonnull
	@Override
	public FieldLengthTableChanges createLayer() {
		return new FieldLengthTableChanges();
	}

	/**
	 * The delegate branch journals every write that changes a length - see {@link #write(int, int)}.
	 *
	 * @return always true
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

	@Nonnull
	@Override
	public FieldLengthTable createCopyWithMergedTransactionalMemory(
		@Nullable FieldLengthTableChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (layer == null) {
			return this;
		}
		final IntByteHashMap overrides = layer.getOverrides();
		final int[] primaryKeys = overrides.keys().toArray();
		// grouped by block, so each touched block is cloned once and every write after the clone lands on the copy;
		// any grouping order would do, ascending signed order is the cheapest to get
		Arrays.sort(primaryKeys);
		final FieldLengthTable copy = new FieldLengthTable(
			Arrays.copyOf(this.blockKeys, this.blockKeys.length),
			Arrays.copyOf(this.blocks, this.blocks.length),
			Arrays.copyOf(this.blockSizes, this.blockSizes.length),
			this.blockCount,
			this.size,
			this.flushState
		);
		int ownedBlockKey = -1;
		for (final int primaryKey : primaryKeys) {
			final int blockKey = primaryKey >>> 16;
			if (blockKey != ownedBlockKey) {
				copy.ownBlock((char) blockKey);
				ownedBlockKey = blockKey;
			}
			copy.writeInPlace(primaryKey, Byte.toUnsignedInt(overrides.get(primaryKey)));
		}
		Assert.isPremiseValid(
			copy.size == this.size + layer.getSizeDelta(),
			() -> "The merged length table holds " + copy.size + " entities, the transaction expected " +
				(this.size + layer.getSizeDelta()) + "!"
		);
		return copy;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	/**
	 * Applies a write as the caller's context requires: into the transaction's layer, or in place - journalled into
	 * the warm-up savepoint when one is open.
	 *
	 * @param primaryKey primary key of the entity
	 * @param encoded    the encoded length, `0` to remove the entity
	 */
	private void write(int primaryKey, int encoded) {
		final FieldLengthTableChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer != null) {
			layer.set(primaryKey, encoded, getEncoded(primaryKey));
			return;
		}
		// in place, so no layer will tell the flush which block changed - the mark does; a rolled-back write leaves its
		// mark behind, which only rewrites a block with the content it already has
		this.flushState.markWrittenInPlace(primaryKey >>> 16);
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			// an absolute restore of this entity's length, whatever the writes after it did to its block
			final int previous = getEncodedInPlace(primaryKey);
			if (previous == encoded) {
				return;
			}
			savepoint.push(() -> writeInPlace(primaryKey, previous));
		}
		writeInPlace(primaryKey, encoded);
	}

	/**
	 * Writes an entity's encoded length into this instance's own blocks.
	 *
	 * @param primaryKey primary key of the entity
	 * @param encoded    the encoded length, `0` to remove the entity
	 */
	private void writeInPlace(int primaryKey, int encoded) {
		if (encoded == 0) {
			removeInPlace(primaryKey);
		} else {
			putInPlace(primaryKey, encoded);
		}
	}

	/**
	 * Replaces the block covering the keys with a private copy, so the commit merge can write to it without touching
	 * the version it was copied from. An absent block needs nothing: the first write opens a fresh one.
	 *
	 * @param blockKey high 16 bits of the keys the block covers
	 */
	private void ownBlock(char blockKey) {
		final int blockIndex = Arrays.binarySearch(this.blockKeys, 0, this.blockCount, blockKey);
		if (blockIndex >= 0) {
			final Object block = this.blocks[blockIndex];
			this.blocks[blockIndex] = block instanceof byte[] dense ? dense.clone() : ((SparseBlock) block).copy();
		}
	}

	/**
	 * Sets an entity's encoded length in this instance's own blocks.
	 *
	 * @param primaryKey primary key of the entity
	 * @param encoded    the encoded length, never `0`
	 */
	private void putInPlace(int primaryKey, int encoded) {
		final char blockKey = (char) (primaryKey >>> 16);
		final char low = (char) primaryKey;
		int blockIndex = Arrays.binarySearch(this.blockKeys, 0, this.blockCount, blockKey);
		if (blockIndex < 0) {
			blockIndex = insertBlock(-blockIndex - 1, blockKey);
		}
		final Object block = this.blocks[blockIndex];
		if (block instanceof byte[] dense) {
			if (dense[low] == 0) {
				this.blockSizes[blockIndex]++;
				this.size++;
			}
			dense[low] = (byte) encoded;
		} else {
			final SparseBlock sparse = (SparseBlock) block;
			if (sparse.put(low, (byte) encoded)) {
				this.blockSizes[blockIndex]++;
				this.size++;
				if (this.blockSizes[blockIndex] > DENSE_PROMOTION_SIZE) {
					this.blocks[blockIndex] = sparse.toDense();
				}
			}
		}
	}

	/**
	 * Removes an entity from this instance's own blocks. Removing an entity the table does not hold changes nothing.
	 *
	 * @param primaryKey primary key of the entity
	 */
	private void removeInPlace(int primaryKey) {
		final int blockIndex = findBlock(primaryKey);
		if (blockIndex < 0) {
			return;
		}
		final char low = (char) primaryKey;
		final Object block = this.blocks[blockIndex];
		final boolean removed;
		if (block instanceof byte[] dense) {
			removed = dense[low] != 0;
			dense[low] = 0;
		} else {
			removed = ((SparseBlock) block).remove(low);
		}
		if (!removed) {
			return;
		}
		this.size--;
		final int blockSize = --this.blockSizes[blockIndex];
		if (blockSize == 0) {
			removeBlock(blockIndex);
		} else if (block instanceof byte[] dense && blockSize < SPARSE_DEMOTION_SIZE) {
			this.blocks[blockIndex] = SparseBlock.fromDense(dense, blockSize);
		}
	}

	/**
	 * Reads an entity's encoded length from this instance's own blocks, ignoring any transaction.
	 *
	 * @param primaryKey primary key of the entity
	 * @return the encoded length as an unsigned value, `0` when the table holds no length for the entity
	 */
	private int getEncodedInPlace(int primaryKey) {
		final int blockIndex = findBlock(primaryKey);
		if (blockIndex < 0) {
			return 0;
		}
		final Object block = this.blocks[blockIndex];
		if (block instanceof byte[] dense) {
			return Byte.toUnsignedInt(dense[(char) primaryKey]);
		} else {
			return ((SparseBlock) block).get((char) primaryKey);
		}
	}

	/**
	 * Searches the block spine for the block covering a primary key.
	 *
	 * @param primaryKey any primary key the block covers
	 * @return the block's index, or `-(insertion point) - 1` when no block covers the key
	 */
	private int findBlock(int primaryKey) {
		return Arrays.binarySearch(this.blockKeys, 0, this.blockCount, (char) (primaryKey >>> 16));
	}

	/**
	 * Opens a new, empty sparse block at the position, keeping the block arrays sorted.
	 *
	 * @param position index the block takes
	 * @param blockKey high 16 bits of the primary keys it covers
	 * @return the index of the new block
	 */
	private int insertBlock(int position, char blockKey) {
		if (this.blockCount == this.blockKeys.length) {
			final int capacity = this.blockKeys.length * 2;
			this.blockKeys = Arrays.copyOf(this.blockKeys, capacity);
			this.blocks = Arrays.copyOf(this.blocks, capacity);
			this.blockSizes = Arrays.copyOf(this.blockSizes, capacity);
		}
		final int tail = this.blockCount - position;
		System.arraycopy(this.blockKeys, position, this.blockKeys, position + 1, tail);
		System.arraycopy(this.blocks, position, this.blocks, position + 1, tail);
		System.arraycopy(this.blockSizes, position, this.blockSizes, position + 1, tail);
		this.blockKeys[position] = blockKey;
		this.blocks[position] = new SparseBlock(INITIAL_SPARSE_CAPACITY);
		this.blockSizes[position] = 0;
		this.blockCount++;
		return position;
	}

	/**
	 * Drops an emptied block, keeping the block arrays sorted.
	 *
	 * @param position index of the block
	 */
	private void removeBlock(int position) {
		final int tail = this.blockCount - position - 1;
		System.arraycopy(this.blockKeys, position + 1, this.blockKeys, position, tail);
		System.arraycopy(this.blocks, position + 1, this.blocks, position, tail);
		System.arraycopy(this.blockSizes, position + 1, this.blockSizes, position, tail);
		this.blockCount--;
		this.blocks[this.blockCount] = null;
	}

	/**
	 * Copies the slots of this instance's own block into the passed array; an absent block copies nothing.
	 *
	 * @param blockKey high 16 bits of the keys the block covers
	 * @param slots    {@link #BLOCK_SIZE} slots to copy into
	 */
	private void copyBlockInto(int blockKey, @Nonnull byte[] slots) {
		final int blockIndex = Arrays.binarySearch(this.blockKeys, 0, this.blockCount, (char) blockKey);
		if (blockIndex < 0) {
			return;
		}
		final Object block = this.blocks[blockIndex];
		if (block instanceof byte[] dense) {
			System.arraycopy(dense, 0, slots, 0, BLOCK_SIZE);
		} else {
			final SparseBlock sparse = (SparseBlock) block;
			for (int i = 0; i < sparse.count; i++) {
				slots[sparse.lows[i]] = sparse.lengths[i];
			}
		}
	}

	/**
	 * Sorts primary keys by their block and, within it, by their low bits - unsigned order, which keeps a key past
	 * `Integer.MAX_VALUE` in the block its high bits name.
	 *
	 * @param primaryKeys the keys, sorted in place
	 * @return the same array
	 */
	@Nonnull
	private static int[] sortedByBlock(@Nonnull int[] primaryKeys) {
		for (int i = 0; i < primaryKeys.length; i++) {
			primaryKeys[i] ^= Integer.MIN_VALUE;
		}
		Arrays.sort(primaryKeys);
		for (int i = 0; i < primaryKeys.length; i++) {
			primaryKeys[i] ^= Integer.MIN_VALUE;
		}
		return primaryKeys;
	}

	/**
	 * The flush bookkeeping of one table: the keys of the blocks on disk, which a flush diffs against to find the
	 * removed ones, and the blocks written in place since the last flush. Not transactional - single-writer flush
	 * bookkeeping shared by reference with every committed copy of the table.
	 */
	private static final class FlushState {

		/**
		 * Keys of the blocks on disk, ascending.
		 */
		@Nonnull private int[] persistedBlockKeys;

		/**
		 * Keys of the blocks written in place since the last flush; `null` while there is none.
		 */
		@Nullable private BitSet writtenInPlace;

		/**
		 * Creates the bookkeeping of a table with the passed blocks on disk.
		 *
		 * @param persistedBlockKeys keys of the blocks on disk, ascending
		 */
		FlushState(@Nonnull int[] persistedBlockKeys) {
			this.persistedBlockKeys = persistedBlockKeys;
		}

		/**
		 * Marks a block as written in place.
		 *
		 * @param blockKey high 16 bits of the keys the block covers
		 */
		void markWrittenInPlace(int blockKey) {
			if (this.writtenInPlace == null) {
				this.writtenInPlace = new BitSet(BLOCK_SIZE);
			}
			this.writtenInPlace.set(blockKey);
		}

		/**
		 * Takes the blocks written in place since the last flush, leaving none marked.
		 *
		 * @return the marked block keys, a set the caller may modify
		 */
		@Nonnull
		BitSet drainWrittenInPlace() {
			final BitSet marked = this.writtenInPlace;
			this.writtenInPlace = null;
			return marked == null ? new BitSet(BLOCK_SIZE) : marked;
		}

		/**
		 * Returns whether a block is on disk.
		 *
		 * @param blockKey high 16 bits of the keys the block covers
		 * @return true when the last flush left the block on disk
		 */
		boolean isPersisted(int blockKey) {
			return Arrays.binarySearch(this.persistedBlockKeys, blockKey) >= 0;
		}

		/**
		 * Replaces the set of blocks on disk.
		 *
		 * @param blockKeys keys of the blocks on disk, ascending
		 */
		void setPersisted(@Nonnull int[] blockKeys) {
			this.persistedBlockKeys = blockKeys;
		}

	}

	/**
	 * A block holding few entities: sorted low 16 bits of their primary keys and their encoded lengths, in parallel.
	 */
	private static final class SparseBlock {

		/**
		 * Low 16 bits of the primary keys, ascending.
		 */
		@Nonnull private char[] lows;

		/**
		 * Encoded lengths, parallel to {@link #lows}; never `0`.
		 */
		@Nonnull private byte[] lengths;

		/**
		 * Number of live entries.
		 */
		private int count;

		/**
		 * Creates an empty block.
		 *
		 * @param capacity initial capacity
		 */
		SparseBlock(int capacity) {
			this.lows = new char[capacity];
			this.lengths = new byte[capacity];
		}

		/**
		 * Builds a sparse block from a dense one.
		 *
		 * @param dense the dense block
		 * @param count number of non-zero slots it holds
		 * @return the equivalent sparse block
		 */
		@Nonnull
		static SparseBlock fromDense(@Nonnull byte[] dense, int count) {
			final SparseBlock sparse = new SparseBlock(Math.max(INITIAL_SPARSE_CAPACITY, count));
			for (int low = 0; low < dense.length; low++) {
				if (dense[low] != 0) {
					sparse.lows[sparse.count] = (char) low;
					sparse.lengths[sparse.count] = dense[low];
					sparse.count++;
				}
			}
			Assert.isPremiseValid(
				sparse.count == count,
				() -> "Dense length block holds " + sparse.count + " entries, its recorded size is " + count + "!"
			);
			return sparse;
		}

		/**
		 * Sets the encoded length of an entry.
		 *
		 * @param low     low 16 bits of the primary key
		 * @param encoded the encoded length, never `0`
		 * @return true when the entry is new, false when an existing one was overwritten
		 */
		boolean put(char low, byte encoded) {
			final int index = Arrays.binarySearch(this.lows, 0, this.count, low);
			if (index >= 0) {
				this.lengths[index] = encoded;
				return false;
			}
			final int position = -index - 1;
			if (this.count == this.lows.length) {
				final int capacity = this.lows.length + (this.lows.length >> 1) + 1;
				this.lows = Arrays.copyOf(this.lows, capacity);
				this.lengths = Arrays.copyOf(this.lengths, capacity);
			}
			System.arraycopy(this.lows, position, this.lows, position + 1, this.count - position);
			System.arraycopy(this.lengths, position, this.lengths, position + 1, this.count - position);
			this.lows[position] = low;
			this.lengths[position] = encoded;
			this.count++;
			return true;
		}

		/**
		 * Removes an entry.
		 *
		 * @param low low 16 bits of the primary key
		 * @return true when the entry existed
		 */
		boolean remove(char low) {
			final int index = Arrays.binarySearch(this.lows, 0, this.count, low);
			if (index < 0) {
				return false;
			}
			System.arraycopy(this.lows, index + 1, this.lows, index, this.count - index - 1);
			System.arraycopy(this.lengths, index + 1, this.lengths, index, this.count - index - 1);
			this.count--;
			return true;
		}

		/**
		 * Reads the encoded length of an entry.
		 *
		 * @param low low 16 bits of the primary key
		 * @return the encoded length as an unsigned value, `0` when absent
		 */
		int get(char low) {
			final int index = Arrays.binarySearch(this.lows, 0, this.count, low);
			return index < 0 ? 0 : Byte.toUnsignedInt(this.lengths[index]);
		}

		/**
		 * Copies this block, so the copy can be written without touching this one.
		 *
		 * @return an independent block holding the same entries
		 */
		@Nonnull
		SparseBlock copy() {
			final SparseBlock copy = new SparseBlock(this.lows.length);
			System.arraycopy(this.lows, 0, copy.lows, 0, this.count);
			System.arraycopy(this.lengths, 0, copy.lengths, 0, this.count);
			copy.count = this.count;
			return copy;
		}

		/**
		 * Converts this block to the dense shape.
		 *
		 * @return a {@link #BLOCK_SIZE}-slot array holding the same entries
		 */
		@Nonnull
		byte[] toDense() {
			final byte[] dense = new byte[BLOCK_SIZE];
			for (int i = 0; i < this.count; i++) {
				dense[this.lows[i]] = this.lengths[i];
			}
			return dense;
		}

	}

}
