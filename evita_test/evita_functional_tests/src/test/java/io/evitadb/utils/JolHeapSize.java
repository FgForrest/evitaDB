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
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

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

	/**
	 * The JDK flyweights this repository's arithmetic prices at **zero**, and which a walk must therefore stop at.
	 *
	 * # Why a `Locale` is not merely subtracted but made opaque
	 *
	 * `EvitaDataTypes#estimateSize` charges `0` for a `Locale` — "flyweights owned by the JVM" — and
	 * `IndexHeapSize#OWNED_KEY_SIZER` charges a `ComparableLocale` for its wrapper alone. Every index in the codebase
	 * therefore reports a locale it holds as free, and a walk that charged one would accuse correct arithmetic of
	 * under-counting. That much a borrowed root could handle.
	 *
	 * What a borrowed root **cannot** handle is that a `Locale`'s own subgraph *grows while the JVM runs*.
	 * `Locale#toLanguageTag` memoises its result into the instance's `languageTag` field — 48 bytes, a `String` and
	 * its `byte[]` — and every locale evitaDB uses is a JVM-wide constant that any code anywhere may materialise at
	 * any moment. Subtracting it by naming it a shared root only works if it is named *and* if the materialisation
	 * happens outside the window between the borrowed walk and the charging walk; miss either and the figure moves by
	 * exactly those 48 bytes, in whichever direction the two walks happened to straddle.
	 *
	 * That is not hypothetical: it is what made `EntityIndexHeapSizeTest#shouldChargeTheHistogramLeafPageBaseline`
	 * (which compares two walks of one index) and `CatalogIndexHeapSizeTest#shouldNotAccumulateCachedViewsOnFlush`
	 * (likewise) fail intermittently, at that same 48-byte quantum, whenever a *concurrent* test in the same surefire
	 * fork touched `Locale.ENGLISH` between the two readings.
	 *
	 * Stopping the walk **at** the locale removes the window entirely: nothing beneath it can enter the figure, no
	 * matter when it appears. The remaining reference slot is charged to the holder as it always was.
	 *
	 * # Why only `Locale`
	 *
	 * The neighbouring flyweights the model also prices at zero — `Currency`, enum constants, an interned
	 * `ZoneOffset` — are *immutable* once created: naming one as a borrowed root is sufficient, which is what the
	 * call sites already do, and widening this predicate to them would change figures those tests currently pin for
	 * no hermeticity gain. Add a type here when its subgraph can grow after construction, not merely because it is
	 * shared.
	 */
	private static final Predicate<Object> JVM_FLYWEIGHT = object -> object instanceof Locale;

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
	 * The {@link #JVM_FLYWEIGHT} types need no such entry and cannot be re-included by one: the walk stops at them
	 * whether they are named or not, because naming alone would not make the figure hermetic.
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
		// A CLASS, and every JVM_FLYWEIGHT, is excluded by NOT BEING TRAVERSED AT ALL - see `ClassBlindGraphWalker`
		// for why removing one from the sum afterwards is not enough, and why descending into one makes a figure
		// depend on JVM history rather than on the object being measured.
		final Set<Object> borrowed = Collections.newSetFromMap(new IdentityHashMap<>());
		if (sharedRoots.length > 0) {
			Collections.addAll(borrowed, sharedRoots);
			new ClassBlindGraphWalker(JVM_FLYWEIGHT).walk(borrowed::add, sharedRoots);
		}

		final long[] owned = new long[1];
		if (!(instance instanceof Class) && !borrowed.contains(instance)) {
			// the walker hands its visitor every object it REACHES but never the root it starts from. A flyweight
			// passed here IS what the caller asked about, so it is charged its own shell - it is only free to the
			// structures that merely reference it
			owned[0] += VM.current().sizeOf(instance);
		}
		new ClassBlindGraphWalker(JVM_FLYWEIGHT).walk(
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
