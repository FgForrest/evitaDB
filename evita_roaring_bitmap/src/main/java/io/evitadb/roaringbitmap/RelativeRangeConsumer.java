package io.evitadb.roaringbitmap;

/**
 * Visitor over a contiguous range of a bitmap that reports both the values present and the values
 * absent, addressed by *relative* offset rather than absolute bitmap index.
 *
 * A range consumer is handed to range traversals such as
 * {@link PersistentRoaringBitmap#forAllInRange} together with a range start. Every offset the
 * visitor is told about is relative to that start: the absolute bitmap index of a reported position
 * is `start + relativePos`. Addressing positions relatively lets one consumer be reused for any
 * range regardless of where it sits, and keeps the offsets within `int` range even for 64-bit
 * bitmaps, where `start` may itself be a `long`.
 *
 * A value at absolute position `pos` is *present* when `bitmap.contains(pos)` is `true`, otherwise
 * it is *absent*. Across a single traversal the four callbacks partition the whole requested range
 * `[0, length)` exactly once - every relative offset is reported by exactly one call, either singly
 * or as part of a run - so the consumer sees a complete, gap-free picture of the range.
 *
 * The bulk `acceptAll*` callbacks let the traversal collapse a long consecutive run into a single
 * call instead of one call per value, which is the fast path for dense or empty stretches.
 */
public interface RelativeRangeConsumer {
	/**
	 * Reports a single present value at relative offset `relativePos` (absolute index
	 * `start + relativePos`).
	 *
	 * @param relativePos offset from the range start of a value contained in the bitmap
	 */
	void acceptPresent(int relativePos);

	/**
	 * Reports a single absent value at relative offset `relativePos` (absolute index
	 * `start + relativePos`).
	 *
	 * @param relativePos offset from the range start of a value not contained in the bitmap
	 */
	void acceptAbsent(int relativePos);

	/**
	 * Reports a consecutive run of present values covering the half-open relative range
	 * `[relativeFrom, relativeTo)`.
	 *
	 * @param relativeFrom inclusive start offset of the run
	 * @param relativeTo   exclusive end offset of the run
	 */
	void acceptAllPresent(int relativeFrom, int relativeTo);

	/**
	 * Reports a consecutive run of absent values covering the half-open relative range
	 * `[relativeFrom, relativeTo)`.
	 *
	 * @param relativeFrom inclusive start offset of the run
	 * @param relativeTo   exclusive end offset of the run
	 */
	void acceptAllAbsent(int relativeFrom, int relativeTo);
}
