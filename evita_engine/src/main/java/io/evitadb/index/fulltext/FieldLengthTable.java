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
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.apache.lucene.util.SmallFloat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

/**
 * The length, in tokens, of one searchable field's value for every entity that has one — quantized to a single byte
 * per entity.
 *
 * ## Why it is kept
 *
 * The impact byte stored with every posting already folds the field length in, so ranking never reads this table.
 * It is kept because storing only that product would make two later things impossible without a reindex: changing
 * the schema's length pivot, and a BM25F that needs term frequency and field length as separate factors
 * (`p1-index-core.md` §4.4). At one byte per (field, entity) it is a fraction of a per cent of the index.
 *
 * ## The quantization
 *
 * Lengths go through Lucene's `SmallFloat#intToByte4`, the encoding Lucene uses for its own length norm: exact for
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
 * over a 1.4M-slot span, where a dense table pays about twelve times what the data needs
 * (`p1-index-core-measurements.md`, part 8). Deciding per block serves both, and keeps an insert's array copy bounded
 * by one block however large the table grows.
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
 * each write first journals the entity's previous length, so a rolled-back entity mutation restores every length it
 * changed. The restore is per entity, not per block: a block the rolled-back write promoted to dense may stay dense.
 * That is no divergence - the shape of a block already depends on its history, through the hysteresis above.
 *
 * Not thread-safe for writes - one writer at a time, as with every index structure; readers of a committed
 * instance are never disturbed, because a committed instance is never written again.
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
	 * Creates an empty table.
	 */
	public FieldLengthTable() {
		this(new char[4], new Object[4], new int[4], 0, 0);
	}

	/**
	 * Adopts the passed spine - the constructor the commit merge builds the next version with.
	 *
	 * @param blockKeys  high 16 bits of each block's keys
	 * @param blocks     the blocks
	 * @param blockSizes entity count of each block
	 * @param blockCount number of live blocks
	 * @param size       number of entities
	 */
	private FieldLengthTable(
		@Nonnull char[] blockKeys,
		@Nonnull Object[] blocks,
		@Nonnull int[] blockSizes,
		int blockCount,
		int size
	) {
		this.blockKeys = blockKeys;
		this.blocks = blocks;
		this.blockSizes = blockSizes;
		this.blockCount = blockCount;
		this.size = size;
	}

	/**
	 * Encodes a length in tokens into its stored byte.
	 *
	 * @param length length in tokens, zero or more
	 * @return the encoded byte as an unsigned value, `0` for a zero length
	 */
	public static int encode(int length) {
		Assert.isPremiseValid(length >= 0, () -> "A field length cannot be negative: " + length + "!");
		return Byte.toUnsignedInt(SmallFloat.intToByte4(length));
	}

	/**
	 * Decodes a stored byte back to a length in tokens. Exact for short lengths and a lower bound for long ones.
	 *
	 * @param encoded the stored byte as an unsigned value
	 * @return the length the byte stands for
	 */
	public static int decode(int encoded) {
		return SmallFloat.byte4ToInt((byte) encoded);
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
	 * Returns whether the block covering the primary key is stored densely in this instance - the committed shape,
	 * whatever a running transaction wrote. Exposed for tests of the per-block representation choice.
	 *
	 * @param primaryKey any primary key the block covers
	 * @return true for a dense block, false for a sparse or absent one
	 */
	boolean isDenseBlock(int primaryKey) {
		final int blockIndex = Arrays.binarySearch(this.blockKeys, 0, this.blockCount, (char) (primaryKey >>> 16));
		return blockIndex >= 0 && this.blocks[blockIndex] instanceof byte[];
	}

	@Nonnull
	@Override
	public FieldLengthTableChanges createLayer() {
		return new FieldLengthTableChanges();
	}

	/**
	 * The delegate branch journals every write - see {@link #write(int, int)}.
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
			this.size
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
		final int blockIndex = Arrays.binarySearch(this.blockKeys, 0, this.blockCount, (char) (primaryKey >>> 16));
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
		final int blockIndex = Arrays.binarySearch(this.blockKeys, 0, this.blockCount, (char) (primaryKey >>> 16));
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
