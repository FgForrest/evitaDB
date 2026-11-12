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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityContractVerifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached computation inputs and locality of {@link PriceHistogramComputer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(HISTOGRAM)
@Tag(CACHE)
@DisplayName("PriceHistogramComputer keys")
class PriceHistogramComputerKeyTest {
	/** Every supplied value input changes both the key and its hash prefilter. */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"bucketCount",
		"behavior",
		"indexedPricePlaces",
		"queryPriceMode",
		"filteringFormula",
		"relaxedBaseline",
		"empty baseline",
		"unchanged baseline"
	})
	@DisplayName("Distinguishes each computation input")
	void shouldDistinguishComputationInput(@Nonnull String input) {
		final ConstructionMode<PriceHistogramComputer> mode =
			new PriceHistogramComputerIdentityFixture().modes().get("request");
		final PriceHistogramComputer base = mode.base().get();
		final PriceHistogramComputer variant = mode.variants().get(input).get();
		assertNotEquals(base.getKey(), variant.getKey());
		assertNotEquals(base.getKey().getHash64(), variant.getKey().getHash64());
	}

	/** The fixture also checks real field transplants and opaque input ownership. */
	@Test
	@DisplayName("Satisfies the identity contract")
	void shouldSatisfyIdentityContract() {
		IdentityContractVerifier.verify(PriceHistogramComputer.class, new PriceHistogramComputerIdentityFixture());
	}
}
