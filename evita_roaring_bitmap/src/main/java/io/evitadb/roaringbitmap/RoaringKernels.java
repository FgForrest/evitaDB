package io.evitadb.roaringbitmap;

import io.evitadb.roaringbitmap.kernel.VectorKernels;

import javax.annotation.Nonnull;

/**
 * The one thing outside this module that anybody needs to know about its computation kernels: which
 * implementation the bitmap containers ended up running on, and why.
 *
 * The kernels themselves live in a package this module does not export, because they are an implementation
 * detail of the containers and nothing outside may call them. Their *decision*, however, is an operational
 * fact worth a line in the server log — an operator who forgot `--add-modules jdk.incubator.vector` should
 * be able to see that from the log rather than from a benchmark.
 *
 * This module deliberately has no logging dependency, so the string is returned rather than logged; evitaDB's
 * engine writes it once at startup.
 */
public final class RoaringKernels {

	private RoaringKernels() {
		throw new UnsupportedOperationException("RoaringKernels is a holder and must not be instantiated.");
	}

	/**
	 * One line naming the kernel implementation in force for the roaring containers and the reason it was
	 * chosen, e.g. `roaring vector kernels: bitmap=vector(512-bit) array=scalar (bitmap: jdk.incubator.vector
	 * present, JIT compiler available, self-test passed; array: no usable vector implementation)`.
	 *
	 * Reading it for the first time resolves the decision, which is deliberate: the engine calls this at
	 * startup so that the choice is made (and visible) before the first query rather than inside one.
	 *
	 * @return the decision summary; never empty
	 */
	@Nonnull
	public static String vectorKernelsSummary() {
		return VectorKernels.summary();
	}

}
