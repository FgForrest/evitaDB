package io.evitadb.roaringbitmap.kernel;

import javax.annotation.Nonnull;

/**
 * Kernels over the sorted `char[]` value list that backs a sparse roaring container.
 *
 * Where {@link BitmapKernels} works on a fixed 8 KiB word array, this one works on two ascending lists of
 * 16-bit values whose intersection is found by a two-way merge. The merge is branchy and data dependent, so
 * unlike the word kernels it has no obvious SIMD shape; the interface exists so that the container operators
 * call through a single seam while candidate vector formulations are measured against
 * {@link ScalarArrayKernels}.
 *
 * **Contract shared by both methods.** Both lists are sorted in unsigned-ascending order and hold no
 * duplicates; only their first `la` / `lb` entries are read. Nothing is allocated and no argument is
 * validated — these are inner loops called from this module's container operators.
 *
 * @see ScalarArrayKernels the reference implementation, and today's only one
 * @see VectorKernels the holder that picks between implementations
 */
public interface ArrayKernels {

	/**
	 * Counts the values the two lists share, without materializing the intersection.
	 *
	 * @param a  first list, sorted unsigned-ascending
	 * @param la number of leading entries of `a` to consider
	 * @param b  second list, sorted unsigned-ascending
	 * @param lb number of leading entries of `b` to consider
	 * @return cardinality of the intersection
	 */
	int intersectCardinality(@Nonnull char[] a, int la, @Nonnull char[] b, int lb);

	/**
	 * Writes the values the two lists share to `out`, in ascending order, and returns how many there were.
	 *
	 * @param a   first list, sorted unsigned-ascending
	 * @param la  number of leading entries of `a` to consider
	 * @param b   second list, sorted unsigned-ascending
	 * @param lb  number of leading entries of `b` to consider
	 * @param out destination, at least `min(la, lb)` entries long, filled from index `0`
	 * @return number of values written to `out`
	 */
	int intersect(@Nonnull char[] a, int la, @Nonnull char[] b, int lb, @Nonnull char[] out);

}
