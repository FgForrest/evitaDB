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

package io.evitadb.index;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.JolHeapSize;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared measurement scaffolding for every test that checks a `getHeapSizeInBytes` implementation against JOL.
 *
 * The rules these assertions encode, and the traps they exist to avoid, are written up in
 * `documentation/developer/heap-size-testing.md`. Read that before adding a new heap test — in particular the reason
 * a JVM-shared box must **not** be named as a shared root where nothing contends for it.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
public final class IndexHeapSizeAssertions {

	/**
	 * The primary key every fixture should start numbering from, chosen to clear the JVM's boxed-`Integer` cache.
	 *
	 * Inside that cache two structures boxing the same value receive the **same instance**, which an identity walk
	 * counts once while the arithmetic charges it to each holder — the divergence rule 1 deliberately accepts,
	 * because whether a box is shared moves with `-XX:AutoBoxCacheMax` and must not decide what a memory reading
	 * says. Seeding above it removes the confound so a test can assert exact equality instead of a direction.
	 */
	public static final int AUTOBOX_CACHE_CEILING = 1_000;

	/**
	 * The JVM-wide zero-length arrays every empty structure in the codebase parks its fields on.
	 *
	 * They are shared by contract, not by luck: an empty {@code FrontCodedStringColumn} points both of its arrays at
	 * these, and a childless hierarchy node its children. Charging one would bill the same sixteen bytes to every
	 * empty structure in the catalog, so the arithmetic excludes them by identity — and every walk therefore has to
	 * subtract them, whichever index happens to reach them.
	 */
	public static final Object[] SHARED_EMPTY_ARRAYS = {
		ArrayUtils.EMPTY_INT_ARRAY, ArrayUtils.EMPTY_BYTE_ARRAY, ArrayUtils.EMPTY_LONG_ARRAY,
		ArrayUtils.EMPTY_OBJECT_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
	};

	private IndexHeapSizeAssertions() {
		// utility class, never instantiated
	}

	/**
	 * Resolves the named field paths against `instance` and returns the objects they point at.
	 *
	 * A path may cross an owned sub-index — `ownedTree.normalizer` names the scaffolding of the inverted index a
	 * sort index owns, which the walk reaches through it and which that index likewise does not charge. A path whose
	 * head resolves to `null` yields nothing, which is what makes the same exclusion list usable against both a cold
	 * and a warmed-up index.
	 *
	 * A path naming a field that no longer exists **throws**. Silently skipping it would leave the walker charging an
	 * object the arithmetic excludes, and the test would then fail somewhere far from the rename that caused it.
	 *
	 * @param instance the object to resolve the paths against
	 * @param paths    dot-separated field paths, in the order the roots should be handed to the walker
	 * @return the resolved objects, with any `null` dropped
	 */
	@Nonnull
	public static Object[] excluded(@Nonnull Object instance, @Nonnull String... paths) {
		final List<Object> roots = new ArrayList<>(paths.length);
		for (final String path : paths) {
			Object current = instance;
			for (final String step : path.split("\\.")) {
				if (current == null) {
					break;
				}
				// a numeric step indexes into the array the previous one resolved to, so a record held inside an
				// owned array can still have its own shared components named
				current = step.chars().allMatch(Character::isDigit) ?
					Array.get(current, Integer.parseInt(step)) : readField(current, step);
			}
			if (current != null) {
				roots.add(current);
			}
		}
		return roots.toArray();
	}

	/**
	 * Reads one field off an object, searching its class and every superclass.
	 *
	 * @param instance  the object to read from
	 * @param fieldName the field to read
	 * @return the field's current value, possibly `null`
	 */
	@Nullable
	public static Object readField(@Nonnull Object instance, @Nonnull String fieldName) {
		Class<?> type = instance.getClass();
		Field field = null;
		while (type != null && field == null) {
			try {
				field = type.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignored) {
				type = type.getSuperclass();
			}
		}
		if (field == null) {
			throw new GenericEvitaInternalError(
				"Field `" + fieldName + "` no longer exists on " + instance.getClass().getName() +
					" - the exclusion list in this test is stale and would silently stop subtracting it."
			);
		}
		field.setAccessible(true);
		try {
			return field.get(instance);
		} catch (IllegalAccessException e) {
			throw new GenericEvitaInternalError("Cannot read field `" + fieldName + "` for exclusion.", e);
		}
	}

	/**
	 * Measures what an index really occupies, subtracting everything it deliberately does not charge.
	 *
	 * @param index          the index to walk
	 * @param excludedFields the fields holding objects the index reaches but deliberately does not charge
	 * @return the measured footprint in bytes
	 */
	public static long measuredHeapOf(@Nonnull Object index, @Nonnull String... excludedFields) {
		return measuredHeapOf(index, new Object[0], excludedFields);
	}

	/**
	 * As above, additionally subtracting objects no single field points at — an interned {@link java.util.Locale}
	 * reached from inside a charged map, or the price record bodies a reduced index borrows from a super index.
	 *
	 * Name a root only where the structure genuinely borrows what it reaches. A JVM-shared instance in particular is
	 * charged once by a walk and once **per holder** by the arithmetic, so naming one where nothing contends for it
	 * does not remove a divergence — it creates one.
	 *
	 * @param index          the index to walk
	 * @param extraRoots     shared objects reached from inside a charged structure
	 * @param excludedFields the fields holding objects the index reaches but deliberately does not charge
	 * @return the measured footprint in bytes
	 */
	public static long measuredHeapOf(
		@Nonnull Object index,
		@Nonnull Object[] extraRoots,
		@Nonnull String... excludedFields
	) {
		final List<Object> roots = new ArrayList<>(
			extraRoots.length + excludedFields.length + SHARED_EMPTY_ARRAYS.length
		);
		roots.addAll(List.of(excluded(index, excludedFields)));
		roots.addAll(List.of(extraRoots));
		roots.addAll(List.of(SHARED_EMPTY_ARRAYS));
		return JolHeapSize.ownedSize(index, roots.toArray());
	}

	/**
	 * Asserts that an index's own arithmetic matches a JOL walk that subtracts everything it does not own.
	 *
	 * @param reported       what the index says it occupies
	 * @param index          the index to walk
	 * @param excludedFields the fields holding objects the index reaches but deliberately does not charge
	 */
	public static void assertMatchesMeasuredHeap(
		long reported,
		@Nonnull Object index,
		@Nonnull String... excludedFields
	) {
		assertMatchesMeasuredHeap(reported, index, new Object[0], excludedFields);
	}

	/**
	 * As above, for an index that also reaches shared objects no single field points at — the interned
	 * {@link java.util.Locale}s scattered through both sides of a global unique index's locale maps, for one.
	 *
	 * @param reported       what the index says it occupies
	 * @param index          the index to walk
	 * @param extraRoots     shared objects reached from inside a charged structure
	 * @param excludedFields the fields holding objects the index reaches but deliberately does not charge
	 */
	public static void assertMatchesMeasuredHeap(
		long reported,
		@Nonnull Object index,
		@Nonnull Object[] extraRoots,
		@Nonnull String... excludedFields
	) {
		assertEquals(measuredHeapOf(index, extraRoots, excludedFields), reported);
	}

	/**
	 * Asserts that an index's arithmetic sits exactly `expectedExcess` bytes above a JOL walk.
	 *
	 * Every divergence in this layer is deliberate and has a known magnitude rather than a vague direction — so they
	 * are pinned with the number, not waved through with a `>=`. An assertion that only said "at least as much" would
	 * keep passing if the arithmetic drifted by a kilobyte.
	 *
	 * @param reported       what the index says it occupies
	 * @param expectedExcess how far above the measurement the arithmetic is expected to sit, and why
	 * @param index          the index to walk
	 * @param excludedFields the fields holding objects the index reaches but deliberately does not charge
	 */
	public static void assertExceedsMeasuredHeapBy(
		long reported,
		long expectedExcess,
		@Nonnull Object index,
		@Nonnull String... excludedFields
	) {
		assertExceedsMeasuredHeapBy(reported, expectedExcess, index, new Object[0], excludedFields);
	}

	/**
	 * As above, for an index that also reaches shared objects no single field points at.
	 *
	 * @param reported       what the index says it occupies
	 * @param expectedExcess how far above the measurement the arithmetic is expected to sit, and why
	 * @param index          the index to walk
	 * @param extraRoots     shared objects reached from inside a charged structure
	 * @param excludedFields the fields holding objects the index reaches but deliberately does not charge
	 */
	public static void assertExceedsMeasuredHeapBy(
		long reported,
		long expectedExcess,
		@Nonnull Object index,
		@Nonnull Object[] extraRoots,
		@Nonnull String... excludedFields
	) {
		assertEquals(measuredHeapOf(index, extraRoots, excludedFields) + expectedExcess, reported);
	}

	/**
	 * Asserts that the gap between two indexes' arithmetic and their measurements is the **same**, however much
	 * more data the second holds.
	 *
	 * This is the property that actually protects the figure. Several deliberate divergences in this layer have a
	 * fixed cost — a pre-sized table, an enum constant the walker charges, a formula's cost bookkeeping — and none
	 * of them matters. What would matter is a *per-element* term going uncharged, and that is exactly what a gap
	 * growing with the data would reveal. Pinning the gap's constancy catches it; pinning its value would only
	 * record whichever fixture happened to be written first.
	 *
	 * @param smallReported  the smaller index's own figure
	 * @param small          the smaller index
	 * @param largeReported  the larger index's own figure
	 * @param large          the larger index, holding materially more data
	 * @param excludedFields the fields holding objects neither index charges
	 */
	public static void assertDivergenceDoesNotGrowWithTheData(
		long smallReported,
		@Nonnull Object small,
		long largeReported,
		@Nonnull Object large,
		@Nonnull String... excludedFields
	) {
		assertEquals(
			smallReported - measuredHeapOf(small, excludedFields),
			largeReported - measuredHeapOf(large, excludedFields),
			"the gap between arithmetic and measurement must not grow with the data"
		);
	}

}
