package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression tests for the bulk-merge path added when porting upstream RoaringBitmap PR #840
 * ("Avoid O(N^2) operations in some cases") onto the copy-on-write {@link PersistentRoaringBitmap}.
 *
 * The in-place {@link PersistentRoaringBitmap#or}, {@link PersistentRoaringBitmap#xor} and
 * {@link PersistentRoaringBitmap#naivelazyor} switch from a per-key insert/remove loop to a single
 * suffix rebuild ({@code mergeBulk}) the moment the receiver's structure must change. That rebuild
 * is the copy-on-write landmine: it must borrow source-only containers by structural sharing (never
 * corrupting the source), clone a shared receiver container before an in-place overlap op (never
 * corrupting a cloned peer), and rebuild the parallel {@code shared[]} flag array in lockstep (never
 * indexing out of bounds). These tests force the mid-stream divergence together with a {@code clone()}
 * peer — the combination the pre-existing sharing tests do not exercise, because they only cover
 * disjoint tail-append and full-overlap sharing through the static operators.
 */
@DisplayName("mergeBulk (PR #840) copy-on-write correctness")
public class MergeBulkCopyOnWriteTest {

	/**
	 * In-place OR with interleaved keys, entered while the receiver is a frozen, fully-shared clone
	 * peer. The very first comparison is a source-only insert at position 0 (`A`'s smallest key is
	 * greater than `B`'s), so {@code mergeBulk} runs against a still-frozen receiver — the hardest
	 * copy-on-write case. Covers source-only borrow, overlap clone-before-op and receiver-only carry
	 * in one pass.
	 */
	@Test
	public void orInterleavedFrozenReceiverPreservesPeerAndSource() {
		// keys interleave and A's first key (10) > B's first key (5) -> mergeBulk fires at index 0
		final int[] aValues = values(new int[]{10, 20}, 3);          // chunks 10, 20
		final int[] bValues = concat(values(new int[]{5, 15, 25}, 3), // disjoint chunks 5, 15, 25
			values(new int[]{10}, 7));                                // overlapping chunk 10, other lows
		final PersistentRoaringBitmap a = bitmapOf(aValues);
		final PersistentRoaringBitmap b = bitmapOf(bValues);

		final PersistentRoaringBitmap peer = a.clone();               // a becomes frozen + all-shared
		final int[] peerBefore = peer.toArray();
		final int[] bBefore = b.toArray();

		a.or(b);

		assertArrayEquals(union(aValues, bValues), a.toArray(), "in-place or produced a wrong result");
		assertArrayEquals(peerBefore, peer.toArray(), "clone peer corrupted by in-place or");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by in-place or");
	}

	/**
	 * In-place XOR with interleaved keys and a shared clone peer, where the overlapping chunk holds a
	 * different value in each operand so the symmetric difference keeps it.
	 */
	@Test
	public void xorInterleavedPreservesPeerAndSource() {
		final int[] aValues = concat(values(new int[]{0, 10, 20}, 3), values(new int[]{10}, 50));
		final int[] bValues = values(new int[]{5, 10, 15, 25}, 3);
		final PersistentRoaringBitmap a = bitmapOf(aValues);
		final PersistentRoaringBitmap b = bitmapOf(bValues);

		final PersistentRoaringBitmap peer = a.clone();
		final int[] peerBefore = peer.toArray();
		final int[] bBefore = b.toArray();

		a.xor(b);

		assertArrayEquals(symmetricDifference(aValues, bValues), a.toArray(), "in-place xor wrong result");
		assertArrayEquals(peerBefore, peer.toArray(), "clone peer corrupted by in-place xor");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by in-place xor");
	}

	/**
	 * In-place XOR where an overlapping chunk holds the identical container in both operands, so the
	 * overlap cancels to an empty container. This is the {@code mergeBulk} cancelled-pair entry point
	 * (`dst = pos1`, `left = pos1 + 1`) that must drop the emptied chunk while keeping both peers
	 * intact.
	 */
	@Test
	public void xorCancelledPairDropsEmptiedChunkAndPreservesPeers() {
		// chunk 10 identical in both -> ixor empties it; it must vanish from the result
		final int[] aValues = values(new int[]{10, 20, 30}, 1);
		final int[] bValues = values(new int[]{10, 25}, 1);
		final PersistentRoaringBitmap a = bitmapOf(aValues);
		final PersistentRoaringBitmap b = bitmapOf(bValues);

		final PersistentRoaringBitmap peer = a.clone();
		final int[] peerBefore = peer.toArray();
		final int[] bBefore = b.toArray();

		a.xor(b);

		final int[] expected = symmetricDifference(aValues, bValues);
		assertArrayEquals(expected, a.toArray(), "cancelled-pair xor produced a wrong result");
		// the identical chunk-10 value must not survive the symmetric difference
		assertFalse(a.contains(10 << 16 | 0), "emptied chunk was not dropped");
		assertArrayEquals(peerBefore, peer.toArray(), "clone peer corrupted by cancelled-pair xor");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by cancelled-pair xor");
	}

	/**
	 * In-place naive lazy OR (promotes overlaps to bitmap containers, exercising the
	 * {@code MERGE_LAZY_OR} branch), followed by {@code repairAfterLazy()}, with interleaved keys and a
	 * shared clone peer.
	 */
	@Test
	public void naiveLazyOrInterleavedPreservesPeerAndSource() {
		final int[] aValues = concat(values(new int[]{10, 20}, 5), values(new int[]{10}, 40));
		final int[] bValues = values(new int[]{5, 10, 15, 25}, 5);
		final PersistentRoaringBitmap a = bitmapOf(aValues);
		final PersistentRoaringBitmap b = bitmapOf(bValues);

		final PersistentRoaringBitmap peer = a.clone();
		final int[] peerBefore = peer.toArray();
		final int[] bBefore = b.toArray();

		a.naivelazyor(b);
		a.repairAfterLazy();

		assertArrayEquals(union(aValues, bValues), a.toArray(), "in-place naivelazyor wrong result");
		assertArrayEquals(peerBefore, peer.toArray(), "clone peer corrupted by in-place naivelazyor");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by in-place naivelazyor");
	}

	/**
	 * The untouched instance {@link PersistentRoaringBitmap#lazyor} (upstream PR #840 deliberately left
	 * it on the per-key path) must keep producing correct unions with interleaved keys and preserve a
	 * shared clone peer.
	 */
	@Test
	public void lazyOrInterleavedStillCorrectAndPreservesPeers() {
		final int[] aValues = values(new int[]{10, 20}, 4);
		final int[] bValues = values(new int[]{5, 15, 25}, 4);
		final PersistentRoaringBitmap a = bitmapOf(aValues);
		final PersistentRoaringBitmap b = bitmapOf(bValues);

		final PersistentRoaringBitmap peer = a.clone();
		final int[] peerBefore = peer.toArray();
		final int[] bBefore = b.toArray();

		a.lazyor(b);
		a.repairAfterLazy();

		assertArrayEquals(union(aValues, bValues), a.toArray(), "in-place lazyor wrong result");
		assertArrayEquals(peerBefore, peer.toArray(), "clone peer corrupted by in-place lazyor");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by in-place lazyor");
	}

	/**
	 * Large fully-interleaved OR: `A` on even chunks, `B` on odd chunks, thousands of keys. This is the
	 * O(N) path {@code mergeBulk} exists to provide and where an undersized rebuilt {@code shared[]}
	 * would throw {@link ArrayIndexOutOfBoundsException}. Runs against a frozen clone peer to force the
	 * shared-flag rebuild across the whole suffix.
	 */
	@Test
	public void largeInterleavedOrIsCorrectAndDoesNotOverflowSharedFlags() {
		final int chunks = 2000;
		final int[] evenChunks = new int[chunks];
		final int[] oddChunks = new int[chunks];
		for (int i = 0; i < chunks; i++) {
			evenChunks[i] = 2 * i;
			oddChunks[i] = 2 * i + 1;
		}
		final int[] aValues = values(evenChunks, 1);
		final int[] bValues = values(oddChunks, 1);
		final PersistentRoaringBitmap a = bitmapOf(aValues);
		final PersistentRoaringBitmap b = bitmapOf(bValues);

		final PersistentRoaringBitmap peer = a.clone();
		final int[] peerBefore = peer.toArray();
		final int[] bBefore = b.toArray();

		a.or(b);

		assertArrayEquals(union(aValues, bValues), a.toArray(), "large interleaved or wrong result");
		assertArrayEquals(peerBefore, peer.toArray(), "clone peer corrupted by large interleaved or");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by large interleaved or");
	}

	/**
	 * In-place OR entered on an _owned_ (never-cloned) receiver whose interleaved keys force the
	 * source-only branch to invoke {@code mergeBulk}, which borrows a source-only chunk from `b` by
	 * structural sharing. {@code mergeBulk} must raise the shared flag on _both_ operands for
	 * that borrowed chunk, so a later write to either side clones on demand and leaves the other intact.
	 * The source `b` is mutated first — while `a` still aliases the borrowed container — so the check
	 * fails if {@code mergeBulk} forgot to flag the borrowed chunk shared on the source side.
	 */
	@Test
	public void orBorrowedChunkIsolatesBothOperandsUnderLaterMutation() {
		// a's first key (10) > b's first key (5) -> or() takes the source-only branch into mergeBulk;
		// chunk 5 (and 25) exist only in b and are borrowed into a by structural sharing.
		final int[] aValues = values(new int[]{10, 20}, 3);
		final int[] bValues = values(new int[]{5, 10, 25}, 3);
		final PersistentRoaringBitmap a = bitmapOf(aValues);   // owned: never cloned
		final PersistentRoaringBitmap b = bitmapOf(bValues);
		final int[] bBefore = b.toArray();

		a.or(b);

		assertArrayEquals(union(aValues, bValues), a.toArray(), "owned-receiver or produced a wrong result");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by owned-receiver or");

		final int[] aAfterOp = a.toArray();
		// mutate the SOURCE inside the borrowed chunk 5 while a still aliases it: a must stay intact
		b.add((5 << 16) | 888);
		assertArrayEquals(aAfterOp, a.toArray(), "receiver corrupted by mutating source in a borrowed chunk");

		final int[] bAfterSourceMutation = b.toArray();
		// mutate the RECEIVER inside the same chunk: the source must stay intact
		a.add((5 << 16) | 999);
		assertArrayEquals(bAfterSourceMutation, b.toArray(), "source corrupted by mutating receiver in a borrowed chunk");
	}

	/**
	 * In-place XOR counterpart of {@link #orBorrowedChunkIsolatesBothOperandsUnderLaterMutation()}: an
	 * owned receiver with interleaved keys enters {@code mergeBulk} through the source-only branch, the
	 * overlapping chunk carries distinct values so its symmetric difference survives, and a source-only
	 * chunk is borrowed. Mutating the source first, then the receiver, must leave the other side intact.
	 */
	@Test
	public void xorBorrowedChunkIsolatesBothOperandsUnderLaterMutation() {
		final int[] aValues = values(new int[]{10, 20}, 3);
		final int[] bValues = concat(values(new int[]{5, 25}, 3), values(new int[]{10}, 7));
		final PersistentRoaringBitmap a = bitmapOf(aValues);   // owned: never cloned
		final PersistentRoaringBitmap b = bitmapOf(bValues);
		final int[] bBefore = b.toArray();

		a.xor(b);

		assertArrayEquals(symmetricDifference(aValues, bValues), a.toArray(), "owned-receiver xor wrong result");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by owned-receiver xor");

		final int[] aAfterOp = a.toArray();
		b.add((5 << 16) | 888);
		assertArrayEquals(aAfterOp, a.toArray(), "receiver corrupted by mutating source in a borrowed chunk");

		final int[] bAfterSourceMutation = b.toArray();
		a.add((5 << 16) | 999);
		assertArrayEquals(bAfterSourceMutation, b.toArray(), "source corrupted by mutating receiver in a borrowed chunk");
	}

	/**
	 * In-place naive lazy OR on an owned receiver whose interleaved keys enter {@code mergeBulk} through
	 * the source-only branch, promoting the overlapping chunk to a bitmap accumulator and borrowing a
	 * source-only chunk. After the mandatory {@link PersistentRoaringBitmap#repairAfterLazy()} the union
	 * must be correct, and mutating either operand in the borrowed chunk must leave the other intact.
	 */
	@Test
	public void naiveLazyOrBorrowedChunkIsolatesBothOperandsUnderLaterMutation() {
		final int[] aValues = concat(values(new int[]{10, 20}, 5), values(new int[]{10}, 40));
		final int[] bValues = values(new int[]{5, 10, 25}, 5);
		final PersistentRoaringBitmap a = bitmapOf(aValues);   // owned: never cloned
		final PersistentRoaringBitmap b = bitmapOf(bValues);
		final int[] bBefore = b.toArray();

		a.naivelazyor(b);
		a.repairAfterLazy();

		assertArrayEquals(union(aValues, bValues), a.toArray(), "owned-receiver naivelazyor wrong result");
		assertArrayEquals(bBefore, b.toArray(), "source operand corrupted by owned-receiver naivelazyor");

		final int[] aAfterOp = a.toArray();
		b.add((5 << 16) | 888);
		assertArrayEquals(aAfterOp, a.toArray(), "receiver corrupted by mutating source in a borrowed chunk");

		final int[] bAfterSourceMutation = b.toArray();
		a.add((5 << 16) | 999);
		assertArrayEquals(bAfterSourceMutation, b.toArray(), "source corrupted by mutating receiver in a borrowed chunk");
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers: bitmap construction and independent set-algebra oracle
	// ---------------------------------------------------------------------------------------------

	/**
	 * Builds a bitmap from an explicit list of values.
	 */
	private static PersistentRoaringBitmap bitmapOf(final int[] values) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (final int value : values) {
			bitmap.add(value);
		}
		return bitmap;
	}

	/**
	 * Produces `count` values inside each of the given high-16-bit chunk keys, so each chunk becomes a
	 * distinct container.
	 *
	 * @param chunks high-16-bit chunk keys to populate
	 * @param count  number of consecutive low values written into every chunk
	 * @return the flattened value array
	 */
	private static int[] values(final int[] chunks, final int count) {
		final int[] out = new int[chunks.length * count];
		int pos = 0;
		for (final int chunk : chunks) {
			for (int low = 0; low < count; low++) {
				out[pos++] = (chunk << 16) | low;
			}
		}
		return out;
	}

	/**
	 * Concatenates two value arrays.
	 */
	private static int[] concat(final int[] first, final int[] second) {
		final int[] out = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, out, first.length, second.length);
		return out;
	}

	/**
	 * Independent union oracle: sorted distinct union of the two value sets.
	 */
	private static int[] union(final int[] a, final int[] b) {
		final TreeSet<Integer> set = new TreeSet<>(Integer::compareUnsigned);
		for (final int value : a) {
			set.add(value);
		}
		for (final int value : b) {
			set.add(value);
		}
		return toIntArray(set);
	}

	/**
	 * Independent symmetric-difference oracle: values present in exactly one of the two sets.
	 */
	private static int[] symmetricDifference(final int[] a, final int[] b) {
		final TreeSet<Integer> set = new TreeSet<>(Integer::compareUnsigned);
		for (final int value : a) {
			set.add(value);
		}
		for (final int value : b) {
			if (!set.remove(value)) {
				set.add(value);
			}
		}
		return toIntArray(set);
	}

	/**
	 * Converts a sorted integer set to a primitive array preserving order.
	 */
	private static int[] toIntArray(final TreeSet<Integer> set) {
		final int[] out = new int[set.size()];
		int pos = 0;
		for (final int value : set) {
			out[pos++] = value;
		}
		return out;
	}
}
