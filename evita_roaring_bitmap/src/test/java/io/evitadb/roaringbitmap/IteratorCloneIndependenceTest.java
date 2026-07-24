package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests asserting that a cloned int iterator is a genuinely independent cursor — for the
 * reusable {@link IntIteratorFlyweight} / {@link ReverseIntIteratorFlyweight} as well as for the
 * per-bitmap cursors handed out by {@link PersistentRoaringBitmap#getIntIterator()} and
 * {@link PersistentRoaringBitmap#getReverseIntIterator()}.
 *
 * The flyweights are the interesting case: each caches one reusable per-shape char cursor
 * ({@link ReverseArrayContainerCharIterator} and friends) as an instance field so that stepping from
 * chunk to chunk allocates nothing. A shallow `Object.clone()` copies those cursor references, so the
 * fork and its origin end up writing to the SAME cached cursor as soon as either of them crosses a
 * chunk boundary and re-targets it. The tests below drive exactly that: fork first, move the fork
 * across a chunk boundary, then read the origin — whose own position must be untouched.
 *
 * Two properties of the fixtures are load-bearing. Every chunk is a small array chunk, because the
 * collision only bites when both sides are parked on the same cached cursor instance and mixing chunk
 * shapes would hide it. And each chunk holds a DIFFERENT set of low bits, because equal low bits make
 * a clobbered cursor coincidentally emit the value the caller expected.
 */
@DisplayName("Iterator clone independence")
public class IteratorCloneIndependenceTest {

	/**
	 * Builds a bitmap of `chunkCount` small array chunks where chunk `k` holds the three values
	 * `k * 5 + 1 .. k * 5 + 3` — distinct low bits per chunk, so a cursor re-seated onto the wrong
	 * chunk is immediately visible in the emitted value.
	 */
	private static PersistentRoaringBitmap arrayChunkedBitmap(final int chunkCount) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int chunk = 0; chunk < chunkCount; chunk++) {
			for (int offset = 1; offset <= 3; offset++) {
				bitmap.add((chunk << 16) + chunk * 5 + offset);
			}
		}
		return bitmap;
	}

	/**
	 * Value `offset` of chunk `chunk` in the fixture built by {@link #arrayChunkedBitmap(int)}.
	 */
	private static int value(final int chunk, final int offset) {
		return (chunk << 16) + chunk * 5 + offset;
	}

	/**
	 * Reverse flyweight: the fork drains the top chunk, steps down and re-targets the cached array
	 * cursor. The origin is still parked on the top chunk and must keep emitting the top chunk's tail.
	 */
	@Test
	public void reverseFlyweightCloneCrossingChunkLeavesOriginIntact() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(3);
		final ReverseIntIteratorFlyweight origin = new ReverseIntIteratorFlyweight(bitmap);

		assertEquals(value(2, 3), origin.next());

		final PeekableIntIterator fork = origin.clone();
		// drain the rest of the top chunk on the fork, forcing it down into chunk 1
		assertEquals(value(2, 2), fork.next());
		assertEquals(value(2, 1), fork.next());
		assertEquals(value(1, 3), fork.next());

		assertTrue(origin.hasNext(), "origin exhausted by the fork's descent");
		assertEquals(value(2, 2), origin.peekNext(), "fork's descent moved the origin's cursor");
		assertEquals(value(2, 2), origin.next(), "fork's descent moved the origin's cursor");
		assertEquals(value(2, 1), origin.next(), "fork's descent moved the origin's cursor");
	}

	/**
	 * Reverse flyweight, `advanceIfNeeded` entry point: seeking the fork below the top chunk crosses a
	 * boundary through the same cached-cursor re-target as `next()` does.
	 */
	@Test
	public void reverseFlyweightCloneAdvanceIfNeededLeavesOriginIntact() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(3);
		final ReverseIntIteratorFlyweight origin = new ReverseIntIteratorFlyweight(bitmap);

		final PeekableIntIterator fork = origin.clone();
		fork.advanceIfNeeded(value(1, 2));
		assertEquals(value(1, 2), fork.next());

		assertTrue(origin.hasNext(), "origin exhausted by the fork's seek");
		assertEquals(value(2, 3), origin.peekNext(), "fork's seek moved the origin's cursor");
		assertEquals(value(2, 3), origin.next(), "fork's seek moved the origin's cursor");
	}

	/**
	 * Forward flyweight: the same cached-cursor reuse, walking upward instead.
	 */
	@Test
	public void forwardFlyweightCloneCrossingChunkLeavesOriginIntact() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(3);
		final IntIteratorFlyweight origin = new IntIteratorFlyweight(bitmap);

		assertEquals(value(0, 1), origin.next());

		final PeekableIntIterator fork = origin.clone();
		// drain the rest of chunk 0 on the fork, forcing it up into chunk 1
		assertEquals(value(0, 2), fork.next());
		assertEquals(value(0, 3), fork.next());
		assertEquals(value(1, 1), fork.next());

		assertTrue(origin.hasNext(), "origin exhausted by the fork's advance");
		assertEquals(value(0, 2), origin.peekNext(), "fork's advance moved the origin's cursor");
		assertEquals(value(0, 2), origin.next(), "fork's advance moved the origin's cursor");
		assertEquals(value(0, 3), origin.next(), "fork's advance moved the origin's cursor");
	}

	/**
	 * Forward flyweight, `advanceIfNeeded` entry point.
	 */
	@Test
	public void forwardFlyweightCloneAdvanceIfNeededLeavesOriginIntact() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(3);
		final IntIteratorFlyweight origin = new IntIteratorFlyweight(bitmap);

		final PeekableIntIterator fork = origin.clone();
		fork.advanceIfNeeded(value(1, 2));
		assertEquals(value(1, 2), fork.next());

		assertTrue(origin.hasNext(), "origin exhausted by the fork's seek");
		assertEquals(value(0, 1), origin.peekNext(), "fork's seek moved the origin's cursor");
		assertEquals(value(0, 1), origin.next(), "fork's seek moved the origin's cursor");
	}

	/**
	 * The origin must equally be free to move without disturbing an already-forked cursor once BOTH
	 * sides have crossed a boundary — at that point a shallow clone leaves them on one cursor again.
	 */
	@Test
	public void reverseFlyweightBothSidesCrossingStayIndependent() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(4);
		final ReverseIntIteratorFlyweight origin = new ReverseIntIteratorFlyweight(bitmap);

		final PeekableIntIterator fork = origin.clone();
		// fork descends one chunk
		assertEquals(value(3, 3), fork.next());
		assertEquals(value(3, 2), fork.next());
		assertEquals(value(3, 1), fork.next());
		assertEquals(value(2, 3), fork.peekNext());

		// origin descends one chunk too
		assertEquals(value(3, 3), origin.next());
		assertEquals(value(3, 2), origin.next());
		assertEquals(value(3, 1), origin.next());
		assertEquals(value(2, 3), origin.next());

		assertEquals(value(2, 3), fork.next(), "origin's descent moved the fork's cursor");
		assertEquals(value(2, 2), fork.next(), "origin's descent moved the fork's cursor");
	}

	/**
	 * The per-bitmap reverse cursor allocates a fresh char iterator per chunk rather than recycling
	 * cached ones, so a shallow clone is enough — but the independence contract is the same and must
	 * hold across a chunk boundary and after a partial seek.
	 */
	@Test
	public void bitmapReverseIteratorCloneIsIndependent() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(3);
		final PeekableIntIterator origin = bitmap.getReverseIntIterator();

		assertEquals(value(2, 3), origin.next());

		final PeekableIntIterator fork = origin.clone();
		assertEquals(value(2, 2), fork.next());
		assertEquals(value(2, 1), fork.next());
		assertEquals(value(1, 3), fork.next());

		assertTrue(origin.hasNext(), "origin exhausted by the fork's descent");
		assertEquals(value(2, 2), origin.peekNext(), "fork's descent moved the origin's cursor");
		assertEquals(value(2, 2), origin.next(), "fork's descent moved the origin's cursor");
		assertEquals(value(2, 1), origin.next(), "fork's descent moved the origin's cursor");

		// forking after a partial seek must copy the sought-to position, not reset or share it
		final PeekableIntIterator sought = bitmap.getReverseIntIterator();
		sought.advanceIfNeeded(value(1, 2));
		final PeekableIntIterator soughtFork = sought.clone();
		assertEquals(value(1, 2), soughtFork.next());
		assertEquals(value(1, 1), soughtFork.next());
		assertEquals(value(1, 2), sought.next(), "the fork consumed the origin's sought-to value");
	}

	/**
	 * Forward counterpart of {@link #bitmapReverseIteratorCloneIsIndependent()}.
	 */
	@Test
	public void bitmapForwardIteratorCloneIsIndependent() {
		final PersistentRoaringBitmap bitmap = arrayChunkedBitmap(3);
		final PeekableIntIterator origin = bitmap.getIntIterator();

		assertEquals(value(0, 1), origin.next());

		final PeekableIntIterator fork = origin.clone();
		assertEquals(value(0, 2), fork.next());
		assertEquals(value(0, 3), fork.next());
		assertEquals(value(1, 1), fork.next());

		assertTrue(origin.hasNext(), "origin exhausted by the fork's advance");
		assertEquals(value(0, 2), origin.peekNext(), "fork's advance moved the origin's cursor");
		assertEquals(value(0, 2), origin.next(), "fork's advance moved the origin's cursor");
		assertEquals(value(0, 3), origin.next(), "fork's advance moved the origin's cursor");

		final PeekableIntIterator sought = bitmap.getIntIterator();
		sought.advanceIfNeeded(value(1, 2));
		final PeekableIntIterator soughtFork = sought.clone();
		assertEquals(value(1, 2), soughtFork.next());
		assertEquals(value(1, 3), soughtFork.next());
		assertEquals(value(1, 2), sought.next(), "the fork consumed the origin's sought-to value");
	}
}
