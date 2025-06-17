/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies that the default implementation of the {@link IsolatedWalPersistenceService} interface works as expected.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class DefaultIsolatedWalServiceTest implements EvitaTestSupport {
	static final ModifyEntitySchemaMutation SCHEMA_MUTATION_EXAMPLE = new ModifyEntitySchemaMutation(
		Entities.PRODUCT,
		new CreateAttributeSchemaMutation(
			"description", "Description of the product", null,
			null, false, false, false, false, false,
			String.class, null, 0
		)
	);
	static final EntityUpsertMutation DATA_MUTATION_EXAMPLE = new EntityUpsertMutation(
		Entities.PRODUCT, 1, EntityExistence.MUST_EXIST,
		new UpsertAttributeMutation("name", Locale.ENGLISH, "New name")
	);
	private final UUID transactionId = UUID.randomUUID();
	private final Path walFile = getTestDirectory().resolve(this.transactionId.toString());
	private final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder().build(),
		Mockito.mock(Scheduler.class)
	);
	private final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
		getTestDirectory().resolve(this.transactionId.toString()),
		StorageOptions.temporary(),
		this.observableOutputKeeper,
		new CatalogOffHeapMemoryManager(TEST_CATALOG, 512, 1)
	);
	private final DefaultIsolatedWalService tested = new DefaultIsolatedWalService(
		this.transactionId,
		this.kryo,
		this.writeHandle
	);

	@AfterEach
	void tearDown() {
		this.tested.close();
		this.observableOutputKeeper.close();
		final File file = this.walFile.toFile();
		if (file.exists()) {
			fail("File " + file + " should not exist after close!");
		}
	}

	@Test
	void shouldWriteSmallNumberOfMutationsAndReadThem() {
		this.tested.write(1L, DATA_MUTATION_EXAMPLE);
		this.tested.write(1L, SCHEMA_MUTATION_EXAMPLE);

		assertEquals(2, this.tested.getMutationCount());
		assertTrue(this.tested.getMutationSizeInBytes() > 0);

		final OffHeapWithFileBackupReference walReference = this.tested.getWalReference();
		final Optional<ByteBuffer> buffer = walReference.getBuffer();
		assertTrue(buffer.isPresent());

		final ReadOnlyHandle readOnlyHandle = this.writeHandle.toReadOnlyHandle();
		readOnlyHandle.execute(
			input -> {
				final Mutation firstMutation = (Mutation) StorageRecord.read(input, (stream, length) -> this.kryo.readClassAndObject(stream)).payload();
				assertEquals(DATA_MUTATION_EXAMPLE, firstMutation);
				final Mutation secondMutation = (Mutation) StorageRecord.read(input, (stream, length) -> this.kryo.readClassAndObject(stream)).payload();
				assertEquals(SCHEMA_MUTATION_EXAMPLE, secondMutation);
				return null;
			}
		);
	}

	@Test
	void shouldWriteLargeNumberOfMutationsAndReadThem() {
		for (int i = 0; i < 10; i++) {
			this.tested.write(1L, DATA_MUTATION_EXAMPLE);
		}

		assertEquals(10, this.tested.getMutationCount());
		assertTrue(this.tested.getMutationSizeInBytes() > 0);

		final OffHeapWithFileBackupReference walReference = this.tested.getWalReference();
		final Optional<ByteBuffer> buffer = walReference.getBuffer();
		assertFalse(buffer.isPresent());
		assertTrue(walReference.getFilePath().isPresent());

		try (final ReadOnlyHandle readOnlyHandle = this.writeHandle.toReadOnlyHandle()) {
			readOnlyHandle.execute(
				input -> {
					for (int i = 0; i < 10; i++) {
						final Mutation mutation = (Mutation) StorageRecord.read(input, (stream, length) -> this.kryo.readClassAndObject(stream)).payload();
						assertEquals(DATA_MUTATION_EXAMPLE, mutation);
					}
					return null;
				}
			);
		}
	}
}
