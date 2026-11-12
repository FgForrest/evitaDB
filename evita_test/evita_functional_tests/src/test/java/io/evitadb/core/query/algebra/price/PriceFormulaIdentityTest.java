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

package io.evitadb.core.query.algebra.price;

import io.evitadb.core.query.algebra.price.filteredPriceRecords.PriceBetweenFormula;
import io.evitadb.core.query.algebra.price.innerRecordHandling.PriceHandlingContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceListCombinationFormula;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PlainPriceTerminationFormulaWithPriceFilter;
import io.evitadb.core.query.algebra.price.termination.PriceFilteringEnvelopeContainer;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.filter.translator.price.alternative.SellingPriceAvailableBitmapFilter;
import io.evitadb.test.identity.IdentityContractVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks each price holder independently, including all of its real construction modes and clone paths.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Price formula identity contracts")
@Tag(ENGINE)
@Tag(PRICE)
class PriceFormulaIdentityTest {
	/** Every price formula and its prefetch alternative participates in the shared contract. */
	@TestFactory
	@DisplayName("Verifies each price identity independently")
	@Nonnull
	List<DynamicTest> shouldVerifyEveryPriceIdentity() {
		final Class<?>[] types = {
			PriceBetweenFormula.class,
			PriceHandlingContainerFormula.class,
			PriceIdContainerFormula.class,
			PriceIndexContainerFormula.class,
			PriceIdToEntityIdTranslateFormula.class,
			PlainPriceTerminationFormula.class,
			PriceListCombinationFormula.class,
			LowestPriceTerminationFormula.class,
			PlainPriceTerminationFormulaWithPriceFilter.class,
			PriceFilteringEnvelopeContainer.class,
			SumPriceTerminationFormula.class,
			SellingPriceAvailableBitmapFilter.class
		};
		final List<DynamicTest> tests = new ArrayList<>(types.length);
		for (final Class<?> type : types) {
			tests.add(DynamicTest.dynamicTest(type.getSimpleName(),
				() -> assertTrue(IdentityContractVerifier.verifyDiscovered(type))));
		}
		return tests;
	}
}
