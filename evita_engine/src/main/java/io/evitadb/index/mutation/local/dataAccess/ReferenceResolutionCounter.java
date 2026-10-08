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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.mutation.local.dataAccess;

import javax.annotation.Nonnull;

// TODO JNO - remove at the end of #1615
/**
 * Counts the work the write path does to find the reference an attribute supplier of a representative reference key
 * reads (see {@link ReferenceEntityStoragePartAccessorAttributeValueByRepresentativeReferenceKeySupplier}): how many
 * times it resolved the reference anew and how many reference keys it compared while doing so. The operation-count
 * gates of the write path read it to prove that a resolution costs a binary search over an entity's references plus a
 * walk over the references of one key, never a scan of all of them.
 *
 * A counter counts the resolutions of the thread that started it, until the thread stops it, so the tests running
 * concurrently in one JVM never count each other's work. A thread without a counter pays one thread-local read per
 * resolution and counts nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ReferenceResolutionCounter {
	/**
	 * The counter of the current thread, absent when the thread counts nothing.
	 */
	private static final ThreadLocal<ReferenceResolutionCounter> CURRENT = new ThreadLocal<>();
	/**
	 * How many times a reference was resolved anew.
	 */
	private long resolutions;
	/**
	 * How many reference keys all resolutions compared together.
	 */
	private long comparisons;
	/**
	 * The most reference keys one resolution compared.
	 */
	private int maxComparisons;
	/**
	 * The most references an entity had when one of its references was resolved.
	 */
	private int maxReferences;

	/**
	 * Creates a counter - see {@link #start()}.
	 */
	private ReferenceResolutionCounter() {
	}

	/**
	 * Starts counting the resolutions of the current thread, replacing a counter the thread had.
	 *
	 * @return the counter
	 */
	@Nonnull
	public static ReferenceResolutionCounter start() {
		final ReferenceResolutionCounter counter = new ReferenceResolutionCounter();
		CURRENT.set(counter);
		return counter;
	}

	/**
	 * Stops counting the resolutions of the current thread.
	 */
	public static void stop() {
		CURRENT.remove();
	}

	/**
	 * Records one resolution of a reference to the counter of the current thread, if it has one.
	 *
	 * @param comparisons how many reference keys the resolution compared
	 * @param references  how many references the entity has
	 */
	static void record(int comparisons, int references) {
		final ReferenceResolutionCounter counter = CURRENT.get();
		if (counter != null) {
			counter.resolutions++;
			counter.comparisons += comparisons;
			counter.maxComparisons = Math.max(counter.maxComparisons, comparisons);
			counter.maxReferences = Math.max(counter.maxReferences, references);
		}
	}

	/**
	 * Returns how many times a reference was resolved anew.
	 *
	 * @return the number of resolutions
	 */
	public long getResolutions() {
		return this.resolutions;
	}

	/**
	 * Returns how many reference keys all resolutions compared together.
	 *
	 * @return the number of comparisons
	 */
	public long getComparisons() {
		return this.comparisons;
	}

	/**
	 * Returns the most reference keys one resolution compared.
	 *
	 * @return the maximum of comparisons per resolution
	 */
	public int getMaxComparisons() {
		return this.maxComparisons;
	}

	/**
	 * Returns the most references an entity had when one of its references was resolved.
	 *
	 * @return the maximum number of references
	 */
	public int getMaxReferences() {
		return this.maxReferences;
	}

	@Nonnull
	@Override
	public String toString() {
		return "resolutions=" + this.resolutions + ", comparisons=" + this.comparisons +
			", maxComparisons=" + this.maxComparisons + ", maxReferences=" + this.maxReferences;
	}
}
