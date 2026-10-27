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


package io.evitadb.index.analysis;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.index.analysis.AttributeNormalizationPolicy.ElementBinding;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.removeCharacters;
import static io.evitadb.index.analysis.AttributeNormalizationPolicyHeapSize.estimate;
import static io.evitadb.index.analysis.AttributeNormalizationPolicyHeapSize.estimateGlobalRows;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Relative pricing rules of the owner budget. The estimator is conservative by design, so only orderings are
 * asserted - never byte counts.
 */
@Tag(INDEXING)
@DisplayName("Normalization policy heap size estimate")
class AttributeNormalizationPolicyHeapSizeTest {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey TITLE = new AttributeIndexKey(null, "title", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);

	@Test
	@DisplayName("The shared empty policy costs nothing to the collection")
	void shouldPriceEmptyPolicyAsZero() {
		assertEquals(0L, estimate(AttributeNormalizationPolicy.EMPTY));
	}

	@Test
	@DisplayName("One step list shared by two rows is charged once")
	void shouldChargeSharedStepListOnlyOnce() {
		final List<AnalysisStep> shared = List.of(removeCharacters("-"));
		final AttributeNormalizationPolicy sharing = new AttributeNormalizationPolicy(
			Map.of(CODE, shared, TITLE, shared), Map.of(), Set.of());
		final AttributeNormalizationPolicy distinct = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(removeCharacters("-")), TITLE, List.of(removeCharacters("-"))), Map.of(), Set.of());

		assertTrue(estimate(sharing) < estimate(distinct));
	}

	@Test
	@DisplayName("Compound bindings and adopted global names add to the estimate")
	void shouldChargeCompoundIdentitiesAndGlobalNames() {
		final Map<AttributeIndexKey, List<AnalysisStep>> rows = Map.of(CODE, List.of(lowercase()));
		final AttributeNormalizationPolicy base = new AttributeNormalizationPolicy(rows, Map.of(), Set.of());
		final AttributeNormalizationPolicy withCompound = new AttributeNormalizationPolicy(rows,
			Map.of(COMPOUND, List.of(new ElementBinding("code", String.class, false, false))), Set.of());
		final AttributeNormalizationPolicy withGlobal = new AttributeNormalizationPolicy(rows, Map.of(),
			Set.of("global"));

		assertTrue(estimate(withCompound) > estimate(base));
		assertTrue(estimate(withGlobal) > estimate(base));
	}

	@Test
	@DisplayName("Catalog rows cost nothing when absent and grow with names and parameter text")
	void shouldPriceGlobalRowsGrowingWithKeysAndParameters() {
		final long one = estimateGlobalRows(Map.of("code", List.of(removeCharacters("-"))));
		final long two = estimateGlobalRows(
			Map.of("code", List.of(removeCharacters("-")), "title", List.of(removeCharacters("-"))));
		final long longParameter = estimateGlobalRows(Map.of("code", List.of(removeCharacters("-".repeat(1000)))));

		assertEquals(0L, estimateGlobalRows(Map.of()));
		assertTrue(two > one);
		assertTrue(longParameter > one + 900);
	}

	@Test
	@DisplayName("A reference-scoped row also pays for its reference name")
	void shouldPriceReferenceScopedRows() {
		final AttributeNormalizationPolicy entityRow = new AttributeNormalizationPolicy(
			Map.of(new AttributeIndexKey(null, "tags", null), List.of(lowercase())), Map.of(), Set.of());
		final AttributeNormalizationPolicy referenceRow = new AttributeNormalizationPolicy(
			Map.of(new AttributeIndexKey("brand", "tags", null), List.of(lowercase())), Map.of(), Set.of());

		assertTrue(estimate(referenceRow) > estimate(entityRow));
	}
}
