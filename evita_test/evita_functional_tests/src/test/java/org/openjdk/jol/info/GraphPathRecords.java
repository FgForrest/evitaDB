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

package org.openjdk.jol.info;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exposes the object behind a {@link GraphPathRecord}, which JOL declares package-private.
 *
 * # Why this class lives in JOL's package
 *
 * `io.evitadb.utils.JolHeapSize` needs to subtract borrowed subgraphs from a measurement **by object identity**.
 * JOL's own {@link GraphLayout#subtract} cannot be used for that: it matches objects by ADDRESS, and this suite runs
 * under surefire `parallel=all`, so a GC between two walks relocates objects, the address sets stop corresponding,
 * and shared objects silently fail to be subtracted — inflating the result by a different amount on every run. That
 * turns a correct production figure into a flaky failure.
 *
 * JOL's traversal itself has no such problem: {@link GraphWalker} dedupes visited objects with a
 * `SimpleIdentityHashSet`, so a single walk is deterministic regardless of what the collector does. The only missing
 * piece is reading the visited object out of the record, and {@link GraphPathRecord#obj()} is package-private. One
 * accessor declared in the same package is the whole of the workaround, and it keeps the intrusion to a single
 * method rather than moving measurement logic in here.
 *
 * This relies on JOL being a plain classpath jar with no `module-info` (verified for jol-core 0.17). Should JOL ever
 * ship as a named module, this class stops compiling — which is the correct way for that assumption to fail.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
public final class GraphPathRecords {

	private GraphPathRecords() {
		// utility class, never instantiated
	}

	/**
	 * Returns the object a walk record refers to.
	 *
	 * @param record the record produced by a {@link GraphVisitor}
	 * @return the visited object
	 */
	@Nonnull
	public static Object objectOf(@Nonnull GraphPathRecord record) {
		return record.obj();
	}

	/**
	 * Returns the record this one was reached through, or `null` for a walk root.
	 *
	 * Needed so a measurement can decide whether an object was reached **through** a borrowed structure and skip it
	 * without ever enumerating that structure's contents. Enumerating is not an option for live JVM state: a
	 * `Class`'s reflection cache can be populated by another thread between an enumeration walk and a summing walk,
	 * so anything reached in the second but missing from the first is charged by accident — which is precisely the
	 * drift this whole approach exists to remove.
	 *
	 * @param record the record whose parent is wanted
	 * @return the parent record, or `null` when `record` is a root
	 */
	@Nullable
	public static GraphPathRecord parentOf(@Nonnull GraphPathRecord record) {
		return record.parent;
	}

}
