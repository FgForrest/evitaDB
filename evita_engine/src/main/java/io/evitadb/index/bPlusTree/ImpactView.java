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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;

/**
 * A read-only view of the impacts of one bucket of an impact-carrying {@link TransactionalBucketBPlusTree}, one byte
 * per record in the order the bucket's records enumerate in (unsigned ascending) - what
 * {@link TransactionalBucketBPlusTree.BucketCursor#impacts()} answers with.
 *
 * ## Why a view and not an array
 *
 * The leaf stores a bucket's impacts in the shape its record set has (see {@link ImpactRecords}): one byte for a
 * single record, a flat array for a small bucket, and one chunk per roaring container for a bitmap bucket. A flat
 * `byte[]` answer would have to copy - and for a chunked bucket concatenate - on every visit. The view wraps the
 * stored arrays instead, so a cursor walk over thousands of terms reads the impacts where they lie, without a second
 * descent and without a copy. Only a bucket the open transaction wrote as a bitmap is aligned into a fresh array,
 * because its stored form is an unaligned delta.
 *
 * The wrapped arrays are the tree's own storage; the view never hands them out, which is what keeps it read-only.
 *
 * ## Reading
 *
 * The view itself is immutable and may be shared. Reading goes through a {@link Reader}, a small single-threaded
 * cursor that remembers the chunk it read last, so the ascending access of a posting-list merge costs one array read
 * per impact. Random access is answered as well, at the cost of a walk over the chunk lengths when it moves backwards.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class ImpactView {
	/**
	 * The view of a bucket with no record - what a lookup of an absent value answers with.
	 */
	public static final ImpactView EMPTY = new ImpactView(new byte[0], null, 0);
	/**
	 * The views of single-record buckets, one per impact value, shared so a single bucket never allocates a view.
	 */
	private static final ImpactView[] SINGLES = new ImpactView[256];

	static {
		for (int i = 0; i < SINGLES.length; i++) {
			SINGLES[i] = new ImpactView(new byte[]{(byte) i}, null, 1);
		}
	}

	/**
	 * The impacts as one array, or `null` when they are chunked.
	 */
	@Nullable private final byte[] flat;
	/**
	 * The impacts as chunks whose concatenation is the record order, or `null` when they are flat.
	 */
	@Nullable private final byte[][] chunks;
	/**
	 * How many impacts the view holds.
	 */
	private final int size;

	/**
	 * @param flat   the impacts as one array, or `null`
	 * @param chunks the impacts as chunks, or `null`; exactly one of the two is non-null
	 * @param size   how many impacts the view holds
	 */
	private ImpactView(@Nullable byte[] flat, @Nullable byte[][] chunks, int size) {
		this.flat = flat;
		this.chunks = chunks;
		this.size = size;
	}

	/**
	 * Wraps impacts the caller already holds as one array. The array is adopted, not copied - it must not be written
	 * after the call.
	 *
	 * @param impacts the impacts in record order
	 * @return the view
	 */
	@Nonnull
	public static ImpactView of(@Nonnull byte[] impacts) {
		return impacts.length == 0 ? EMPTY : new ImpactView(impacts, null, impacts.length);
	}

	/**
	 * Returns the shared view of a single-record bucket.
	 *
	 * @param impact the record's impact
	 * @return the view
	 */
	@Nonnull
	static ImpactView single(byte impact) {
		return SINGLES[Byte.toUnsignedInt(impact)];
	}

	/**
	 * Wraps the chunks of a bitmap bucket, without copying them.
	 *
	 * @param chunks the chunks, chunk `c` parallel to roaring container `c`
	 * @param size   the total number of impacts the chunks hold
	 * @return the view
	 */
	@Nonnull
	static ImpactView ofChunks(@Nonnull byte[][] chunks, int size) {
		return chunks.length == 1 ? of(chunks[0]) : new ImpactView(null, chunks, size);
	}

	/**
	 * Returns how many impacts the view holds - the cardinality of the bucket it was taken of.
	 *
	 * @return the number of impacts
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Opens a reader over the view. Readers are cheap and independent, one per walk.
	 *
	 * @return a new reader
	 */
	@Nonnull
	public Reader reader() {
		return new Reader();
	}

	/**
	 * Copies the impacts into a fresh array, in record order.
	 *
	 * @return the impacts
	 */
	@Nonnull
	public byte[] toArray() {
		if (this.flat != null) {
			return this.flat.clone();
		}
		final byte[] result = new byte[this.size];
		int to = 0;
		//noinspection DataFlowIssue - exactly one of `flat` and `chunks` is set
		for (final byte[] chunk : this.chunks) {
			System.arraycopy(chunk, 0, result, to, chunk.length);
			to += chunk.length;
		}
		return result;
	}

	@Override
	public String toString() {
		return Arrays.toString(toArray());
	}

	/**
	 * A cursor over the impacts of one {@link ImpactView}. It remembers the chunk it read last, so ascending reads -
	 * the access of a merge that visits every posting in order - cost one bounds check and one array read each.
	 *
	 * Not thread-safe: a reader belongs to one walk.
	 */
	@NotThreadSafe
	public final class Reader {
		/**
		 * Index of the chunk read last; always `0` over a flat view.
		 */
		private int chunkIndex;
		/**
		 * Record-order index of the first impact of {@link #chunk}.
		 */
		private int chunkStart;
		/**
		 * The chunk read last - the whole array of a flat view.
		 */
		@Nonnull private byte[] chunk;

		/**
		 * Opens the reader at the first impact.
		 */
		private Reader() {
			//noinspection DataFlowIssue - exactly one of `flat` and `chunks` is set
			this.chunk = ImpactView.this.flat != null ? ImpactView.this.flat : ImpactView.this.chunks[0];
		}

		/**
		 * Returns the impact of the record at the passed index of the bucket's record order.
		 *
		 * @param index the record's index, `0..`{@link #size()}` - 1`
		 * @return the impact byte, unsigned
		 */
		public byte impactAt(int index) {
			final int offset = index - this.chunkStart;
			if (offset >= 0 && offset < this.chunk.length) {
				return this.chunk[offset];
			}
			return seek(index);
		}

		/**
		 * Moves the reader to the chunk holding the passed index and reads it - the slow path of
		 * {@link #impactAt(int)}, taken once per chunk boundary on an ascending walk.
		 *
		 * @param index the record's index
		 * @return the impact byte
		 */
		private byte seek(int index) {
			Assert.isPremiseValid(
				index >= 0 && index < ImpactView.this.size,
				() -> "Impact index " + index + " is outside 0.." + (ImpactView.this.size - 1) + "!"
			);
			final byte[][] theChunks = ImpactView.this.chunks;
			// a flat view always takes the fast path for an index inside the bounds checked above
			Assert.isPremiseValid(theChunks != null, "A flat impact view has a single chunk!");
			if (index < this.chunkStart) {
				this.chunkIndex = 0;
				this.chunkStart = 0;
				this.chunk = theChunks[0];
			}
			while (index >= this.chunkStart + this.chunk.length) {
				this.chunkStart += this.chunk.length;
				this.chunk = theChunks[++this.chunkIndex];
			}
			return this.chunk[index - this.chunkStart];
		}
	}

}
