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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a {@link GlobalUniqueIndex} write rolled back by a {@link WarmUpSavepoint} leaves the index FULLY
 * CONSISTENT — not merely "mostly rewound".
 *
 * This index is where a partial rewind stopped being invisible and became a crash. Registering a LOCALIZED unique key
 * writes two structures at once: the value tuple goes into the {@code TransactionalBucketBPlusTree} carrying a compact
 * internal locale id, and that id is minted on the spot into the two {@code TransactionalMap} locale indexes. Before
 * the trees journaled their warm-up writes, a rollback rewound the maps (they were covered) but not the tree, leaving a
 * tuple whose locale id resolved to nothing — and {@code toLocale} is a {@code requireNonNull}, so the very next
 * locale-agnostic lookup of that value threw a {@link NullPointerException} out of a read path. The assertion that
 * pins this is the locale-agnostic lookup below: it is the one that passes the locale filter and therefore reaches
 * {@code toLocale} with whatever id the tuple carries.
 *
 * With the trees rewinding, the two structures move together again and the ONLY thing the rollback does not undo is
 * the locale-id SEQUENCE, which is an {@code AtomicInteger} and deliberately not journaled — the same accepted residue
 * as the primary-key and price-id sequences under an ALIVE savepoint. That is asserted too, through the emitted
 * storage part: the locale registered after the rollback receives id 2, because id 1 was burned by the rolled-back
 * one. A burned id is a harmless gap; a burned id still referenced by a surviving tuple was the crash.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("A rolled-back warm-up write leaves GlobalUniqueIndex consistent")
class WarmUpSavepointGlobalUniqueIndexConsistencyTest {
	private static final AttributeKey URL_KEY = new AttributeKey("url");

	private final Catalog catalog = Mockito.mock(Catalog.class);
	/**
	 * Resolves the entity type name to its compact primary key and back, delegating to the mocked catalog exactly as
	 * {@link GlobalUniqueIndexTest} does.
	 */
	private final EntityTypeClassifierResolver classifierResolver = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return WarmUpSavepointGlobalUniqueIndexConsistencyTest.this.catalog
				.getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return WarmUpSavepointGlobalUniqueIndexConsistencyTest.this.catalog
				.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
		}
	};

	/**
	 * Emits the index's storage parts and returns the root, which carries the inline locale-id map in both the
	 * `SINGLE` and the `PAGED` shape.
	 *
	 * @param index the index to flush
	 * @return the emitted root storage part
	 */
	@Nonnull
	private static GlobalUniqueIndexStoragePart emitRoot(@Nonnull GlobalUniqueIndex index) {
		final TrappedChanges changes = new TrappedChanges();
		index.appendStorageParts(URL_KEY, changes);
		final Iterator<StoragePart> emittedParts = changes.getTrappedChangesIterator();
		while (emittedParts.hasNext()) {
			final StoragePart part = emittedParts.next();
			if (part instanceof GlobalUniqueIndexStoragePart root) {
				return root;
			}
		}
		return fail("No root storage part was emitted by the flush!");
	}

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1))
			.thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT))
			.thenReturn(productCollection);
	}

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Test
	@DisplayName("Rolling back a localized registration leaves no tuple pointing at a rewound locale id")
	void shouldLeaveNoOrphanedLocaleReferenceAfterRollback() {
		final GlobalUniqueIndex index = new GlobalUniqueIndex(Scope.LIVE, URL_KEY, String.class);
		index.registerUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		index.registerUniqueKey("B", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);
		assertEquals(2, index.size(), "self-check: the localized key was registered inside the savepoint");
		savepoint.rollback();

		// THE assertion: a locale-agnostic lookup passes the locale filter and resolves the tuple's locale id through
		// `toLocale`. A surviving tuple carrying the rewound id would throw a NullPointerException right here
		assertTrue(
			index.getEntityReferenceByUniqueValue("B", null, this.classifierResolver).isEmpty(),
			"The rolled-back value must be gone from the value tree, not left pointing at a rewound locale id."
		);
		assertEquals(1, index.size(), "Rollback must restore the pre-savepoint number of unique keys.");
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
			index.getEntityReferenceByUniqueValue("A", null, this.classifierResolver).orElse(null),
			"The key registered before the savepoint must survive the rollback untouched."
		);

		// the index must be fully writable afterwards, including for a locale it has never seen
		index.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2, this.classifierResolver);
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.FRENCH),
			index.getEntityReferenceByUniqueValue("B", null, this.classifierResolver).orElse(null),
			"A localized key written after the rollback must resolve back to its locale."
		);
		assertEquals(2, index.size(), "The post-rollback write must be visible.");

		// the ONLY thing the rollback leaves advanced is the locale-id sequence: French received id 2 because the
		// rolled-back English registration burned id 1
		final Map<Integer, Locale> localeIndex = emitRoot(index).getLocaleIndex();
		assertEquals(
			Map.of(2, Locale.FRENCH), localeIndex,
			"The rewound locale must be gone from the locale index, and the surviving one must carry the id the " +
				"un-rewound sequence handed out - a harmless gap, and the only residue of the rollback."
		);
	}

	@Test
	@DisplayName("Rolling back a burst that paged the value tree restores the inline shape")
	void shouldRestoreValueTreeShapeAfterPagingBurst() {
		final GlobalUniqueIndex index = new GlobalUniqueIndex(Scope.LIVE, URL_KEY, Integer.class);
		for (int i = 1; i <= 20; i++) {
			index.registerUniqueKey(i, Entities.PRODUCT, null, i, this.classifierResolver);
		}
		assertTrue(index.isEmpty() || !index.isPaged(), "self-check: the seeded tree must still be a single leaf");

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		for (int i = 1000; i < 1600; i++) {
			index.registerUniqueKey(i, Entities.PRODUCT, Locale.GERMAN, i, this.classifierResolver);
		}
		assertTrue(index.isPaged(), "self-check: the in-savepoint burst must have split the value tree");
		savepoint.rollback();

		assertEquals(20, index.size(), "Rollback must restore the pre-savepoint number of unique keys.");
		assertTrue(!index.isPaged(), "Rollback must restore the pre-split single-leaf shape of the value tree.");
		for (int i = 1; i <= 20; i++) {
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, i, null),
				index.getEntityReferenceByUniqueValue(i, null, this.classifierResolver).orElse(null),
				"Every pre-savepoint key must still resolve after the rollback."
			);
		}
		assertEquals(
			Map.of(), emitRoot(index).getLocaleIndex(),
			"The locale minted only by the rolled-back burst must be gone from the locale index."
		);
	}

	@Test
	@DisplayName("Committing keeps the localized registration and its locale id")
	void shouldKeepLocalizedRegistrationOnCommit() {
		final GlobalUniqueIndex index = new GlobalUniqueIndex(Scope.LIVE, URL_KEY, String.class);
		index.registerUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		index.registerUniqueKey("B", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);
		savepoint.commit();

		assertEquals(2, index.size(), "Commit must keep the key registered while the savepoint was open.");
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.ENGLISH),
			index.getEntityReferenceByUniqueValue("B", null, this.classifierResolver).orElse(null),
			"The committed localized key must resolve back to its locale."
		);
		assertEquals(
			Map.of(1, Locale.ENGLISH), emitRoot(index).getLocaleIndex(),
			"Commit must keep the locale id minted while the savepoint was open."
		);
	}

}
