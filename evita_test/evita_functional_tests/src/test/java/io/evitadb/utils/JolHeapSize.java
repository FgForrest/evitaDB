/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.utils;

import org.openjdk.jol.info.ClassBlindGraphWalker;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Ground truth for heap-size estimates, measured with JOL rather than asserted from a remembered number.
 *
 * **Why this exists.** A size test can be written three ways, and only one of them tests anything:
 *
 * - deriving the expectation from the same constants the implementation uses - passes for every formula,
 *   including a wrong one. This is how an `int[]` estimate that was **6x** too large once survived a full suite.
 * - asserting a literal captured on some past date - detects change, but says nothing about correctness, and the
 *   usual response to a failure is to re-measure and overwrite the literal.
 * - asking the VM what the object actually weighs, on every run. That is what this class does.
 *
 * **Shared objects are the whole difficulty.** {@link GraphLayout} follows every reference transitively, so a deep
 * measurement happily charges an object for structures it merely borrows - an {@link java.time.OffsetDateTime}
 * measures 280 bytes because its interned `ZoneOffset` drags the shared timezone-rules database along, when the
 * value itself owns 96. {@link #ownedSize} therefore takes the borrowed roots explicitly and subtracts them as an
 * object **set**, not as an arithmetic difference of totals. Naming them at the call site is the point: it makes the
 * ownership decision executable instead of leaving it in a comment, which is the only form that stays true.
 *
 * **Before writing a new size test, read `documentation/developer/heap-size-testing.md`.** It carries the ownership
 * rules the arithmetic has to follow and four traps that let a wrong implementation pass — chief among them that a
 * JVM-shared instance such as `Integer.valueOf(0)` is charged once by a walk and once per holder by the arithmetic,
 * so naming it here where nothing contends for it manufactures a divergence rather than removing one.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class JolHeapSize {
	private JolHeapSize() {
	}

	/**
	 * Footprint of one object alone, ignoring everything it points at. The right measure for a value whose fields
	 * are all primitive - a boxed number, a `UUID`, a primitive array.
	 *
	 * @param instance the object to measure
	 * @return its heap footprint in bytes, header and alignment padding included
	 */
	public static long shallowSize(@Nonnull Object instance) {
		return VM.current().sizeOf(instance);
	}

	/**
	 * Footprint of an object and everything it owns, with the passed borrowed roots removed.
	 *
	 * Pass a `sharedRoots` entry for every structure reachable from `instance` that belongs to somebody else - a JVM
	 * singleton, an interned instance, a structure another owner maintains. Passing none asserts that the object owns
	 * its entire reachable graph.
	 *
	 * @param instance    the object whose owned footprint is wanted
	 * @param sharedRoots roots of the subgraphs `instance` borrows rather than owns
	 * @return the owned footprint in bytes
	 */
	public static long ownedSize(@Nonnull Object instance, @Nonnull Object... sharedRoots) {
		// Identity, never addresses. JOL's own `GraphLayout.subtract` matches objects by ADDRESS, and this suite runs
		// under surefire `parallel=all`: a GC between the instance walk and a shared-root walk relocates objects, the
		// two address sets stop corresponding, and shared objects silently fail to be subtracted. The figure then
		// reads high by a different amount on every run, and a correct production estimate is accused of
		// under-counting. A traversal has no such weakness - it dedupes with a `SimpleIdentityHashSet` - so doing the
		// set subtraction by identity too makes the whole measurement deterministic under any collector.
		//
		// A NAMED shared root is excluded by MEMBERSHIP: everything it reaches is somebody else's, no matter which
		// path this walk happens to arrive by. That matters for aliasing - a copy-on-write duplicate reaches the
		// original's backing arrays directly, never "through" the original, so a path test would charge them.
		// Enumerating a named root is safe: those are this codebase's own structures, not mutating underfoot.
		//
		// A CLASS is excluded by NOT BEING TRAVERSED AT ALL - see `ClassBlindGraphWalker` for why removing it from
		// the sum afterwards is not enough, and why descending into one makes a figure depend on JVM history.
		final Set<Object> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
		if (sharedRoots.length > 0) {
			Collections.addAll(borrowed, sharedRoots);
			new ClassBlindGraphWalker().walk(borrowed::add, sharedRoots);
		}

		final long[] owned = new long[1];
		if (!(instance instanceof Class) && !borrowed.contains(instance)) {
			// the walker hands its visitor every object it REACHES but never the root it starts from
			owned[0] += VM.current().sizeOf(instance);
		}
		new ClassBlindGraphWalker().walk(
			visited -> {
				if (!borrowed.contains(visited)) {
					owned[0] += VM.current().sizeOf(visited);
				}
			},
			instance
		);
		return owned[0];
	}

	/**
	 * Width of one object reference on the running VM, measured rather than read from a constant.
	 *
	 * Derived from the difference between two **large** reference arrays whose lengths differ by a power of two.
	 * That cancels the array header and, more importantly, the alignment padding: the naive
	 * `sizeOf(new Object[1]) - sizeOf(new Object[0])` answers **8** under compressed oops, because a one-element
	 * array occupies `16 + 4 = 20` bytes rounded up to 24 - the padding, not the reference, supplies the extra 4.
	 *
	 * @return 4 bytes under compressed oops, 8 otherwise
	 */
	public static long referenceSize() {
		final long larger = VM.current().sizeOf(new Object[1024]);
		final long smaller = VM.current().sizeOf(new Object[512]);
		return (larger - smaller) / 512;
	}

}
