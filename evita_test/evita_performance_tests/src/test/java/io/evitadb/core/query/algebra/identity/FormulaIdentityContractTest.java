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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spike.mock.MockEntityIdsFormula;
import io.evitadb.spike.mock.MockInnerRecordIdsFormula;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.identity.IdentityContractVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Applies the shared identity contract to holders declared in the performance module's main sources.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Performance formula identity contract discovery")
@Tag(ENGINE)
@Tag(CACHE)
class FormulaIdentityContractTest implements EvitaTestSupport {
	/** Minimum number of benchmark holders; covers the two benchmark formula implementations. */
	private static final int PERFORMANCE_FLOOR = 2;

	/** Discovers every benchmark holder and checks the same contract as the functional tests. */
	@Test
	@DisplayName("Discovers benchmark holders and verifies their identity declarations")
	void shouldVerifyEveryDiscoveredHolder() {
		final Set<Class<?>> holders = IdentityContractVerifier.discover(getRootDirectory().resolve(
			"evita_test/evita_performance_tests/src/main/java"
		), getRootDirectory().resolve("evita_engine/src/main/java"));
		assertTrue(holders.size() >= PERFORMANCE_FLOOR, "Performance inventory below floor: " + holders.size());
		final int verified = IdentityContractVerifier.verifyInventory(holders, Set.of());
		System.out.println("Identity discovery: performance=" + holders.size() + "/floor=" + PERFORMANCE_FLOOR +
			", verified=" + verified);
	}

	/**
	 * Supplies the bitmap-and-record constructors of the benchmark mocks, as the benchmark states call them.
	 *
	 * @return the mock name and its constructor
	 */
	@Nonnull
	static Stream<Arguments> benchmarkMockConstructors() {
		return Stream.of(
			Arguments.of(
				"MockEntityIdsFormula",
				(BiFunction<Bitmap, FilteredPriceRecords, Formula>) MockEntityIdsFormula::new
			),
			Arguments.of(
				"MockInnerRecordIdsFormula",
				(BiFunction<Bitmap, FilteredPriceRecords, Formula>) MockInnerRecordIdsFormula::new
			)
		);
	}

	/**
	 * The measurement states compose the benchmark mocks under real formulas, so a mock must be initialized by its
	 * constructor alone.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Benchmark mock initialization")
	class BenchmarkMockInitializationTest {

		/** A real parent formula reads the hash, cost and result of its children when it is built and computed. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.core.query.algebra.identity.FormulaIdentityContractTest#benchmarkMockConstructors")
		@DisplayName("Constructs mocks a real parent formula can estimate and compute")
		void shouldComposeConstructedMockUnderRealFormula(
			@Nonnull String mockName,
			@Nonnull BiFunction<Bitmap, FilteredPriceRecords, Formula> constructor
		) {
			final Formula mock = constructor.apply(new BaseBitmap(1, 2), new ResolvedFilteredPriceRecords());
			final Formula parent = new AndFormula(mock, new ConstantFormula(new BaseBitmap(2, 3)));

			assertTrue(parent.getEstimatedCost() > 0, mockName);
			assertArrayEquals(new int[]{2}, parent.compute().getArray(), mockName);
		}
	}
}
