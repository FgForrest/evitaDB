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

import org.openjdk.jol.util.ObjectUtils;
import org.openjdk.jol.util.SimpleIdentityHashSet;
import org.openjdk.jol.util.SimpleStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * A {@link GraphWalker} that never traverses a {@link Class}, so a class's subgraph cannot claim an object the
 * structure under measurement also holds.
 *
 * # Why JOL's own walker cannot be used
 *
 * {@link GraphWalker} gates the record and the stack push on a single identity set:
 *
 * ```
 * if (e != null && visited.add(e)) { ... }
 * ```
 *
 * Every object is therefore recorded exactly once, under **whichever path reached it first**, and a visitor can only
 * classify that one path — it cannot stop the walk from descending. Filtering class-borne objects out of the *sum*
 * afterwards is consequently not enough: the class subgraph has already marked them visited, so the structure's own
 * reference to the same object is discarded as already-seen and its owner silently stops being charged for it.
 *
 * That is not a hypothetical. A `Class` enters the graph as an ordinary field value — {@link AbstractGraphWalker}
 * skips static fields, so it is never reached through one — and descent then continues through the class's *instance*
 * fields, among them `reflectionData`: a {@link java.lang.ref.SoftReference} to a lazily built cache of `Field[]`,
 * `Method[]` and annotation data. Whether that cache exists depends on what reflected on the class earlier and on
 * whether the collector has cleared it since. A measurement that descends into it therefore depends on the history of
 * the whole JVM, and the same structure measures differently when its test runs alone and when it runs in a suite.
 *
 * Refusing to descend removes both problems at once, and it removes them in the safe direction: an object reachable
 * *only* through a class was never charged and still is not, while one the structure genuinely holds is now always
 * charged to it. Measured figures can therefore only rise, never fall.
 *
 * # Why it lives in JOL's package
 *
 * {@link AbstractGraphWalker} is package-private and its `getAllReferenceFields` — which caches the reference fields
 * per class and pre-authorises them for reflection — is `protected`. Subclassing is the only way to reuse it, and
 * reusing it is what keeps this walker's field enumeration identical to JOL's. See {@link GraphPathRecords} for the
 * same trade-off made for the same reason.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
public final class ClassBlindGraphWalker extends AbstractGraphWalker {

	/**
	 * Receives every object the walk reaches, excluding the roots it starts from and every {@link Class}.
	 */
	@FunctionalInterface
	public interface ObjectVisitor {

		/**
		 * Called once per reached object, in the order the walk reaches it.
		 *
		 * @param object the object reached
		 */
		void visit(@Nonnull Object object);

	}

	/**
	 * Walks everything reachable from `roots` without descending into any {@link Class}, handing each reached object
	 * to `visitor` exactly once.
	 *
	 * The roots themselves are **not** visited, matching {@link GraphWalker#walk} — a caller that needs to charge
	 * them must do so itself, which is what lets it apply its own rules to them.
	 *
	 * @param visitor receives every reached object
	 * @param roots   the objects to walk from; `null` entries and {@link Class} entries are ignored
	 */
	public void walk(@Nonnull ObjectVisitor visitor, @Nonnull Object... roots) {
		final SimpleIdentityHashSet visited = new SimpleIdentityHashSet();
		final SimpleStack<Object> stack = new SimpleStack<>();

		for (Object root : roots) {
			if (isTraversable(root) && visited.add(root)) {
				stack.push(root);
			}
		}

		while (!stack.isEmpty()) {
			final Object current = stack.pop();
			final Class<?> type = current.getClass();

			if (type.isArray()) {
				if (type.getComponentType().isPrimitive()) {
					// a primitive array points at nothing; its bytes are charged by the visitor that reached it
					continue;
				}
				for (Object element : (Object[]) current) {
					reach(element, visitor, visited, stack);
				}
			} else {
				for (Field field : getAllReferenceFields(type)) {
					// `ObjectUtils.value` falls back to the VM's own field offsets when reflection is refused, which
					// is what lets this walk cross into JDK-internal types without `--add-opens`
					reach(ObjectUtils.value(current, field), visitor, visited, stack);
				}
			}
		}
	}

	/**
	 * Offers one referenced object to the walk, visiting and enqueueing it the first time it is seen.
	 *
	 * @param object  the referenced object, possibly null
	 * @param visitor the visitor to notify
	 * @param visited the identity set of everything already reached
	 * @param stack   the traversal stack
	 */
	private static void reach(
		@Nullable Object object,
		@Nonnull ObjectVisitor visitor,
		@Nonnull SimpleIdentityHashSet visited,
		@Nonnull SimpleStack<Object> stack
	) {
		if (isTraversable(object) && visited.add(object)) {
			visitor.visit(object);
			stack.push(object);
		}
	}

	/**
	 * Decides whether the walk may descend into an object at all.
	 *
	 * @param object the candidate, possibly null
	 * @return true when the object exists and is not a {@link Class}
	 */
	private static boolean isTraversable(@Nullable Object object) {
		return object != null && !(object instanceof Class);
	}

}
