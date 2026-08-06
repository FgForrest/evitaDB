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

package io.evitadb.spike.footprint;

import io.evitadb.utils.VMLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

import javax.annotation.Nonnull;

/**
 * Shared harness for the **memory-footprint spikes** — the measured ground truth behind every heap estimate
 * evitaDB reports through the `MEMORY_FOOTPRINT` statistics component.
 *
 * An estimate is a formula that claims to predict a structure's heap cost without walking it. The only way to
 * know whether such a formula is *right* — rather than merely unchanged since someone wrote it down — is to
 * compare it against a real measurement of a real instance. That is what these spikes do: build the structure
 * the engine actually builds, measure it with JOL, run the candidate formula over it, and print both.
 *
 * # The three accounting rules
 *
 * Every number produced here obeys the same three rules, in this order. They are not stylistic preferences —
 * they decide what a number *means*, and getting them wrong produces a figure that is wrong rather than
 * merely imprecise.
 *
 * **1. Never count shared objects.** A structure owns what dies with it. JVM singletons, interned instances,
 * empty-array constants, and anything maintained elsewhere and merely pointed at are *borrowed*: only the
 * reference slot belongs to the borrower. Charging a borrowed object to yourself is not caution, it is a
 * wrong answer — the parts then stop summing to the whole, and a caller adding up per-index figures gets a
 * total larger than the heap. Use {@link #ownedSize(Object, Object...)} and name the shared roots explicitly.
 *
 * **2. Structure shared with a *previous version* is charged in full.** This scopes rule 1 rather than
 * contradicting it: the test is **who outlives whom**, not whether two references currently exist. Rule 1
 * excludes objects whose owner *outlives* the borrower. A copy-on-write predecessor is the opposite — a
 * superseded MVCC version is garbage-in-waiting, collected quickly, after which the survivor is the sole
 * owner of everything it was aliasing. Reporting the pre-collection split describes a state that lasts
 * milliseconds and understates the steady state, which is the only state an operator can act on. So when
 * measuring a copy-on-write structure, do **not** pass its previous version as a shared root.
 *
 * This is a property of persistent data structures in general, not of any one class. It applies identically
 * to roaring containers aliased by {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap}'s `shared[]`, to
 * the pages a {@link io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree} or
 * {@link io.evitadb.index.bPlusTree.TransactionalLongBPlusTree} carries over unchanged into its next
 * version, and to every CHAMP node a transactional map re-uses across a commit. Whenever a structure here is
 * described as copy-on-write, assume this rule governs it.
 *
 * **3. When several figures are defensible, report the higher one.** Applied *after* rules 1 and 2, never
 * against them. Where a structure's cost genuinely depends on something unknowable at estimate time — a
 * String's internal encoding, an array's slack capacity — assume the more expensive shape. An operator
 * sizing a heap is harmed by an optimistic number and merely inconvenienced by a pessimistic one.
 *
 * Rules 1 and 2 outrank rule 3 because they can collide, and when they do, rule 3 is the one that must give
 * way: "count it anyway, to be safe" is exactly how a genuinely shared object gets billed to every one of
 * its borrowers.
 *
 * # Reading the output
 *
 * Every row prints the JOL-measured truth, the candidate estimate, and their ratio. A ratio **below 1.00 is
 * a defect** — the estimate under-reports, which rule 3 forbids outright. A ratio above 1.00 is acceptable
 * in proportion to how far above: the goal is the smallest formula that never dips below.
 *
 * # Recording the result
 *
 * Numbers produced here belong in the javadoc of the **measured class**, not only in this spike, so that
 * whoever changes that structure sees the cost they must update. Every recorded figure must carry the date
 * and the VM layout it was measured under ({@link #describeLayout()} prints exactly that) — without both, a
 * later reader cannot tell whether the number is still credible, and a rotted number is worse than none.
 *
 * @author Claude (memory-footprint spike harness), FG Forrest a.s. (c) 2026
 */
public final class FootprintSpikeSupport {
	/** Width of the label column, chosen so the widest scenario label in the spikes still fits. */
	private static final int LABEL_WIDTH = 52;

	private FootprintSpikeSupport() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Measures the heap `instance` **owns**: its deep retained graph minus everything reachable from any of
	 * the named shared roots.
	 *
	 * This is set subtraction over the two object graphs, not `totalSize() - totalSize()` arithmetic. The
	 * distinction matters: only objects genuinely reachable from *both* graphs are dropped, so naming a root
	 * that `instance` does not actually reach cannot silently shrink the answer. An over-eager shared-root
	 * list is therefore safe to write, which is what makes rule 1 practical to apply.
	 *
	 * It is **not** safe against rule 2, though, and that is the one misuse to watch for: passing a
	 * copy-on-write structure's superseded predecessor here subtracts exactly the structure the current
	 * version must be charged for. A shared root names a *peer* that outlives this instance, never an
	 * earlier version of it.
	 *
	 * @param instance    the structure whose owned footprint is wanted
	 * @param sharedRoots objects whose graphs are owned by somebody else; anything reachable from them is
	 *                    excluded from the result
	 * @return bytes of heap that would be reclaimed if `instance` became unreachable while every shared root
	 *         stayed alive
	 */
	public static long ownedSize(@Nonnull Object instance, @Nonnull Object... sharedRoots) {
		GraphLayout layout = GraphLayout.parseInstance(instance);
		for (final Object sharedRoot : sharedRoots) {
			layout = layout.subtract(GraphLayout.parseInstance(sharedRoot));
		}
		return layout.totalSize();
	}

	/**
	 * Measures only the object itself — its header plus its own fields, with every reference counted as one
	 * slot and nothing followed. Useful for isolating a wrapper's overhead from its payload.
	 *
	 * @param instance the object to measure
	 * @return the object's shallow size in bytes, including alignment padding
	 */
	public static long shallowSize(@Nonnull Object instance) {
		return VM.current().sizeOf(instance);
	}

	/**
	 * Describes the VM object layout the current run measures under — the second half of what makes a
	 * recorded figure credible later (the first being the date).
	 *
	 * @return a one-line description of reference size, header sizes and object alignment
	 */
	@Nonnull
	public static String describeLayout() {
		final VMLayout layout = VMLayout.current();
		return String.format(
			"reference=%dB, object header=%dB, array header=%dB, alignment=%dB",
			layout.referenceSize(), layout.objectHeaderSize(), layout.arrayHeaderSize(), layout.objectAlignment()
		);
	}

	/**
	 * Prints a spike's opening banner, including the VM layout every following number was measured under.
	 *
	 * @param title what this spike measures
	 */
	public static void banner(@Nonnull String title) {
		System.out.printf("%s%n", title);
		System.out.printf("VM layout: %s%n", describeLayout());
		System.out.println("Truth = JOL owned size (deep retained, shared roots subtracted). " +
			"Ratio = estimate / truth; below 1.00 is a defect.");
		System.out.println();
	}

	/**
	 * Prints a section heading grouping the rows that follow.
	 *
	 * @param title the section title
	 */
	public static void section(@Nonnull String title) {
		System.out.printf("== %s ==%n", title);
	}

	/**
	 * Prints one measured row: the JOL truth, a candidate estimate, and how far the estimate strays.
	 *
	 * @param label    what was measured
	 * @param truth    the JOL-measured owned size in bytes
	 * @param estimate what the candidate formula predicted, in bytes
	 */
	public static void row(@Nonnull String label, long truth, long estimate) {
		// a truth of zero would make the ratio meaningless rather than infinite - print it as such instead of
		// letting a division produce a number a reader would take seriously
		final String ratio = truth == 0 ? "     n/a" : String.format("%7.2fx", (double) estimate / truth);
		System.out.printf(
			"  %-" + LABEL_WIDTH + "s : truth %,12d B | est %,12d B | %s%n",
			label, truth, estimate, ratio
		);
	}

	/**
	 * Prints a measured row with no estimate to compare against — a plain observation.
	 *
	 * @param label what was measured
	 * @param truth the JOL-measured owned size in bytes
	 */
	public static void observation(@Nonnull String label, long truth) {
		System.out.printf("  %-" + LABEL_WIDTH + "s : truth %,12d B%n", label, truth);
	}

	/**
	 * Prints a free-form remark between rows — used to record what a group of numbers demonstrates, so the
	 * console output stays self-explanatory when pasted into a decision record.
	 *
	 * @param text the remark
	 */
	public static void note(@Nonnull String text) {
		System.out.printf("  -> %s%n", text);
	}
}
