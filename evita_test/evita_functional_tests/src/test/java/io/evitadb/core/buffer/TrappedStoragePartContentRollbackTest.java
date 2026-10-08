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

package io.evitadb.core.buffer;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.core.buffer.DataStoreChanges.DataStoreChangesMemento;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that rewinding {@link DataStoreChanges} restores the CONTENT of a trapped storage part, not merely the
 * slot that holds it.
 *
 * The layer hands a trapped part out by reference, and the storage executor mutates that instance in place, so
 * putting the slot back to the instance it held restores nothing. The rewind must install the content the part had
 * before the savepoint handed it out — its pre-image, see {@link DataStoreChanges#journalTrappedContent(long, Class)}.
 * These tests drive the three ways a mutation can otherwise leave its changes in a trapped part, and check what a
 * later reader gets:
 *
 * - the part is changed in place and the mutation fails BEFORE its executor commits — the slot never changes;
 * - the part is changed in place, its executor commits (re-trapping the same instance), and a LATER executor fails —
 *   the slot is put back to the very instance that was changed;
 * - the same through the transactional memento instead of the warm-up savepoint.
 *
 * Around them: a part the storage executor fetched before the savepoint opened, which is covered only by journalling
 * it explicitly; a committed savepoint, which must keep the change; and slots that hold no live part, which the
 * journal must leave alone.
 *
 * A real {@link EntityBodyStoragePart} is used rather than a stand-in, because the point is precisely that a real part
 * is mutable in place. The store is the map-backed {@link InMemoryStoragePartPersistenceService}; it is never read
 * here, because every read is served from the trap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(TRANSACTION)
@DisplayName("Content rollback of a trapped storage part")
class TrappedStoragePartContentRollbackTest {
	private static final long CATALOG_VERSION = 1L;
	private static final int PRIMARY_KEY = 7;
	private static final long ASSOCIATED_DATA_PART_PK = 43L;
	private static final AssociatedDataKey DESCRIPTION = new AssociatedDataKey("description", Locale.ENGLISH);
	private DataStoreChanges dataStoreChanges;

	@BeforeEach
	void setUp() {
		this.dataStoreChanges = new DataStoreChanges(new InMemoryStoragePartPersistenceService());
	}

	/**
	 * Closes a savepoint a failing test might have left bound to this thread.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	/**
	 * The path of the reported case: the nested executor changes the trapped part during `applyMutation`, and the
	 * root fails its consistency check before any executor commits. The rollback must leave the part as it was
	 * before the savepoint.
	 */
	@Test
	@DisplayName("A part changed in place by a mutation that fails before commit is restored on rollback")
	void shouldRestoreContentChangedInPlaceBeforeCommit() {
		trapBodyWithEnglishLocale();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		readTrappedBody().addAttributeLocale(Locale.GERMAN);
		savepoint.rollback();

		assertEquals(
			Set.of(Locale.ENGLISH), readTrappedBody().getAttributeLocales(),
			"A rolled-back mutation must not leave its locale in the trapped part."
		);
	}

	/**
	 * The later-executor path: the nested executor that changed the part also commits — which re-traps the very same
	 * instance — and a later executor of the same root mutation fails. The journal restores the slot to the
	 * instance it held before, which is that same, already changed instance.
	 */
	@Test
	@DisplayName("A part re-trapped by a committed executor is restored when a later executor fails")
	void shouldRestoreContentOfRetrappedPartWhenLaterExecutorFails() {
		trapBodyWithEnglishLocale();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		final EntityBodyStoragePart shared = readTrappedBody();
		shared.addAttributeLocale(Locale.GERMAN);
		// what ContainerizedLocalMutationExecutor#commit does for a nested (trapping) executor
		this.dataStoreChanges.trapPutStoragePart(shared);
		// ... and a later executor of the same root mutation fails, so the savepoint rolls back
		savepoint.rollback();

		assertEquals(
			Set.of(Locale.ENGLISH), readTrappedBody().getAttributeLocales(),
			"Restoring the slot to the instance it already held restores nothing - the content must be restored."
		);
	}

	/**
	 * The re-trapped part changed in place, rolled back through the transactional savepoint instead: it snapshots and
	 * restores this layer through its memento rather than through the warm-up savepoint, and the content must be
	 * restored all the same.
	 */
	@Test
	@DisplayName("A part changed in place inside a transactional savepoint is restored by the memento")
	void shouldRestoreContentThroughTransactionalMemento() {
		trapBodyWithEnglishLocale();

		final DataStoreChangesMemento memento = this.dataStoreChanges.snapshot();
		final EntityBodyStoragePart shared = readTrappedBody();
		shared.addAttributeLocale(Locale.GERMAN);
		this.dataStoreChanges.trapPutStoragePart(shared);
		this.dataStoreChanges.restore(memento);

		assertEquals(
			Set.of(Locale.ENGLISH), readTrappedBody().getAttributeLocales(),
			"Restoring the memento must restore the content of the trapped part."
		);
	}

	/**
	 * Control: a part trapped for the FIRST time inside the savepoint is dropped from the slot on rollback, so its
	 * in-place changes vanish with it. This is why the defect needs a part that was trapped BEFORE the failing root
	 * mutation started.
	 */
	@Test
	@DisplayName("A part first trapped inside the savepoint disappears on rollback (control)")
	void shouldDropPartFirstTrappedInsideSavepoint() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		final EntityBodyStoragePart body = new EntityBodyStoragePart(PRIMARY_KEY);
		body.addAttributeLocale(Locale.GERMAN);
		this.dataStoreChanges.trapPutStoragePart(body);
		savepoint.rollback();

		assertNull(
			this.dataStoreChanges.getStoragePart(CATALOG_VERSION, PRIMARY_KEY, EntityBodyStoragePart.class),
			"A part trapped only inside the rolled-back savepoint must not survive it."
		);
	}

	/**
	 * A committed savepoint keeps what its mutation did: the in-place change of a trapped part survives, so the
	 * content journalled on the read is discarded rather than replayed.
	 */
	@Test
	@DisplayName("A part changed in place keeps the change when the savepoint commits")
	void shouldKeepContentChangedInPlaceWhenSavepointCommits() {
		trapBodyWithEnglishLocale();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		readTrappedBody().addAttributeLocale(Locale.GERMAN);
		savepoint.commit();

		assertEquals(
			Set.of(Locale.ENGLISH, Locale.GERMAN), readTrappedBody().getAttributeLocales(),
			"A committed mutation must keep its locale in the trapped part."
		);
	}

	/**
	 * A part read several times inside one savepoint, with a change between the reads. Only the first read journals a
	 * pre-image, and it must be the one the rollback ends at — the content before the savepoint touched the part, not
	 * the content a later read would have seen.
	 */
	@Test
	@DisplayName("A part read several times inside one savepoint is restored to its content before the first read")
	void shouldRestoreEarliestContentWhenPartIsReadSeveralTimes() {
		trapBodyWithEnglishLocale();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		readTrappedBody().addAttributeLocale(Locale.GERMAN);
		readTrappedBody().addAttributeLocale(Locale.FRENCH);
		savepoint.rollback();

		assertEquals(
			Set.of(Locale.ENGLISH), readTrappedBody().getAttributeLocales(),
			"The content journalled by the second read already carries the first change and must not win."
		);
	}

	/**
	 * A slot gets ONE pre-image per savepoint, however often it is read: the earliest one is the one a rollback keeps,
	 * so every further copy would be retained for nothing — and a lookup loop that re-reads a large references part
	 * per iteration would retain one full copy per iteration. A new savepoint must capture the slot again.
	 */
	@Test
	@DisplayName("A trapped part read several times takes one pre-image per savepoint")
	void shouldTakeOnePreImagePerSlotAndSavepoint() {
		final CountingBodyStoragePart body = new CountingBodyStoragePart(PRIMARY_KEY);
		this.dataStoreChanges.trapPutStoragePart(body);

		final WarmUpSavepoint first = WarmUpSavepoint.open();
		for (int i = 0; i < 3; i++) {
			this.dataStoreChanges.getStoragePart(CATALOG_VERSION, PRIMARY_KEY, CountingBodyStoragePart.class);
		}
		this.dataStoreChanges.journalTrappedContent(PRIMARY_KEY, CountingBodyStoragePart.class);
		first.commit();
		assertEquals(1, body.preImages, "Three reads and an explicit request in one savepoint must copy once.");

		final WarmUpSavepoint second = WarmUpSavepoint.open();
		this.dataStoreChanges.getStoragePart(CATALOG_VERSION, PRIMARY_KEY, CountingBodyStoragePart.class);
		this.dataStoreChanges.getStoragePart(CATALOG_VERSION, PRIMARY_KEY, CountingBodyStoragePart.class);
		second.commit();
		assertEquals(2, body.preImages, "A new savepoint must take its own pre-image of the slot, once.");
	}

	/**
	 * Content restore is not specific to the entity body: an associated data part trapped before the savepoint and
	 * replaced in place inside it gets its previous value back.
	 */
	@Test
	@DisplayName("A trapped associated data part changed in place is restored on rollback")
	void shouldRestoreContentOfTrappedAssociatedDataPart() {
		this.dataStoreChanges.trapPutStoragePart(
			new AssociatedDataStoragePart(
				ASSOCIATED_DATA_PART_PK, PRIMARY_KEY, new AssociatedDataValue(DESCRIPTION, "original"), 16
			)
		);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		readTrappedAssociatedData().replaceAssociatedData(new AssociatedDataValue(DESCRIPTION, "changed"));
		savepoint.rollback();

		final AssociatedDataValue restored = readTrappedAssociatedData().getValue();
		assertEquals(
			"original", restored == null ? null : restored.value(),
			"A rolled-back mutation must not leave its value in the trapped part."
		);
	}

	/**
	 * The storage executor reads the entity body in its constructor, before the root mutation's savepoint opens, and
	 * then mutates the instance it holds. Journalling that part's content explicitly right after the savepoint opens
	 * must make the rollback restore it.
	 */
	@Test
	@DisplayName("A part fetched before the savepoint opened is restored once its content is journalled")
	void shouldRestoreContentOfPartFetchedBeforeSavepointWhenJournalled() {
		trapBodyWithEnglishLocale();
		final EntityBodyStoragePart held = readTrappedBody();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		this.dataStoreChanges.journalTrappedContent(PRIMARY_KEY, EntityBodyStoragePart.class);
		held.addAttributeLocale(Locale.GERMAN);
		savepoint.rollback();

		assertEquals(
			Set.of(Locale.ENGLISH), readTrappedBody().getAttributeLocales(),
			"The explicitly journalled content must be restored."
		);
	}

	/**
	 * Control: the same held instance changed in place WITHOUT the explicit journal call. The read happened before the
	 * savepoint opened, so the layer recorded nothing the rollback could replay — which is why the storage executor
	 * must journal the parts it holds as soon as the savepoint opens.
	 */
	@Test
	@DisplayName("A part fetched before the savepoint opened keeps its change when nobody journals it (control)")
	void shouldKeepChangeOfPartFetchedBeforeSavepointWhenNotJournalled() {
		trapBodyWithEnglishLocale();
		final EntityBodyStoragePart held = readTrappedBody();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		held.addAttributeLocale(Locale.GERMAN);
		savepoint.rollback();

		assertEquals(
			Set.of(Locale.ENGLISH, Locale.GERMAN), readTrappedBody().getAttributeLocales(),
			"A read before the savepoint cannot journal anything, so only the explicit call covers the held part."
		);
	}

	/**
	 * Asking to journal a slot that holds no part — nothing trapped at all, another primary key, or a type never
	 * trapped — records nothing and leaves the trapped parts alone.
	 */
	@Test
	@DisplayName("Journalling a slot that holds no part does nothing")
	void shouldIgnoreJournalRequestForSlotHoldingNoPart() {
		final WarmUpSavepoint emptyLayerSavepoint = WarmUpSavepoint.open();
		this.dataStoreChanges.journalTrappedContent(PRIMARY_KEY, EntityBodyStoragePart.class);
		emptyLayerSavepoint.commit();
		trapBodyWithEnglishLocale();
		final EntityBodyStoragePart trapped = readTrappedBody();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		this.dataStoreChanges.journalTrappedContent(PRIMARY_KEY + 1, EntityBodyStoragePart.class);
		this.dataStoreChanges.journalTrappedContent(PRIMARY_KEY, AttributesStoragePart.class);
		savepoint.rollback();

		assertAll(
			() -> assertSame(trapped, readTrappedBody(), "The trapped body must keep its instance."),
			() -> assertNull(
				this.dataStoreChanges.getStoragePart(CATALOG_VERSION, PRIMARY_KEY + 1, EntityBodyStoragePart.class),
				"No body may appear under a primary key nothing was trapped under."
			),
			() -> assertNull(
				this.dataStoreChanges.getStoragePart(CATALOG_VERSION, PRIMARY_KEY, AttributesStoragePart.class),
				"No part may appear under a type nothing was trapped under."
			)
		);
	}

	/**
	 * Traps a body with an English attribute locale OUTSIDE any savepoint, standing in for a nested mutation of an
	 * earlier, successful root mutation in the same flush window.
	 */
	private void trapBodyWithEnglishLocale() {
		final EntityBodyStoragePart body = new EntityBodyStoragePart(PRIMARY_KEY);
		body.addAttributeLocale(Locale.ENGLISH);
		this.dataStoreChanges.trapPutStoragePart(body);
		assertSame(
			body, readTrappedBody(),
			"Premise: the layer hands out the trapped instance itself."
		);
	}

	/**
	 * Reads the body with {@link #PRIMARY_KEY} the way the storage executor does.
	 *
	 * @return the body served by the layer
	 */
	@Nonnull
	private EntityBodyStoragePart readTrappedBody() {
		final EntityBodyStoragePart body = this.dataStoreChanges.getStoragePart(
			CATALOG_VERSION, PRIMARY_KEY, EntityBodyStoragePart.class
		);
		assertEquals(PRIMARY_KEY, body == null ? -1 : body.getPrimaryKey(), "The body must be trapped.");
		return body;
	}

	/**
	 * Reads the associated data part with {@link #ASSOCIATED_DATA_PART_PK} the way the storage executor does.
	 *
	 * @return the associated data part served by the layer
	 */
	@Nonnull
	private AssociatedDataStoragePart readTrappedAssociatedData() {
		final AssociatedDataStoragePart part = this.dataStoreChanges.getStoragePart(
			CATALOG_VERSION, ASSOCIATED_DATA_PART_PK, AssociatedDataStoragePart.class
		);
		assertEquals(PRIMARY_KEY, part == null ? -1 : part.getEntityPrimaryKey(), "The part must be trapped.");
		return part;
	}

	/**
	 * A real entity body that counts how many pre-images were taken of it. Its pre-images are plain
	 * {@link EntityBodyStoragePart}s, so a test using it must not roll back and then read it by its own type.
	 */
	private static final class CountingBodyStoragePart extends EntityBodyStoragePart {
		@Serial private static final long serialVersionUID = 2817164034418640261L;
		/**
		 * Number of {@link #createPreImage()} calls so far.
		 */
		private int preImages;

		/**
		 * Creates a new, empty body with the given primary key.
		 *
		 * @param primaryKey the entity primary key
		 */
		CountingBodyStoragePart(int primaryKey) {
			super(primaryKey);
		}

		@Nonnull
		@Override
		public EntityBodyStoragePart createPreImage() {
			this.preImages++;
			return super.createPreImage();
		}
	}

}
