package io.evitadb.roaringbitmap.kernel;

import io.evitadb.roaringbitmap.Util;

import javax.annotation.Nonnull;

/**
 * The reference implementation of {@link ArrayKernels}: the linear two-way merge the sparse container
 * operators have always used, reached through `Util`.
 *
 * It delegates rather than re-implements on purpose. A second copy of the merge would have to be kept in
 * step with the vendored one by hand, and the whole value of a reference implementation is that it is the
 * code the rest of the module already runs.
 *
 * Stateless and therefore safe to share: {@link #INSTANCE} is the only instance anyone needs.
 */
public final class ScalarArrayKernels implements ArrayKernels {
	/**
	 * The shared instance. The class holds no state, so a second one would buy nothing.
	 */
	public static final ScalarArrayKernels INSTANCE = new ScalarArrayKernels();

	@Override
	public int intersectCardinality(@Nonnull final char[] a, final int la, @Nonnull final char[] b, final int lb) {
		return Util.unsignedLocalIntersect2by2Cardinality(a, la, b, lb);
	}

	@Override
	public int intersect(
		@Nonnull final char[] a,
		final int la,
		@Nonnull final char[] b,
		final int lb,
		@Nonnull final char[] out
	) {
		return Util.unsignedLocalIntersect2by2(a, la, b, lb, out);
	}

}
