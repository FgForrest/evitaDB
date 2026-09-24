/*
 * Vendored subset of RoaringBitmap (Apache-2.0), reshaped for evitaDB.
 * See the module NOTICE file for attribution and the synced upstream commit.
 */

/**
 * evitaDB's vendored, persistent (immutable, structure-sharing) RoaringBitmap.
 *
 * Derived from the RoaringBitmap project (https://github.com/RoaringBitmap/RoaringBitmap),
 * Apache License 2.0. See the LICENSE, AUTHORS and NOTICE files in this module.
 */
module evita.roaringbitmap {
	requires jsr305;
	// read once at class-init of `io.evitadb.roaringbitmap.kernel.VectorKernels` to see whether the JVM was
	// started with an optimizing JIT - a vector kernel that is not intrinsified is slower than the scalar one
	requires java.management;
	// optional: the HotSpot diagnostic bean answers what the compiler flags settled on, which is a better
	// signal than the command line; a runtime without it falls back to `RuntimeMXBean.getInputArguments()`
	requires static jdk.management;
	// optional at run time on purpose: the incubating Vector API is never in the default root set, so a
	// launcher that omits `--add-modules jdk.incubator.vector` simply runs the scalar kernels
	requires static jdk.incubator.vector;
	exports io.evitadb.roaringbitmap;
	// `io.evitadb.roaringbitmap.kernel` is deliberately NOT exported - the containers are its only callers,
	// and the one fact outside code needs is reachable through `io.evitadb.roaringbitmap.RoaringKernels`
}
