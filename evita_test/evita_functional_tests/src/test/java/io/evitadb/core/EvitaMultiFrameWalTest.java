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

package io.evitadb.core;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises transaction byte accounting through an embedded engine restart and public WAL history reads.
 * A single entity upsert spans several frames, while every individual value fits in one frame. Returning only
 * the final frame's location from the payload writer undercounts the transaction's WAL size and violates
 * the consistency checks performed when appending and reading the log.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog WAL transactions containing a multi-frame entity mutation")
@Tag(ENGINE)
@Tag(TRANSACTION)
@Tag(WAL)
@Tag(CDC)
class EvitaMultiFrameWalTest implements EvitaTestSupport {
	/**
	 * Small frame size that accommodates engine metadata and individual values without a large fixture.
	 */
	private static final int OUTPUT_BUFFER_SIZE = 1_024;
	/**
	 * Number of associated data values in the single upsert; together they exceed one frame.
	 */
	private static final int VALUE_COUNT = 16;
	/**
	 * Base of every associated data value; each value alone fits in one frame.
	 */
	private static final String VALUE = "x".repeat(128);

	/**
	 * Storage and work directories shared by the engine before and after the restart.
	 */
	private TestPaths paths;
	/**
	 * Configuration both engine instances start with.
	 */
	private EvitaConfiguration configuration;
	/**
	 * The engine currently running on {@link #paths}.
	 */
	private Evita evita;

	/**
	 * Allocates isolated paths shared by both engine instances and disables compression so the payload
	 * must span frames.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(EvitaMultiFrameWalTest.class.getSimpleName());
		this.configuration = newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.outputBufferSize(OUTPUT_BUFFER_SIZE)
					.compress(false)
					.build()
			)
			.build();
		this.evita = new Evita(this.configuration);
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Closes the current engine, including after a failed restart, and removes only this test's directories.
	 */
	@AfterEach
	void tearDown() {
		try {
			if (this.evita != null && this.evita.isActive()) {
				this.evita.close();
			}
		} finally {
			cleanupTestPaths(this.paths);
		}
	}

	/**
	 * Commits one multi-frame entity mutation in an ALIVE catalog, then verifies that both persisted entity data
	 * and the complete WAL mutation remain readable through the public API after a clean engine restart.
	 */
	@Test
	@DisplayName("should load the catalog and read the complete mutation history after restart")
	void shouldReadMultiFrameEntityMutationAfterRestart() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntitySchemaBuilder schema = session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey();
				for (int i = 0; i < VALUE_COUNT; i++) {
					schema.withAssociatedData("payload" + i, String.class);
				}
				schema.updateVia(session);
				session.goLiveAndClose();
			}
		);
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(TEST_CATALOG).orElseThrow());
		assertTrue(VALUE.length() + Integer.toString(VALUE_COUNT - 1).length() < OUTPUT_BUFFER_SIZE);
		assertTrue(VALUE_COUNT * VALUE.length() > OUTPUT_BUFFER_SIZE);

		final long transactionVersion;
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE)
		)) {
			final EntityBuilder entity = session.createNewEntity(Entities.PRODUCT, 1);
			for (int i = 0; i < VALUE_COUNT; i++) {
				entity.setAssociatedData("payload" + i, VALUE + i);
			}
			// Schema is already complete: this one upsert is the transaction's only catalog mutation.
			session.upsertEntity(entity);
			assertNotNull(session.closeNowWithProgress().onChangesVisible().toCompletableFuture().join());
		}
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			transactionVersion = session.getCatalogVersion();
		}

		this.evita.close();
		this.evita = new Evita(this.configuration);
		this.evita.waitUntilFullyInitialized();
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(TEST_CATALOG).orElseThrow());

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, associatedDataContentAll())
				.orElseThrow();
			assertEquals(VALUE_COUNT, entity.getAssociatedDataKeys().size());
			for (int i = 0; i < VALUE_COUNT; i++) {
				assertEquals(VALUE + i, entity.getAssociatedData("payload" + i));
			}

			// A new engine has no live capture cache: history deserializes the committed WAL from disk.
			try (final Stream<ChangeCatalogCapture> history = session.getMutationsHistoryForward(
				ChangeCatalogCaptureRequest.builder()
					.sinceVersion(transactionVersion)
					.content(ChangeCaptureContent.BODY)
					.build()
			)) {
				final List<ChangeCatalogCapture> captures = history.toList();
				final List<TransactionMutation> transactions = captures.stream()
					.map(ChangeCatalogCapture::body)
					.filter(TransactionMutation.class::isInstance)
					.map(TransactionMutation.class::cast)
					.toList();
				assertEquals(1, transactions.size());
				assertEquals(1, transactions.getFirst().getMutationCount());
				assertTrue(transactions.getFirst().getWalSizeInBytes() > OUTPUT_BUFFER_SIZE);

				final List<EntityUpsertMutation> mutations = captures.stream()
					.map(ChangeCatalogCapture::body)
					.filter(EntityUpsertMutation.class::isInstance)
					.map(EntityUpsertMutation.class::cast)
					.toList();
				assertEquals(1, mutations.size());
				assertEquals(Entities.PRODUCT, mutations.getFirst().getEntityType());
				assertEquals(1, mutations.getFirst().getEntityPrimaryKey());
				assertEquals(VALUE_COUNT, mutations.getFirst().getLocalMutations().size());
				for (int i = 0; i < VALUE_COUNT; i++) {
					assertTrue(mutations.getFirst().getLocalMutations().contains(
						new UpsertAssociatedDataMutation("payload" + i, VALUE + i)
					));
				}
			}
		}
	}
}
