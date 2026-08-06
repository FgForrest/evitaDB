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

import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.info.GraphPathRecord;
import org.openjdk.jol.info.GraphPathRecords;
import org.openjdk.jol.info.GraphWalker;
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
		// under-counting. JOL's traversal has no such weakness - `GraphWalker` dedupes with a `SimpleIdentityHashSet`
		// - so doing the set subtraction by identity makes the whole measurement deterministic under any collector.
		// Two different exclusions, because the two kinds of borrowing behave differently.
		//
		// A NAMED shared root is excluded by MEMBERSHIP: everything it reaches is somebody else's, no matter which
		// path this walk happens to arrive by. That matters for aliasing - a copy-on-write duplicate reaches the
		// original's backing arrays directly, never "through" the original, so a path test would charge them.
		// Enumerating a named root is safe: those are this codebase's own structures, not mutating underfoot.
		//
		// A CLASS is excluded by PATH, and must never be enumerated. Its reflection cache is live JVM state that
		// another thread can populate between an enumeration walk and a summing walk, so the summing walk reaches
		// objects the enumeration never saw and charges them - which is exactly the drift this method exists to
		// remove. Deciding per record inside the one walk leaves no window.
		final Set<Object> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
		if (sharedRoots.length > 0) {
			Collections.addAll(borrowed, sharedRoots);
			new GraphWalker(record -> {
				if (!reachedThroughClass(record)) {
					borrowed.add(GraphPathRecords.objectOf(record));
				}
			}).walk(sharedRoots);
		}

		final long[] owned = new long[1];
		if (!(instance instanceof Class) && !borrowed.contains(instance)) {
			// `GraphWalker` hands its visitors every object it REACHES but never the root it starts from
			owned[0] += VM.current().sizeOf(instance);
		}
		new GraphWalker(record -> {
			final Object visited = GraphPathRecords.objectOf(record);
			if (!borrowed.contains(visited) && !reachedThroughClass(record)) {
				// `record.size()` is NOT readable here: the walker invokes its visitors before it calls
				// `setSize` on the record, so a visitor sees an unpopulated size. Asking the VM directly is
				// what the walker itself does one step later, and it is the same number
				owned[0] += VM.current().sizeOf(visited);
			}
		}).walk(instance);
		return owned[0];
	}

	/**
	 * Decides whether a visited object is a {@link Class}, or was only reachable by going through one.
	 *
	 * A `Class` drags in its lazily-populated reflection cache — `Class$ReflectionData`, a `SoftReference`, a
	 * `Field[]`, one `Field` per declared field, a `ReferenceQueue`, the `Module` and several interned strings, 600
	 * bytes in one measured case. None of that belongs to the object under test, and it materialises the first time
	 * anything reflects on the class, so including it would make every figure depend on what happened to run earlier
	 * in the same JVM. The ownership rule already says a `Class` is owned by its class loader and its holder pays
	 * only for the reference slot; this enforces it rather than trusting each call site to remember to name it.
	 *
	 * @param record the record to classify
	 * @return true when this object sits at or below a `Class` on the walk path
	 */
	private static boolean reachedThroughClass(@Nonnull GraphPathRecord record) {
		for (GraphPathRecord step = record; step != null; step = GraphPathRecords.parentOf(step)) {
			if (GraphPathRecords.objectOf(step) instanceof Class) {
				return true;
			}
		}
		return false;
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
