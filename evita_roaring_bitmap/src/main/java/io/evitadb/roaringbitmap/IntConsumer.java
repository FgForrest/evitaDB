package io.evitadb.roaringbitmap;

/**
 * Callback that receives the `int` values held by a bitmap or container, one at a time.
 *
 * Handed to the various `forEach` traversals (see {@link ImmutableBitmapDataProvider#forEach} and
 * {@link Container#forEach}); each value is visited exactly once, in the traversal order of the
 * caller (ascending for the standard iterators). It is a single-method interface, so it is
 * typically supplied as a lambda:
 *
 * ```java
 * bitmap.forEach(value -> {
 * // do something with value
 * });
 * ```
 */
public interface IntConsumer {
	/**
	 * Receives one value produced by the traversal.
	 *
	 * @param value the visited value
	 */
	void accept(int value);
}
