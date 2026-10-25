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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.trim;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/** The canonical form a policy takes so that committed owners and borrowed resolvers can share it. */
@Tag(INDEXING)
@DisplayName("Built-with normalization policy")
class AttributeNormalizationPolicyTest {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey TITLE = new AttributeIndexKey(null, "title", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);
	private static final ElementBinding CODE_ELEMENT = new ElementBinding("code", String.class, false, false);

	@Test
	@DisplayName("An empty step list is no row, so a policy of empty lists is the empty policy")
	void shouldOmitRowsWithEmptyStepLists() {
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(), TITLE, List.of(lowercase())), Map.of(), Set.of());

		assertEquals(Map.of(TITLE, List.of(lowercase())), policy.rows());
		assertEquals(AttributeNormalizationPolicy.EMPTY,
			new AttributeNormalizationPolicy(Map.of(CODE, List.of()), Map.of(), Set.of()));
	}

	@Test
	@DisplayName("Changing the inputs after construction leaves the policy unchanged")
	void shouldCopyInputsDefensively() {
		final List<AnalysisStep> steps = new ArrayList<>(List.of(lowercase()));
		final Map<AttributeIndexKey, List<AnalysisStep>> rows = new HashMap<>(Map.of(CODE, steps));
		final List<ElementBinding> elements = new ArrayList<>(List.of(CODE_ELEMENT));
		final Map<AttributeIndexKey, List<ElementBinding>> compounds = new HashMap<>(Map.of(COMPOUND, elements));
		final Set<String> globals = new HashSet<>(Set.of("global"));
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(rows, compounds, globals);

		steps.add(trim());
		rows.put(TITLE, List.of(lowercase()));
		elements.clear();
		compounds.clear();
		globals.add("other");

		assertEquals(Map.of(CODE, List.of(lowercase())), policy.rows());
		assertEquals(Map.of(COMPOUND, List.of(CODE_ELEMENT)), policy.compounds());
		assertEquals(Set.of("global"), policy.globalAttributes());
	}

	@Test
	@DisplayName("The rows, bindings and global names it exposes cannot be modified")
	void shouldExposeImmutableViews() {
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(
			Map.of(CODE, List.of(lowercase())), Map.of(COMPOUND, List.of(CODE_ELEMENT)), Set.of("global"));

		assertThrows(UnsupportedOperationException.class, () -> policy.rows().put(TITLE, List.of(trim())));
		assertThrows(UnsupportedOperationException.class, () -> policy.rows().get(CODE).add(trim()));
		assertThrows(UnsupportedOperationException.class, () -> policy.compounds().put(TITLE, List.of()));
		assertThrows(UnsupportedOperationException.class, () -> policy.compounds().get(COMPOUND).clear());
		assertThrows(UnsupportedOperationException.class, () -> policy.globalAttributes().add("other"));
	}
}
