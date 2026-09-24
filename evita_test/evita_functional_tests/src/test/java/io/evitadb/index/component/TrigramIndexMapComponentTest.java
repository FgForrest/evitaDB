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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the storage side of the trigram map's index component: that a POPULATED map still writes nothing and
 * announces nothing, because a trigram index is derived state that the catalog load re-derives from the shared value
 * trees rather than reading back off disk.
 *
 * The component's other two methods are deliberately not re-asserted here, because both are already caught where they
 * would actually fail. A missed `removeLayer` propagation surfaces at commit as a stale-transaction-memory refusal
 * from the layer sweep every `AssertionUtils.assertStateAfterCommit` triggers — `TrigramIndexTest` alone drives that
 * sweep several times. And the emit-nothing decision is pinned end-to-end by `EntityIndexReloadPlanSymmetryTest`,
 * which is what would notice a manifest key appearing with no loader behind it; what is added here is the same
 * decision asserted directly at the component, over a map that actually holds something.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Trigram index map storage component")
class TrigramIndexMapComponentTest {

	private static final int ENTITY_INDEX_PK = 1;

	/**
	 * @return the map shape the global entity index carries, holding one populated index
	 */
	@Nonnull
	private static TransactionalMap<AttributeIndexKey, TrigramIndex> populatedMap() {
		final TransactionalMap<AttributeIndexKey, TrigramIndex> map = new TransactionalMap<>(
			new HashMap<>(), TrigramIndex.class, Function.identity()
		);
		final AttributeIndexKey key = new AttributeIndexKey(null, "name", null);
		final TrigramIndex index = new TrigramIndex(key);
		index.valueCreated(1, "abcd");
		map.put(key, index);
		return map;
	}

	@Test
	@DisplayName("a populated map emits no storage part and announces no manifest key")
	void shouldEmitNoStoragePartAndAnnounceNoManifestKey() {
		// announcing a key would promise a reload path that reads the accelerator back off disk, and there is none:
		// every posting is a function of the shared value tree's distinct values and of the ids that tree already
		// persists, so the load re-derives the whole thing
		final EntityIndexManifest manifest = new EntityIndexManifest();
		final TrappedChanges trappedChanges = new TrappedChanges();

		new TrigramIndexMapComponent(populatedMap())
			.collectModifiedStorageParts(ENTITY_INDEX_PK, manifest, trappedChanges);

		assertEquals(0, trappedChanges.getTrappedChangesCount(), "a derived structure writes nothing");
		assertTrue(manifest.getAttributeKeys().isEmpty());
		assertTrue(manifest.getPriceKeys().isEmpty());
		assertTrue(manifest.getFacetReferencedEntities().isEmpty());
		assertTrue(manifest.getHistogramKeys().isEmpty());
		assertFalse(manifest.isHierarchyPresent());
	}

}
