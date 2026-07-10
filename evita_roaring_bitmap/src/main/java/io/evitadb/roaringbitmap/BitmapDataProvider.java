/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

/**
 * Mutable 32-bit bitmap contract. Extends {@link ImmutableBitmapDataProvider} with in-place
 * mutation (add, remove) and memory-management (trim) operations. Set bits are addressed by
 * unsigned 32-bit integers.
 */
public interface BitmapDataProvider extends ImmutableBitmapDataProvider {
	/**
	 * Sets the bit for the given value, whether or not it is already present. Idempotent: adding an
	 * already-present value leaves the bitmap unchanged.
	 *
	 * @param x value to add, treated as an unsigned 32-bit integer
	 */
	void add(int x);

	/**
	 * Adds every value in the half-open range {@code [min, sup)} to the bitmap. Values already
	 * present are left untouched.
	 *
	 * @param min inclusive lower bound, in {@code [0, 0x100000000)}
	 * @param sup exclusive upper bound, in {@code [0, 0x100000000)}
	 */
	void add(long min, long sup);

	/**
	 * Clears the bit for the given value if present; a no-op when the value is absent.
	 *
	 * @param x value to remove, treated as an unsigned 32-bit integer
	 */
	void remove(int x);

	/**
	 * Releases memory that has been allocated but is currently unused, shrinking internal buffers to
	 * fit the live cardinality.
	 */
	void trim();
}
