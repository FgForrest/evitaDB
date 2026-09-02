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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the measurement helper every heap-size assertion in this repository is built on.
 *
 * # The property being defended
 *
 * {@link JolHeapSize#ownedSize} must return the **same number every time**, whatever else the JVM is doing. That is
 * not a nicety: the whole suite runs under surefire `parallel=all`, so a measurement that drifts under concurrent
 * load turns correct production arithmetic into an intermittent failure, and the natural response to such a failure
 * — relaxing the assertion — quietly destroys the only thing that makes these tests worth having.
 *
 * Three hazards were found the hard way and are pinned here:
 *
 * - JOL's own `GraphLayout.subtract` matches objects **by address**, so a GC between two walks leaves shared objects
 *   unsubtracted and inflates the result by a different amount each run. The helper subtracts by identity instead.
 * - Walking into a `Class` reaches its **lazily populated** reflection cache, which materialises the first time
 *   anything reflects on that class. That made a figure depend on which tests had run before it. The helper excludes
 *   `Class` objects and everything below them.
 * - Walking into a `Locale` reaches the language tag `Locale#toLanguageTag` memoises into it — 48 bytes that any
 *   code in the fork can materialise at any moment, including *between* two walks a test is comparing. The helper
 *   excludes locales and everything below them, for the reasons {@link JolHeapSize} sets out in full.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("JOL heap-size measurement")
class JolHeapSizeTest {

	/**
	 * A graph deep enough to be worth walking, holding objects of several shapes.
	 *
	 * @return a freshly built fixture
	 */
	@Nonnull
	private static List<Object> fixture() {
		final List<Object> graph = new ArrayList<>(64);
		for (int i = 0; i < 64; i++) {
			graph.add(new long[]{i, i + 1, i + 2});
			graph.add("element-" + i);
			graph.add(UUID.nameUUIDFromBytes(new byte[]{(byte) i}));
		}
		return graph;
	}

	@Nested
	@DisplayName("returns the same figure under any heap conditions")
	class Deterministic {

		@Test
		void shouldReturnAnIdenticalFigureAcrossRepeatedCalls() {
			final List<Object> graph = fixture();

			final long first = JolHeapSize.ownedSize(graph);
			for (int i = 0; i < 20; i++) {
				assertEquals(first, JolHeapSize.ownedSize(graph), "measurement drifted on repeat " + i);
			}
		}

		@Test
		void shouldReturnAnIdenticalFigureWhileAnotherThreadChurnsTheHeap() throws Exception {
			final List<Object> graph = fixture();
			final long quiet = JolHeapSize.ownedSize(graph);

			// the failure mode this defends against needs a collector that MOVES objects mid-measurement, so the
			// churn has to be real: a second thread allocating hard enough to force repeated young collections
			// while the walk is running
			final AtomicBoolean churning = new AtomicBoolean(true);
			final Thread allocator = new Thread(() -> {
				while (churning.get()) {
					final byte[][] garbage = new byte[256][];
					for (int i = 0; i < garbage.length; i++) {
						garbage[i] = new byte[4096];
					}
				}
			}, "heap-churn");
			allocator.setDaemon(true);
			allocator.start();
			try {
				for (int i = 0; i < 20; i++) {
					assertEquals(
						quiet,
						JolHeapSize.ownedSize(graph),
						"measurement drifted under concurrent allocation on repeat " + i
					);
				}
			} finally {
				churning.set(false);
				allocator.join(5_000);
			}
		}

		@Test
		void shouldReturnAnIdenticalFigureAcrossExplicitCollections() {
			final List<Object> graph = fixture();
			final long before = JolHeapSize.ownedSize(graph);

			for (int i = 0; i < 5; i++) {
				System.gc();
				assertEquals(before, JolHeapSize.ownedSize(graph), "measurement drifted across a collection");
			}
		}
	}

	@Nested
	@DisplayName("never charges for JVM-owned class metadata")
	class ClassMetadataExcluded {

		@Test
		void shouldNotChargeTheReflectionCacheOfAReachableClass() {
			// a Class reference is JVM-owned: the holder pays for its slot, never for the class object, and never
			// for the reflection data that appears behind it the moment anything reflects on that class
			final Object holder = new Object[]{String.class};
			final long beforeReflection = JolHeapSize.ownedSize(holder);

			// materialise the reflection cache the way a real run would, then measure again
			assertTrue(String.class.getDeclaredFields().length > 0);
			final long afterReflection = JolHeapSize.ownedSize(holder);

			assertEquals(
				beforeReflection,
				afterReflection,
				"reflecting on a reachable class must not change what its holder is charged"
			);
		}

		@Test
		void shouldChargeOnlyTheArrayItselfForAnArrayOfClasses() {
			final Object[] classes = {String.class, Integer.class, UUID.class};

			// three reference slots and an array header - nothing else, however heavy those three classes are
			assertEquals(JolHeapSize.shallowSize(classes), JolHeapSize.ownedSize(classes));
		}
	}

	@Nested
	@DisplayName("never charges for a JDK flyweight the arithmetic prices at zero")
	class JvmFlyweightsExcluded {

		/**
		 * A locale nothing else in the JVM uses, so its memoised language tag is guaranteed unmaterialised when the
		 * first reading is taken.
		 *
		 * @return a locale built fresh for one assertion
		 */
		@Nonnull
		private Locale untouchedLocale() {
			return new Locale("zz", "ZZ", "heapSizeProbe" + UUID.randomUUID());
		}

		@Test
		void shouldChargeOnlyTheArrayItselfForAnArrayOfLocales() {
			final Object[] locales = {Locale.ENGLISH, Locale.GERMAN, untouchedLocale()};

			// three reference slots and an array header. A locale is priced at zero by `EvitaDataTypes#estimateSize`
			// - "flyweights owned by the JVM" - so a walk that charged one would accuse correct arithmetic of
			// under-counting, and it charges neither the locale nor the interned `BaseLocale` beneath it
			assertEquals(JolHeapSize.shallowSize(locales), JolHeapSize.ownedSize(locales));
		}

		@Test
		void shouldNotChargeTheLanguageTagOfAReachableLocale() {
			// `Locale#toLanguageTag` memoises its result INTO the locale, and every locale an index holds is shared
			// with the rest of the JVM. Whether those 48 bytes exist is therefore a property of the fork's history,
			// not of the structure being measured - and two walks of one structure taken either side of a concurrent
			// test that touches the locale would otherwise disagree by exactly that much
			final Locale probe = untouchedLocale();
			final Object holder = new Object[]{probe};
			final long beforeTag = JolHeapSize.ownedSize(holder);

			assertTrue(probe.toLanguageTag().length() > 0);
			final long afterTag = JolHeapSize.ownedSize(holder);

			assertEquals(
				beforeTag,
				afterTag,
				"materialising a reachable locale's language tag must not change what its holder is charged"
			);
		}

		@Test
		void shouldFindNoLazyCacheInTheOtherZeroPricedFlyweights() {
			// A TRIPWIRE, not a measurement. `EvitaDataTypes#estimateSize` prices `Currency`, enum constants and an
			// interned `ZoneOffset` at zero for the same reason it prices a `Locale` at zero - yet only the locale is
			// in `JVM_FLYWEIGHT`, because only the locale memoises anything into itself. The other three are
			// immutable once constructed, so naming them as borrowed roots is sufficient and the call sites that do
			// so are correct as they stand.
			//
			// That claim is a property of the JDK, not of this repository, and a future JDK could add a cached field
			// to any of them - at which point the exact defect this suite just fixed would reappear somewhere else,
			// wearing a different byte count. This test is the observation that would say so: it exercises the
			// accessors most likely to memoise and asserts the holder's charge does not move. If it ever fails, the
			// named type has acquired a lazily populated field and belongs in `JolHeapSize#JVM_FLYWEIGHT`.
			final Object holder = new Object[]{
				Currency.getInstance("CZK"), ZoneOffset.UTC, DayOfWeek.MONDAY
			};
			final long before = JolHeapSize.ownedSize(holder);

			assertNotNull(Currency.getInstance("CZK").getSymbol(Locale.ENGLISH));
			assertNotNull(Currency.getInstance("CZK").getDisplayName(Locale.ENGLISH));
			assertNotNull(ZoneOffset.UTC.getId());
			assertNotNull(ZoneOffset.UTC.getRules());
			assertNotNull(DayOfWeek.MONDAY.toString());

			assertEquals(
				before,
				JolHeapSize.ownedSize(holder),
				"a zero-priced flyweight outside JVM_FLYWEIGHT has started caching into itself - it now needs the " +
					"opaque boundary, for the reason JolHeapSize documents for Locale"
			);
		}

		@Test
		void shouldStillChargeALocaleAskedAboutDirectly() {
			// a flyweight is free to the structures that merely REFERENCE it, not to a caller who names it as the
			// thing being measured - `ownedSize(x)` always charges x's own shell, whatever x is
			assertEquals(JolHeapSize.shallowSize(Locale.ENGLISH), JolHeapSize.ownedSize(Locale.ENGLISH));
		}
	}

	@Nested
	@DisplayName("can enter the shapes the index graphs are built from")
	class MeasurableShapes {

		/**
		 * A record, the shape `Unsafe.objectFieldOffset` refuses outright.
		 *
		 * @param alpha a primitive component
		 * @param beta  a reference component
		 */
		private record ProbeRecord(int alpha, String beta) {
		}

		@Test
		void shouldMeasureARecord() {
			// JOL cannot resolve field offsets on a record unless `-Djol.magicFieldOffset=true` is on the surefire
			// argLine, and records are everywhere in the index graphs - AttributeIndexKey, RepresentativeReferenceKey,
			// ComparatorSource, ChainDescriptor. Without the flag this throws "Cannot get the field offset", and
			// SortIndex, ChainIndex, AttributeIndex and every entity index above them become unverifiable. This test
			// exists so removing the flag fails here, loudly, instead of quietly disabling half the heap-size suite
			final ProbeRecord probe = new ProbeRecord(1, "beta");

			// header + int + reference, padded - the same arithmetic any other object of that shape gets
			final VMLayout layout = VMLayout.current();
			assertEquals(
				layout.sizeOfObject(Integer.BYTES + layout.referenceSize()),
				JolHeapSize.shallowSize(probe)
			);
			// and the walk enters it, reaching the String and its byte[]
			assertEquals(
				JolHeapSize.shallowSize(probe) + JolHeapSize.ownedSize(probe.beta()),
				JolHeapSize.ownedSize(probe)
			);
		}

		@Test
		void shouldMeasureABoundMethodReference() {
			// the other refused shape: a method reference that captured a receiver. `EntityCollection` builds its
			// index maps with exactly this (`EntityIndex.class::cast`)
			final Function<Object, Object> bound = String.class::cast;

			// the captured receiver is a Class, which the walk excludes by path - so only the lambda object remains
			assertEquals(JolHeapSize.shallowSize(bound), JolHeapSize.ownedSize(bound));
			assertTrue(JolHeapSize.ownedSize(bound) > 0);
		}
	}

	@Nested
	@DisplayName("subtracts borrowed roots by identity")
	class BorrowedRoots {

		@Test
		void shouldSubtractABorrowedSubgraphButNotAnEqualOne() {
			final long[] borrowed = {1L, 2L, 3L};
			final Object[] holder = {borrowed};

			// the borrowed array is subtracted, leaving only the holder
			assertEquals(JolHeapSize.shallowSize(holder), JolHeapSize.ownedSize(holder, borrowed));

			// an EQUAL but distinct array is not the same object, so naming it subtracts nothing - identity is the
			// whole point, and an equals-based implementation would silently under-report here
			final long[] equalButDistinct = {1L, 2L, 3L};
			assertEquals(
				JolHeapSize.ownedSize(holder),
				JolHeapSize.ownedSize(holder, equalButDistinct)
			);
		}
	}
}
