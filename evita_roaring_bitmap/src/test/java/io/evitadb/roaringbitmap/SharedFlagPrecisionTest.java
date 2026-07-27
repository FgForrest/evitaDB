package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how precisely the static binary operations of {@link PersistentRoaringBitmap} raise
 * copy-on-write flags, which is deliberately different on the two sides of the operation.
 *
 * A `shared` flag is a promise to clone before the next in-place write. Raising it on a chunk nobody
 * co-owns is safe but not free: the clone still happens, and for a bitmap chunk that is an 8 KiB copy
 * bought for nothing.
 *
 * **The result** is a brand-new bitmap, reachable only by the caller until it is published, so its
 * flags are tracked per slot: a chunk lent from one operand is flagged, a chunk recombined from both
 * into a private container ({@link ContainerBinaryOpFreshnessTest} pins that privateness) is not.
 *
 * **The operands** are marked wholesale instead, and that is on purpose rather than an oversight — see
 * `markAllShared`. A live bitmap can be an operand of two concurrent queries, and the flag array is
 * written unsynchronised; a full fill converges on the conservative value under a lost update, while
 * sparse writes would leave behind the unsafe one. The operand-side tests below therefore assert the
 * *conservative* behaviour deliberately, and tightening it is not a free optimisation.
 */
@DisplayName("Static or/xor/andNot flag result chunks precisely and operands conservatively")
public class SharedFlagPrecisionTest {

	/**
	 * Fills the given chunk densely enough that it is held as a {@link BitmapContainer}. That matters
	 * for the clone census: a bitmap container absorbs {@link Container#add(char)} in place and returns
	 * itself, so any change of container identity across a write is a copy-on-write clone and nothing
	 * else.
	 */
	private static void fillDenseChunk(
		final PersistentRoaringBitmap bitmap, final int chunkKey, final int offset
	) {
		final int base = chunkKey << 16;
		for (int i = 0; i < ArrayContainer.DEFAULT_MAX_SIZE + 256; i++) {
			bitmap.add(base + offset + i * 3);
		}
	}

	/**
	 * Builds a bitmap holding one dense chunk per requested key.
	 */
	private static PersistentRoaringBitmap denseChunks(final int offset, final int... chunkKeys) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (final int key : chunkKeys) {
			fillDenseChunk(bitmap, key, offset);
		}
		for (int i = 0; i < bitmap.highLowContainer.size(); i++) {
			assertTrue(
				bitmap.getContainerAtIndex(i) instanceof BitmapContainer,
				"fixture drifted off the bitmap-container shape at slot " + i);
		}
		return bitmap;
	}

	/**
	 * Snapshots container identities so a later write pass can be audited for clones.
	 */
	private static Container[] identities(final PersistentRoaringBitmap bitmap) {
		final Container[] snapshot = new Container[bitmap.highLowContainer.size()];
		for (int i = 0; i < snapshot.length; i++) {
			snapshot[i] = bitmap.getContainerAtIndex(i);
		}
		return snapshot;
	}

	/**
	 * Writes one fresh value into every chunk and reports which slots had their container replaced,
	 * i.e. where the write pass had to pay for a copy-on-write clone.
	 */
	private static boolean[] clonesOnWritePass(final PersistentRoaringBitmap bitmap) {
		final Container[] before = identities(bitmap);
		for (int i = 0; i < before.length; i++) {
			// a value guaranteed absent from the fixtures, which stride by 3 from a small offset
			bitmap.add((bitmap.getKeyAtIndex(i) << 16) + 0xFFFF);
		}
		final Container[] after = identities(bitmap);
		final boolean[] cloned = new boolean[before.length];
		for (int i = 0; i < before.length; i++) {
			cloned[i] = before[i] != after[i];
		}
		return cloned;
	}

	/**
	 * Asserts every live slot of the bitmap carries the expected flag.
	 */
	private static void assertAllFlags(
		final PersistentRoaringBitmap bitmap, final boolean expected, final String where
	) {
		for (int i = 0; i < bitmap.highLowContainer.size(); i++) {
			final int slot = i;
			assertEquals(
				expected, bitmap.isShared(i),
				() -> where + ": slot " + slot + " (chunk " + (int) bitmap.getKeyAtIndex(slot) + ")");
		}
	}

	@Test
	@DisplayName("or over fully overlapping chunks aliases nothing, so the result flags nothing")
	public void orOverFullyOverlappingChunksFlagsNothingInTheResult() {
		final PersistentRoaringBitmap left = denseChunks(1, 0, 1, 2);
		final PersistentRoaringBitmap right = denseChunks(2, 0, 1, 2);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.or(left, right);

		// every result chunk was recombined from both operands, so all three containers are private
		assertAllFlags(result, false, "or result");
		// the operands are still marked wholesale - deliberately conservative, see the class comment
		assertAllFlags(left, true, "or left operand");
		assertAllFlags(right, true, "or right operand");
	}

	@Test
	@DisplayName("or flags exactly the result chunks carried over from a single operand")
	public void orFlagsOnlyCarriedOverChunks() {
		final PersistentRoaringBitmap left = denseChunks(1, 0, 1);
		final PersistentRoaringBitmap right = denseChunks(2, 1, 2);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.or(left, right);

		assertEquals(3, result.highLowContainer.size());
		// chunk 0 comes from `left` alone and chunk 2 from `right` alone: both are aliased. Chunk 1 is
		// present on both sides and is therefore a freshly combined, privately owned container.
		assertTrue(result.isShared(0), "chunk 0 is aliased from the left operand");
		assertFalse(result.isShared(1), "chunk 1 was recombined and is private");
		assertTrue(result.isShared(2), "chunk 2 is aliased from the right operand");

		assertSame(left.getContainerAtIndex(0), result.getContainerAtIndex(0));
		assertNotSame(left.getContainerAtIndex(1), result.getContainerAtIndex(1));
		assertNotSame(right.getContainerAtIndex(0), result.getContainerAtIndex(1));
		assertSame(right.getContainerAtIndex(1), result.getContainerAtIndex(2));
	}

	@Test
	@DisplayName("xor flags exactly the result chunks carried over from a single operand")
	public void xorFlagsOnlyCarriedOverChunks() {
		final PersistentRoaringBitmap left = denseChunks(1, 0, 1);
		final PersistentRoaringBitmap right = denseChunks(2, 1, 2);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.xor(left, right);

		assertEquals(3, result.highLowContainer.size());
		assertTrue(result.isShared(0), "chunk 0 is aliased from the left operand");
		assertFalse(result.isShared(1), "chunk 1 was recombined and is private");
		assertTrue(result.isShared(2), "chunk 2 is aliased from the right operand");
	}

	@Test
	@DisplayName("andNot flags only the left-only result chunks and never touches the subtrahend")
	public void andNotFlagsOnlyLeftOnlyChunks() {
		final PersistentRoaringBitmap left = denseChunks(1, 0, 1, 2);
		final PersistentRoaringBitmap right = denseChunks(2, 1);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.andNot(left, right);

		assertEquals(3, result.highLowContainer.size());
		assertTrue(result.isShared(0), "chunk 0 has no counterpart and is aliased");
		assertFalse(result.isShared(1), "chunk 1 was recombined and is private");
		assertTrue(result.isShared(2), "chunk 2 has no counterpart and is aliased");

		// andNot never carries a container out of its subtrahend, so it is not marked at all
		assertAllFlags(right, false, "andNot right operand");
	}

	@Test
	@DisplayName("writing to a merged result clones only the chunks it really borrowed")
	public void writingToAMergedResultClonesOnlyBorrowedChunks() {
		// chunks 0 and 3 exist on one side only and are borrowed; chunks 1 and 2 exist on both and are
		// recombined into containers the result owns outright
		final PersistentRoaringBitmap left = denseChunks(1, 0, 1, 2);
		final PersistentRoaringBitmap right = denseChunks(2, 1, 2, 3);

		final PersistentRoaringBitmap result = PersistentRoaringBitmap.or(left, right);
		assertEquals(4, result.highLowContainer.size());

		final boolean[] cloned = clonesOnWritePass(result);
		assertArrayEquals(
			new boolean[]{true, false, false, true}, cloned,
			"a write into the merged result should clone the two borrowed chunks and no others");

		// and the operands the borrowed chunks came from must be untouched by that write
		assertArrayEquals(
			denseChunks(1, 0, 1, 2).toArray(), left.toArray(), "left operand was written through");
		assertArrayEquals(
			denseChunks(2, 1, 2, 3).toArray(), right.toArray(), "right operand was written through");
	}

	@Test
	@DisplayName("re-encoding a chunk hands its slot a private container, so the flag must drop")
	public void reEncodingClearsTheSharedFlag() {
		// a run-shaped chunk plus a chunk that stays a bitmap, so both branches of runOptimize appear
		final PersistentRoaringBitmap source = new PersistentRoaringBitmap();
		source.add(0L, 40_000L);
		fillDenseChunk(source, 1, 1);

		final PersistentRoaringBitmap peer = source.clone();
		assertAllFlags(peer, true, "a fresh clone co-owns every chunk");

		peer.removeRunCompression();
		for (int i = 0; i < peer.highLowContainer.size(); i++) {
			// a chunk that was actually re-encoded holds a container built for this bitmap alone
			final boolean reEncoded = peer.getContainerAtIndex(i) != source.getContainerAtIndex(i);
			assertEquals(
				!reEncoded, peer.isShared(i),
				"slot " + i + " was re-encoded=" + reEncoded + " but is flagged " + peer.isShared(i));
		}

		final PersistentRoaringBitmap second = source.clone();
		second.runOptimize();
		for (int i = 0; i < second.highLowContainer.size(); i++) {
			final boolean reEncoded = second.getContainerAtIndex(i) != source.getContainerAtIndex(i);
			assertEquals(
				!reEncoded, second.isShared(i),
				"slot " + i + " was re-encoded=" + reEncoded + " but is flagged " + second.isShared(i));
		}

		// the bitmap the re-encoding forked away from must be untouched
		assertArrayEquals(source.toArray(), peer.toArray(), "removeRunCompression changed contents");
		assertArrayEquals(source.toArray(), second.toArray(), "runOptimize changed contents");
	}
}
