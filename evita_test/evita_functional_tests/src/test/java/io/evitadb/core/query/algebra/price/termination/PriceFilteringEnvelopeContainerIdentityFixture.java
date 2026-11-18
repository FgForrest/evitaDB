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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.test.identity.*;
import javax.annotation.Nonnull;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Map;
import java.util.Set;

import static io.evitadb.core.query.algebra.price.PriceIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and clone contracts of {@link PriceFilteringEnvelopeContainer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceFilteringEnvelopeContainerIdentityFixture
	implements IdentityFixture<PriceFilteringEnvelopeContainer> {
	/** Covers the holder's constructor and factory modes with independently changed inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<PriceFilteringEnvelopeContainer>> modes() {
		final Currency currency = Currency.getInstance("CZK");
		final OffsetDateTime moment = OffsetDateTime.parse("2026-01-01T00:00:00Z");
		return Map.of("constructor", new ConstructionMode<>(() -> new PriceFilteringEnvelopeContainer(new String[]{"basic"},
			currency, moment, leaf(1), leaf(2)),
				Map.of(
					"lists", () -> new PriceFilteringEnvelopeContainer(new String[]{"other"}, currency, moment, leaf(1), leaf(2)),
					"no lists", () -> new PriceFilteringEnvelopeContainer(null, currency, moment, leaf(1), leaf(2)),
					"currency", () -> new PriceFilteringEnvelopeContainer(new String[]{"basic"},
						Currency.getInstance("EUR"), moment, leaf(1), leaf(2)),
					"moment", () -> new PriceFilteringEnvelopeContainer(new String[]{"basic"}, currency,
						moment.plusNanos(1), leaf(1), leaf(2))
				), Map.of(), Map.of(),
				Map.of("equal children", new ClonePath<>(
					formula -> formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), UNEQUAL)), Set.of(), Set.of(),
						Set.of()));
	}
}
