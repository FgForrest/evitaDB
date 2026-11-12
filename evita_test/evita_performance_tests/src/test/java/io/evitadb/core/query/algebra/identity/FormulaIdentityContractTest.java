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

import io.evitadb.spike.mock.MockInnerRecordIdsFormula;
import io.evitadb.spike.mock.MockEntityIdsFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.identity.IdentityContractVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
		final int declared = IdentityContractVerifier.verifyInventory(holders, Set.of());
		System.out.println("Identity discovery: performance=" + holders.size() + "/floor=" + PERFORMANCE_FLOOR +
			", declared=" + declared + ", undeclared=" + (holders.size() - declared));
	}
	/** Uses the bitmap-and-record constructor used by the benchmark states. */
	@Test
	@DisplayName("Initializes EntityIds benchmark formulas with stable instance-unique opaque keys")
	void shouldInitializeEntityIdsFormulaWithOpaqueKeys() {
		final Bitmap bitmap = new BaseBitmap(1);
		final FilteredPriceRecords records = new ResolvedFilteredPriceRecords();
		final MockEntityIdsFormula formula = new MockEntityIdsFormula(bitmap, records);
		assertSame(formula.getKey(), formula.getKey());
		assertNotEquals(formula.getKey(), new MockEntityIdsFormula(bitmap, records).getKey());
		assertTrue(formula.getKey().isOpaque());
		assertTrue(formula.getKey().isQueryLocal());
		assertEquals(formula.getKey().getWeight() + 1,
			new MockEntityIdsFormula(new BaseBitmap(1, 2), records).getKey().getWeight());
		assertTrue(formula.getEstimatedCost() >= 0);
	}

	/** Uses the bitmap-and-record constructor used by the benchmark states. */
	@Test
	@DisplayName("Initializes InnerRecordIds benchmark formulas with stable instance-unique opaque keys")
	void shouldInitializeInnerRecordIdsFormulaWithOpaqueKeys() {
		final Bitmap bitmap = new BaseBitmap(1);
		final FilteredPriceRecords records = new ResolvedFilteredPriceRecords();
		final MockInnerRecordIdsFormula formula = new MockInnerRecordIdsFormula(bitmap, records);
		assertSame(formula.getKey(), formula.getKey());
		assertNotEquals(formula.getKey(), new MockInnerRecordIdsFormula(bitmap, records).getKey());
		assertTrue(formula.getKey().isOpaque());
		assertTrue(formula.getKey().isQueryLocal());
		assertEquals(formula.getKey().getWeight() + 1,
			new MockInnerRecordIdsFormula(new BaseBitmap(1, 2), records).getKey().getWeight());
		assertTrue(formula.getEstimatedCost() >= 0);
	}
}
