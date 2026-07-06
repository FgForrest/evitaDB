/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */
package io.evitadb.roaringbitmap;

/**
 * Callback that receives the `long` values held by a 64-bit bitmap, one at a time.
 *
 * The 64-bit counterpart of {@link IntConsumer}, handed to the `forEach` traversals of
 * {@link PersistentLongRoaringBitmap}; each value is visited exactly once, in the traversal order
 * of the caller. Being a single-method interface, it is typically supplied as a lambda:
 *
 * ```java
 * bitmap.forEach(value -> {
 * // do something with value
 * });
 * ```
 */
public interface LongConsumer {
	/**
	 * Receives one value produced by the traversal.
	 *
	 * @param value the visited value
	 */
	void accept(long value);
}
