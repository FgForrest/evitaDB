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

package io.evitadb.store.shared;

import io.evitadb.api.statistics.StoragePartGroup;
import io.evitadb.api.statistics.StoragePartKind;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.shared.service.StoragePartRegistry;
import io.evitadb.store.shared.service.StoragePartRegistry.StoragePartRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the classification every registered storage-part type carries - the {@link StoragePartGroup} a storage
 * composition breakdown is grouped by.
 *
 * The classification exists because {@code storagePartType} cannot serve as one: it is the simple class name of a
 * storage part, an open set that grows with every index structure the engine gains, and a client has no way to tell
 * which of those names is an index. This test is what keeps that promise true - a new part type cannot be registered
 * without a group (the record will not compile), and it cannot be classified *differently from what is published*
 * without failing here.
 *
 * The expected mapping is written out in full rather than derived, deliberately. A derived expectation would restate
 * whatever the registries say and could never disagree with them; the point of this table is that changing a
 * classification is a visible, reviewed edit, because it changes what an operator's composition chart means.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Storage part group registration")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class StoragePartGroupRegistrationTest {

	/**
	 * Every storage-part type the engine registers, and the kind of data it holds. Keyed by simple class name because
	 * that is the identity the histogram - and therefore the statistics API - reports a type under.
	 */
	private static final Map<String, StoragePartGroup> EXPECTED_GROUPS = new TreeMap<>(
		Map.ofEntries(
			// entity data - the five parts an entity is decomposed into
			Map.entry("EntityBodyStoragePart", StoragePartGroup.ENTITY_BODY),
			Map.entry("AttributesStoragePart", StoragePartGroup.ATTRIBUTE_DATA),
			Map.entry("AssociatedDataStoragePart", StoragePartGroup.ASSOCIATED_DATA),
			Map.entry("PricesStoragePart", StoragePartGroup.PRICE_DATA),
			Map.entry("ReferencesStoragePart", StoragePartGroup.REFERENCE_DATA),

			// metadata - schemas and headers
			Map.entry("EntitySchemaStoragePart", StoragePartGroup.SCHEMA),
			Map.entry("CatalogSchemaStoragePart", StoragePartGroup.SCHEMA),
			Map.entry("CatalogHeader", StoragePartGroup.HEADER),
			Map.entry("EntityCollectionFileHeader", StoragePartGroup.HEADER),

			// index manifests - an index's own record, and the membership bitmaps evicted out of it
			Map.entry("EntityIndexStoragePart", StoragePartGroup.INDEX_MANIFEST),
			Map.entry("EntityIdsStoragePart", StoragePartGroup.INDEX_MANIFEST),
			Map.entry("CatalogIndexStoragePart", StoragePartGroup.INDEX_MANIFEST),

			// attribute indexes, roots and leaf pages alike
			Map.entry("UniqueIndexStoragePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("FilterIndexStoragePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("SortIndexStoragePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("ChainIndexStoragePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("AttributeCardinalityIndexStoragePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("GlobalUniqueIndexStoragePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("FilterIndexLeafPagePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("RangeIndexLeafPagePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("UniqueIndexLeafPagePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("SortIndexLeafPagePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("ChainIndexLeafPagePart", StoragePartGroup.ATTRIBUTE_INDEX),
			Map.entry("GlobalUniqueIndexLeafPagePart", StoragePartGroup.ATTRIBUTE_INDEX),

			// price indexes
			Map.entry("PriceListAndCurrencySuperIndexStoragePart", StoragePartGroup.PRICE_INDEX),
			Map.entry("PriceListAndCurrencyRefIndexStoragePart", StoragePartGroup.PRICE_INDEX),
			Map.entry("PriceListAndCurrencySuperIndexLeafPagePart", StoragePartGroup.PRICE_INDEX),

			// reference indexes - faceting is charged apart, below
			Map.entry("ReferenceTypeCardinalityIndexStoragePart", StoragePartGroup.REFERENCE_INDEX),
			Map.entry("GroupCardinalityIndexStoragePart", StoragePartGroup.REFERENCE_INDEX),
			Map.entry("ReferenceTypeCardinalityIndexLeafPagePart", StoragePartGroup.REFERENCE_INDEX),

			Map.entry("FacetIndexStoragePart", StoragePartGroup.FACET_INDEX),
			Map.entry("HierarchyIndexStoragePart", StoragePartGroup.HIERARCHY_INDEX),

			// the bucketed histogram indexes a reference schema declares - NOT the `attributeHistogram` /
			// `priceHistogram` extra results, which are computed on the fly and persist nothing
			Map.entry("HistogramIndexStoragePart", StoragePartGroup.REFERENCE_HISTOGRAM_INDEX),
			Map.entry("HistogramCardinalityStoragePart", StoragePartGroup.REFERENCE_HISTOGRAM_INDEX),
			Map.entry("HistogramIndexLeafPagePart", StoragePartGroup.REFERENCE_HISTOGRAM_INDEX),
			Map.entry("HistogramRangeIndexLeafPagePart", StoragePartGroup.REFERENCE_HISTOGRAM_INDEX)
		)
	);

	/**
	 * Loads every storage-part type the engine registers, exactly as the offset index does at runtime.
	 *
	 * @return every registered type, in registry order
	 */
	@Nonnull
	private static List<StoragePartRecord> loadRegisteredParts() {
		final List<StoragePartRecord> parts = new ArrayList<>(64);
		ServiceLoader.load(StoragePartRegistry.class)
			.stream()
			.map(Provider::get)
			.map(StoragePartRegistry::listStorageParts)
			.forEach(parts::addAll);
		return parts;
	}

	@Test
	@DisplayName("Every registered storage part type is classified, and classified as published")
	void shouldClassifyEveryRegisteredStoragePartType() {
		final Collection<StoragePartRecord> registered = loadRegisteredParts();
		assertTrue(
			registered.size() >= EXPECTED_GROUPS.size(),
			"The service loader found fewer types than are expected to exist - is a registry missing from the " +
				"module path? Found: " + registered.size()
		);

		final Map<String, StoragePartGroup> actual = new TreeMap<>();
		for (final StoragePartRecord record : registered) {
			final String name = record.partType().getSimpleName();
			assertNotNull(record.group(), "The type " + name + " was registered without a group");
			actual.put(name, record.group());
		}

		// compared as whole maps so the failure names the type that moved, was added, or was dropped - a per-entry
		// loop would report only the first divergence and stay silent about a type nobody classified at all
		assertEquals(
			EXPECTED_GROUPS, actual,
			"A storage part type was added, removed or reclassified. This changes what an operator's storage " +
				"composition chart means, so the expectation above has to be updated deliberately"
		);
	}

	@Test
	@DisplayName("Every group is carried by at least one registered type")
	void shouldLeaveNoGroupWithoutAType() {
		final EnumSet<StoragePartGroup> unused = EnumSet.allOf(StoragePartGroup.class);
		loadRegisteredParts().forEach(record -> unused.remove(record.group()));

		// the enum is a published wire contract: a value nothing can produce is one every client has to render a
		// label and a colour for, forever, without ever seeing a row of it
		assertTrue(unused.isEmpty(), "These groups are declared but no storage part type carries them: " + unused);
	}

	@Test
	@DisplayName("Each group folds to the kind it is published under")
	void shouldFoldEveryGroupToItsKind() {
		// pinned as a table for the same reason the type mapping above is: the kind is what a client renders as its
		// three-row summary, so moving a group between kinds silently rewrites what that summary means. Asserting
		// that `kind()` merely returns something cannot fail - the enum constructor already requires a non-null kind
		// for every constant that compiles - so it would be a test of the Java language, not of this taxonomy
		final Map<StoragePartGroup, StoragePartKind> expected = new EnumMap<>(
			Map.ofEntries(
				Map.entry(StoragePartGroup.ENTITY_BODY, StoragePartKind.ENTITY_DATA),
				Map.entry(StoragePartGroup.ATTRIBUTE_DATA, StoragePartKind.ENTITY_DATA),
				Map.entry(StoragePartGroup.ASSOCIATED_DATA, StoragePartKind.ENTITY_DATA),
				Map.entry(StoragePartGroup.PRICE_DATA, StoragePartKind.ENTITY_DATA),
				Map.entry(StoragePartGroup.REFERENCE_DATA, StoragePartKind.ENTITY_DATA),
				Map.entry(StoragePartGroup.INDEX_MANIFEST, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.ATTRIBUTE_INDEX, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.PRICE_INDEX, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.REFERENCE_INDEX, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.FACET_INDEX, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.HIERARCHY_INDEX, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.REFERENCE_HISTOGRAM_INDEX, StoragePartKind.INDEX),
				Map.entry(StoragePartGroup.SCHEMA, StoragePartKind.METADATA),
				Map.entry(StoragePartGroup.HEADER, StoragePartKind.METADATA)
			)
		);

		final Map<StoragePartGroup, StoragePartKind> actual = new EnumMap<>(StoragePartGroup.class);
		for (final StoragePartGroup group : StoragePartGroup.values()) {
			actual.put(group, group.kind());
		}

		assertEquals(expected, actual, "A group was added or moved between kinds");
	}

	@Test
	@DisplayName("Two types may not be registered under one simple class name")
	void shouldRefuseTwoTypesSharingASimpleName() {
		final OffsetIndexRecordTypeRegistry registry = new OffsetIndexRecordTypeRegistry();

		// the histogram identifies a record type by its simple name, so a second type wearing an already-registered
		// one would silently merge two rows into one that names only half of what it reports. The duplicate here is
		// a locally declared class, because every production type is already registered exactly once
		assertThrows(
			GenericEvitaInternalError.class,
			() -> registry.registerFileOffsetIndexType(
				(byte) 98, EntityBodyStoragePart.class, StoragePartGroup.ENTITY_BODY
			),
			"Re-registering a type must be refused"
		);
		assertThrows(
			GenericEvitaInternalError.class,
			() -> registry.registerFileOffsetIndexType(
				(byte) 98, Shadow.EntityBodyStoragePart.class, StoragePartGroup.ENTITY_BODY
			),
			"A second type wearing an already-registered simple class name must be refused"
		);
		assertEquals(
			EntityBodyStoragePart.class.getSimpleName(),
			Shadow.EntityBodyStoragePart.class.getSimpleName(),
			"The collision this test relies on has to be a real one - both classes must wear the same simple name"
		);
	}

	@Test
	@DisplayName("An unregistered type name has no group rather than a default one")
	void shouldRefuseToGuessAGroupForAnUnknownType() {
		final OffsetIndexRecordTypeRegistry registry = new OffsetIndexRecordTypeRegistry();

		// a fallback group would be indistinguishable from a real classification in the composition table, which is
		// exactly the failure the declared classification replaces
		assertThrows(
			GenericEvitaInternalError.class,
			() -> registry.groupFor("NoSuchStoragePart"),
			"An unknown record type must raise rather than be bucketed somewhere plausible"
		);
	}

	/**
	 * Holder whose only purpose is to let a class carry the simple name {@code EntityBodyStoragePart} without
	 * shadowing the real one in this file. Two different classes wearing one simple name is precisely the collision
	 * registration has to refuse.
	 */
	private static final class Shadow {

		/**
		 * A storage part whose simple class name deliberately collides with the real
		 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart}.
		 */
		private static class EntityBodyStoragePart implements StoragePart {
			@Serial private static final long serialVersionUID = 1L;

			@Nonnull
			@Override
			public Long getStoragePartPK() {
				return 1L;
			}

			@Override
			public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
				return 1L;
			}
		}

	}

}
