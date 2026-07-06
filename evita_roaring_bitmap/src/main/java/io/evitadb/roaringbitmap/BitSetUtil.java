package io.evitadb.roaringbitmap;

import static java.lang.Long.numberOfTrailingZeros;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Static helpers that bridge {@link PersistentRoaringBitmap} and the flat bit representations of
 * {@link java.util.BitSet}: `long[]` word arrays, `byte[]`, and {@link java.nio.ByteBuffer}.
 *
 * All conversions follow {@link BitSet}'s little-endian word convention: value `v` lives in word
 * `v >>> 6` at bit `v & 63`, so the `bitmapOf` / `toLongArray` pair round-trips through
 * {@link BitSet#toLongArray()} and {@link BitSet#valueOf(long[])} without reinterpreting bits. The
 * `bitmapOf` / `bitsetOf` families materialise a whole structure, whereas the {@link ByteBuffer}
 * overloads stream an uncompressed bitmap block-by-block to minimise allocation, optionally reusing
 * a caller-supplied 8 KB word buffer.
 */
public class BitSetUtil {

	/**
	 * Number of 64-bit words spanning one Roaring chunk (1024 words = 65536 bits), i.e. a single
	 * container's key range. Word arrays and byte buffers are split into blocks of this length so each
	 * block maps to at most one container during conversion.
	 */
	public static final int BLOCK_LENGTH = BitmapContainer.MAX_CAPACITY / Long.SIZE;

	/**
	 * Converts a {@link PersistentRoaringBitmap} into a {@link BitSet}, setting bit `v` for each
	 * value `v` present in the bitmap.
	 *
	 * Equivalent to {@code BitSet.valueOf(BitSetUtil.toLongArray(bitmap))}.
	 *
	 * @param bitmap source bitmap
	 * @return a new bit set holding the same values
	 */
	@Nonnull
	public static BitSet bitsetOf(@Nonnull final PersistentRoaringBitmap bitmap) {
		return BitSet.valueOf(toLongArray(bitmap));
	}

	/**
	 * Converts a {@link PersistentRoaringBitmap} into a {@link BitSet} by setting bits directly,
	 * avoiding the intermediate `long[]` that {@link #bitsetOf} allocates.
	 *
	 * @param bitmap source bitmap
	 * @return a new bit set holding the same values
	 * @throws IllegalArgumentException if the bitmap holds a value whose signed 32-bit form is
	 *                                  negative and thus outside {@link BitSet}'s index range
	 */
	@Nonnull
	public static BitSet bitsetOfWithoutCopy(@Nonnull final PersistentRoaringBitmap bitmap) {
		if (bitmap.isEmpty()) {
			return new BitSet(0);
		}
		final int last = bitmap.last();
		if (last < 0) {
			throw new IllegalArgumentException("bitmap has negative bits set");
		}
		final BitSet bitSet = new BitSet(last);
		bitmap.forEach((IntConsumer) bitSet::set);
		return bitSet;
	}

	/**
	 * Serialises a {@link PersistentRoaringBitmap} into a little-endian `byte[]`, matching
	 * {@link BitSet#toByteArray()}.
	 *
	 * @param bitmap source bitmap
	 * @return a new little-endian byte array where byte `n` carries bits `8*n .. 8*n+7`
	 */
	@Nonnull
	public static byte[] toByteArray(@Nonnull final PersistentRoaringBitmap bitmap) {
		final long[] words = toLongArray(bitmap);
		final ByteBuffer buffer =
			ByteBuffer.allocate(words.length * Long.SIZE).order(ByteOrder.LITTLE_ENDIAN);
		buffer.asLongBuffer().put(words);
		return buffer.array();
	}

	/**
	 * Packs a {@link PersistentRoaringBitmap} into a `long[]` using {@link BitSet}'s little-endian
	 * word layout, matching {@link BitSet#toLongArray()}: value `v` is bit `v & 63` of word
	 * `v >>> 6`. The result is trimmed to the highest set value, so trailing all-zero words are
	 * dropped and an empty bitmap yields a zero-length array.
	 *
	 * Runs in O(words): each container's dense bitmap is copied into the target array in a single
	 * pass, skipping key ranges that hold no container.
	 *
	 * @param bitmap source bitmap
	 * @return a new little-endian word array sized to the highest set value
	 * @throws IllegalArgumentException if the bitmap holds a value whose signed 32-bit form is
	 *                                  negative and thus outside {@link BitSet}'s index range
	 */
	@Nonnull
	public static long[] toLongArray(@Nonnull final PersistentRoaringBitmap bitmap) {
		if (bitmap.isEmpty()) {
			return new long[0];
		}

		final int last = bitmap.last();
		if (last < 0) {
			throw new IllegalArgumentException("bitmap has negative bits set");
		}
		final int lastBit = Math.max(last, Long.SIZE);
		final int remainder = lastBit % Long.SIZE;
		final int numBits = remainder > 0 ? lastBit - remainder : lastBit;
		final int wordsInUse = numBits / Long.SIZE + 1;
		final long[] words = new long[wordsInUse];

		final ContainerPointer pointer = bitmap.getContainerPointer();
		final int numContainers = Math.max(words.length / BLOCK_LENGTH, 1);
		int position = 0;
		for (int i = 0; i <= numContainers; i++) {
			final char key = Util.lowbits(i);
			if (key == pointer.key()) {
				final Container container = Objects.requireNonNull(
					pointer.getContainer(),
					"Container must be present when its key matches the pointer key."
				);
				final int remaining = wordsInUse - position;
				final int length = Math.min(BLOCK_LENGTH, remaining);
				if (container instanceof BitmapContainer) {
					((BitmapContainer) container).copyBitmapTo(words, position, length);
				} else {
					container.copyBitmapTo(words, position);
				}
				position += length;
				pointer.advance();
				if (pointer.getContainer() == null) {
					break;
				}
			} else {
				position += BLOCK_LENGTH;
			}
		}
		assert pointer.getContainer() == null;
		assert position == wordsInUse;
		return words;
	}

	/**
	 * Creates array container's content char buffer.
	 *
	 * @param from        first value of the range
	 * @param to          last value of the range
	 * @param cardinality new buffer cardinality, expected to be less than 4096 and more than present
	 *                    values in given bitmap
	 * @param words       bitmap
	 * @return array container's content char buffer
	 */
	@Nonnull
	public static char[] arrayContainerBufferOf(
		final int from, final int to, final int cardinality, @Nonnull final long[] words) {
		return arrayContainerBufferOf(from, to, new char[cardinality], words);
	}

	/**
	 * Creates array container's content char buffer.
	 *
	 * @param from   first value of the range
	 * @param to     last value of the range
	 * @param buffer new buffer, expected to have size less than 4096 and more than present
	 *               values in given bitmap
	 * @param words  bitmap
	 * @return array container's content char buffer - the same as {@code buffer}
	 */
	@Nonnull
	public static char[] arrayContainerBufferOf(
		final int from, final int to, @Nonnull final char[] buffer, @Nonnull final long[] words) {
		// precondition: cardinality is max 4096
		int base = 0;
		int pos = 0;
		for (int i = from; i < to; i++) {
			long word = words[i];
			while (word != 0L) {
				buffer[pos++] = (char) (base + numberOfTrailingZeros(word));
				word &= (word - 1);
			}
			base += 64;
		}
		return buffer;
	}

	@Nonnull
	private static ArrayContainer arrayContainerOf(
		final int from, final int to, final int cardinality, @Nonnull final long[] words) {
		return new ArrayContainer(arrayContainerBufferOf(from, to, cardinality, words));
	}

	/**
	 * Generate a PersistentRoaringBitmap out of a BitSet
	 *
	 * @param bitSet original bitset (will not be modified)
	 * @return roaring bitmap equivalent to BitSet
	 */
	@Nonnull
	public static PersistentRoaringBitmap bitmapOf(@Nonnull final BitSet bitSet) {
		return bitmapOf(bitSet.toLongArray());
	}

	/**
	 * Generate a PersistentRoaringBitmap out of a long[], each long using little-endian representation of its
	 * bits
	 *
	 * @param words array of longs (will not be modified)
	 * @return roaring bitmap
	 * @see BitSet#toLongArray() for an equivalent
	 */
	@Nonnull
	public static PersistentRoaringBitmap bitmapOf(@Nonnull final long[] words) {
		// split long[] into blocks.
		// each block becomes a single container, if any bit is set
		final PersistentRoaringBitmap ans = new PersistentRoaringBitmap();
		int containerIndex = 0;
		for (int from = 0; from < words.length; from += BLOCK_LENGTH) {
			final int to = Math.min(from + BLOCK_LENGTH, words.length);
			final int blockCardinality = cardinality(from, to, words);
			if (blockCardinality > 0) {
				ans.highLowContainer.insertNewKeyValueAt(
					containerIndex++,
					Util.highbits(from * Long.SIZE),
					BitSetUtil.containerOf(from, to, blockCardinality, words)
				);
			}
		}
		return ans;
	}

	/**
	 * Builds a {@link PersistentRoaringBitmap} from an uncompressed little-endian bitmap held in a
	 * {@link ByteBuffer}, allocating a fresh scratch buffer per call.
	 *
	 * Prefer {@link #bitmapOf(ByteBuffer, long[])} on hot paths to reuse the 8 KB scratch buffer.
	 *
	 * @param bb uncompressed little-endian bitmap; left unmodified (a slice is taken internally)
	 * @return a new roaring bitmap holding the buffer's set bits
	 */
	@Nonnull
	public static PersistentRoaringBitmap bitmapOf(@Nonnull final ByteBuffer bb) {
		return bitmapOf(bb, new long[BLOCK_LENGTH]);
	}

	/**
	 * Builds a {@link PersistentRoaringBitmap} from an uncompressed little-endian bitmap, minimising
	 * allocation by streaming the buffer one {@link #BLOCK_LENGTH}-word block at a time and emitting a
	 * container only for blocks that carry set bits.
	 *
	 * The scratch `wordsBuffer` lets callers avoid the 8 KB per-call allocation; no reference to it is
	 * retained, so a single buffer may be cached (e.g. in a `ThreadLocal`) and reused across calls.
	 *
	 * @param bb          uncompressed little-endian bitmap; left unmodified (a slice is taken
	 *                    internally)
	 * @param wordsBuffer reusable scratch array; its length must equal {@link #BLOCK_LENGTH}
	 * @return a new roaring bitmap holding the buffer's set bits
	 * @throws IllegalArgumentException if `wordsBuffer.length` differs from {@link #BLOCK_LENGTH}
	 */
	@Nonnull
	public static PersistentRoaringBitmap bitmapOf(
		@Nonnull ByteBuffer bb, @Nonnull final long[] wordsBuffer) {

		if (wordsBuffer.length != BLOCK_LENGTH) {
			throw new IllegalArgumentException("wordsBuffer length should be " + BLOCK_LENGTH);
		}

		bb = bb.slice().order(ByteOrder.LITTLE_ENDIAN);
		final PersistentRoaringBitmap ans = new PersistentRoaringBitmap();

		// split buffer into blocks of long[]
		int containerIndex = 0;
		int blockLength = 0, blockCardinality = 0, offset = 0;
		long word;
		while (bb.remaining() >= 8) {
			word = bb.getLong();

			// Add read long to block
			wordsBuffer[blockLength++] = word;
			blockCardinality += Long.bitCount(word);

			// When block is full, add block to bitmap
			if (blockLength == BLOCK_LENGTH) {
				// Each block becomes a single container, if any bit is set
				if (blockCardinality > 0) {
					ans.highLowContainer.insertNewKeyValueAt(
						containerIndex++,
						Util.highbits(offset),
						BitSetUtil.containerOf(0, blockLength, blockCardinality, wordsBuffer)
					);
				}
        /*
           Offset can overflow when bitsets size is more than Integer.MAX_VALUE - 64
           It's harmless though, as it will happen after the last block is added
        */
				offset += (BLOCK_LENGTH * Long.SIZE);
				blockLength = blockCardinality = 0;
			}
		}

		if (bb.remaining() > 0) {
			// Read remaining (less than 8) bytes
			// We can do this in while loop also, it will probably slow things down a bit though
			word = 0;
			for (int remaining = bb.remaining(), j = 0; j < remaining; j++) {
				word |= (bb.get() & 0xffL) << (8 * j);
			}

			// Add last word to block, only if any bit is set
			if (word != 0) {
				wordsBuffer[blockLength++] = word;
				blockCardinality += Long.bitCount(word);
			}
		}

		// Add block to map, if any bit is set
		if (blockCardinality > 0) {
			ans.highLowContainer.insertNewKeyValueAt(
				containerIndex,
				Util.highbits(offset),
				BitSetUtil.containerOf(0, blockLength, blockCardinality, wordsBuffer)
			);
		}
		return ans;
	}

	private static int cardinality(final int from, final int to, @Nonnull final long[] words) {
		int sum = 0;
		for (int i = from; i < to; i++) {
			sum += Long.bitCount(words[i]);
		}
		return sum;
	}

	@Nonnull
	private static Container containerOf(
		final int from, final int to, final int blockCardinality, @Nonnull final long[] words) {
		// find the best container available
		if (blockCardinality <= ArrayContainer.DEFAULT_MAX_SIZE) {
			// containers with DEFAULT_MAX_SIZE or less integers should be
			// ArrayContainers
			return arrayContainerOf(from, to, blockCardinality, words);
		} else {
			// otherwise use bitmap container
			final long[] container = new long[BLOCK_LENGTH];
			System.arraycopy(words, from, container, 0, to - from);
			return new BitmapContainer(container, blockCardinality);
		}
	}

	/**
	 * Compares a PersistentRoaringBitmap and a BitSet. They are equal if and only if they contain the same set
	 * of integers.
	 *
	 * @param bitset first object to be compared
	 * @param bitmap second object to be compared
	 * @return whether they are equals
	 */
	public static boolean equals(
		@Nonnull final BitSet bitset, @Nonnull final PersistentRoaringBitmap bitmap) {
		if (bitset.cardinality() != bitmap.getCardinality()) {
			return false;
		}
		final IntIterator it = bitmap.getIntIterator();
		while (it.hasNext()) {
			final int val = it.next();
			if (!bitset.get(val)) {
				return false;
			}
		}
		return true;
	}
}
