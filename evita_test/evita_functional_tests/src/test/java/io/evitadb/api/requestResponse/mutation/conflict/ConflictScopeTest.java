/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.mutation.conflict;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.EnumSet;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the {@link ConflictKey#conflictScope()} mapping for every conflict-key implementation. The mapping is the
 * source of the exported conflict-metric scope label, so a silent change here would silently reshape an
 * observability contract; the exhaustiveness check additionally guards against a newly added key type being
 * forgotten (its scope must appear) or an orphaned {@link ConflictScope} constant that no key produces.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ConflictKey#conflictScope mapping")
@Tag(CONTRACT)
@Tag(TRANSACTION)
class ConflictScopeTest {

	private static final AttributeKey ATTRIBUTE_KEY = new AttributeKey("code");
	private static final ReferenceKey REFERENCE_KEY = new ReferenceKey("category", 100);

	@Test
	@DisplayName("should map every conflict-key implementation to its bounded scope")
	void shouldMapEveryConflictKeyToItsScope() {
		assertEquals(ConflictScope.CATALOG, new CatalogConflictKey("catalog").conflictScope());
		assertEquals(ConflictScope.COLLECTION, new CollectionConflictKey("Product").conflictScope());
		assertEquals(ConflictScope.ENTITY, new EntityConflictKey("Product", 1).conflictScope());
		assertEquals(ConflictScope.ATTRIBUTE, new AttributeConflictKey("Product", 1, "code").conflictScope());
		assertEquals(
			ConflictScope.ASSOCIATED_DATA,
			new AssociatedDataConflictKey("Product", 1, "gallery").conflictScope()
		);
		assertEquals(
			ConflictScope.PRICE,
			new PriceConflictKey("Product", 1, 7, Currency.getInstance("EUR"), "basic").conflictScope()
		);
		assertEquals(
			ConflictScope.PRICE_INNER_RECORD_HANDLING,
			new PriceInnerRecordHandlingStrategyConflictKey("Product", 1).conflictScope()
		);
		assertEquals(ConflictScope.HIERARCHY, new HierarchyConflictKey("Category", 1).conflictScope());
		assertEquals(
			ConflictScope.REFERENCE,
			new ReferenceConflictKey("Product", 1, "category", 100).conflictScope()
		);
		assertEquals(
			ConflictScope.REFERENCE_ATTRIBUTE,
			new ReferenceAttributeConflictKey("Product", 1, "category", 100, "priority").conflictScope()
		);
	}

	@Test
	@DisplayName("should collapse the range-constrained delta keys onto their absolute scope")
	void shouldCollapseDeltaKeysOntoAbsoluteScope() {
		assertEquals(
			ConflictScope.ATTRIBUTE,
			new AttributeDeltaConflictKey("Product", 1, ATTRIBUTE_KEY, 5, null, false).conflictScope()
		);
		assertEquals(
			ConflictScope.REFERENCE_ATTRIBUTE,
			new ReferenceAttributeDeltaConflictKey("Product", 1, REFERENCE_KEY, ATTRIBUTE_KEY, 5, null, false)
				.conflictScope()
		);
	}

	@Test
	@DisplayName("should have at least one conflict key producing each scope")
	void shouldCoverEveryScope() {
		final EnumSet<ConflictScope> produced = EnumSet.of(
			new CatalogConflictKey("catalog").conflictScope(),
			new CollectionConflictKey("Product").conflictScope(),
			new EntityConflictKey("Product", 1).conflictScope(),
			new AttributeConflictKey("Product", 1, "code").conflictScope(),
			new AssociatedDataConflictKey("Product", 1, "gallery").conflictScope(),
			new PriceConflictKey("Product", 1, 7, Currency.getInstance("EUR"), "basic").conflictScope(),
			new PriceInnerRecordHandlingStrategyConflictKey("Product", 1).conflictScope(),
			new HierarchyConflictKey("Category", 1).conflictScope(),
			new ReferenceConflictKey("Product", 1, "category", 100).conflictScope(),
			new ReferenceAttributeConflictKey("Product", 1, "category", 100, "priority").conflictScope()
		);
		assertEquals(EnumSet.allOf(ConflictScope.class), produced);
	}

}
