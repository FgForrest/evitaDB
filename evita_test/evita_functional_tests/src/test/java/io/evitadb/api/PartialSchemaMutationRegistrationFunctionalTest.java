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

package io.evitadb.api;

import com.esotericsoftware.kryo.KryoException;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.TransactionContract.CommitBehavior.WAIT_FOR_CHANGES_VISIBLE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies rollback when real schema serialization fails after appending isolated WAL continuation frames. */
@DisplayName("Partial schema mutation registration discards the transaction")
@Tag(ENGINE)
@Tag(SCHEMA)
class PartialSchemaMutationRegistrationFunctionalTest implements EvitaTestSupport {

	/** Frame size small enough to exercise continuation and an oversized value with short descriptions. */
	private static final int FRAME_SIZE = 8_192;

	/** A caught first registration failure must retain its cause and discard earlier and later writes. */
	@Test
	@DisplayName("An oversized schema description rolls back a partially serialized mutation")
	void shouldRollBackPartialSchemaMutationRegistration() {
		final TestPaths paths = createTestPaths("PartialSchemaMutationRegistration");
		final EvitaConfiguration configuration = configuration(paths);
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("product").withoutGeneratedPrimaryKey().updateVia(session);
					session.goLiveAndClose();
				});
				final AtomicBoolean rollbackOnly = new AtomicBoolean();
				final AtomicReference<RuntimeException> refusal = new AtomicReference<>();
				final RuntimeException closeException = assertThrows(RuntimeException.class, () ->
					evita.updateCatalog(TEST_CATALOG, session -> {
						session.upsertEntity(session.createNewEntity("product", 1));
						refusal.set(assertThrows(RuntimeException.class, () -> session.updateEntitySchema(
							new ModifyEntitySchemaMutation("product",
								new ModifyEntitySchemaDescriptionMutation("a".repeat(FRAME_SIZE / 2)),
								new ModifyEntitySchemaDescriptionMutation("b".repeat(FRAME_SIZE / 2)),
								new ModifyEntitySchemaDescriptionMutation("c".repeat(FRAME_SIZE / 2)),
								new ModifyEntitySchemaDescriptionMutation("d".repeat(FRAME_SIZE)))
						)));
						rollbackOnly.set(session.isRollbackOnly());
						session.getEntitySchemaOrThrowException("product").openForWrite()
							.withDescription("continued").updateVia(session);
						session.upsertEntity(session.createNewEntity("product", 2));
					}, WAIT_FOR_CHANGES_VISIBLE)
				);
				final KryoException serializationFailure = assertInstanceOf(
					KryoException.class, refusal.get());
				assertAll(
					() -> assertTrue(serializationFailure.getMessage()
						.contains("A single value cannot be split across frames")),
					() -> assertTrue(Arrays.stream(serializationFailure.getStackTrace()).anyMatch(frame ->
						frame.getMethodName().equals("registerMutation"))),
					() -> assertTrue(rollbackOnly.get(),
						"A partial WAL registration must mark the transaction rollback-only"),
					() -> assertInstanceOf(RollbackException.class, closeException),
					() -> assertEquals("Transaction changes have been rolled back due to a previous exception.",
						closeException.getMessage()),
					() -> assertSame(serializationFailure, closeException.getCause())
				);
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				evita.queryCatalog(TEST_CATALOG, session -> {
					assertNull(session.getEntitySchemaOrThrowException("product").getDescription());
					assertFalse(session.getEntity("product", 1).isPresent());
					assertFalse(session.getEntity("product", 2).isPresent());
				});
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.upsertEntity(session.createNewEntity("product", 3));
				}, WAIT_FOR_CHANGES_VISIBLE);
			}
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				evita.queryCatalog(TEST_CATALOG, session -> {
					assertTrue(session.getEntity("product", 3).isPresent());
				});
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** Uses the same frame limit and directories before and after restarting the engine. */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.storage(StorageOptions.builder()
				.storageDirectory(paths.storage())
				.workDirectory(paths.work())
				.outputBufferSize(FRAME_SIZE)
				.build())
			.build();
	}

}
