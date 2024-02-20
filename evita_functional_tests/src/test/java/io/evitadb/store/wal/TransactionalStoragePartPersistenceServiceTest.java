/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.wal;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.compressor.ReadOnlyKeyCompressor;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.StoragePartPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This test verifies behavior of {@link TransactionalStoragePartPersistenceService}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class TransactionalStoragePartPersistenceServiceTest {
	private TransactionalStoragePartPersistenceService service;
	private OffHeapMemoryManager offHeapMemoryManager;
	private StoragePartPersistenceService delegateService;

	@BeforeEach
	public void setUp() {
		this.offHeapMemoryManager = new OffHeapMemoryManager(2048, 1);
		this.delegateService = mock(StoragePartPersistenceService.class);
		when(this.delegateService.getReadOnlyKeyCompressor()).thenReturn(new ReadOnlyKeyCompressor(Map.of()));
		final StorageOptions storageOptions = StorageOptions.builder().build();
		final TransactionOptions transactionOptions = TransactionOptions.builder().build();
		final ObservableOutputKeeper observableOutputKeeper = mock(ObservableOutputKeeper.class);
		when(observableOutputKeeper.getOptions()).thenReturn(storageOptions);
		final OffsetIndexRecordTypeRegistry registry = mock(OffsetIndexRecordTypeRegistry.class);
		when(registry.idFor(EntityBodyStoragePart.class)).thenReturn((byte) 1);
		doAnswer(invocation -> EntityBodyStoragePart.class).when(registry).typeFor((byte) 1);

		service = new TransactionalStoragePartPersistenceService(
			UUID.randomUUID(),
			"test",
			delegateService,
			storageOptions,
			transactionOptions,
			offHeapMemoryManager,
			kryoKeyInputs -> VersionedKryoFactory.createKryo(
				kryoKeyInputs.version(),
				SchemaKryoConfigurer.INSTANCE
					.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
					.andThen(SharedClassesConfigurer.INSTANCE)
					.andThen(new EntityStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
					.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
			),
			registry,
			observableOutputKeeper
		);
	}

	@AfterEach
	void tearDown() {
		offHeapMemoryManager.close();
	}

	@Test
	public void shouldGetStoragePartFromDelegateWhenNotRemoved() {
		StoragePart storagePart = new EntityBodyStoragePart(1);
		when(delegateService.getStoragePart(anyLong(), anyLong(), any())).thenReturn(storagePart);
		when(delegateService.containsStoragePart(anyLong(), anyLong(), any())).thenReturn(true);

		StoragePart result = service.getStoragePart(1L, 1L, StoragePart.class);

		assertEquals(storagePart, result);
	}

	@Test
	public void shouldReturnNullWhenStoragePartRemoved() {
		StoragePart storagePart = new EntityBodyStoragePart(1);
		when(delegateService.getStoragePart(anyLong(), anyLong(), any())).thenReturn(storagePart);
		when(delegateService.containsStoragePart(anyLong(), anyLong(), any())).thenReturn(true);

		service.removeStoragePart(1L, 1L, EntityBodyStoragePart.class);

		StoragePart result = service.getStoragePart(1L, 1L, EntityBodyStoragePart.class);

		assertNull(result);
	}

	@Test
	public void shouldPutStoragePartIntoOffsetIndex() {
		StoragePart storagePart = new EntityBodyStoragePart(1);

		service.putStoragePart(1L, storagePart);

		assertTrue(service.containsStoragePart(1L, 1L, EntityBodyStoragePart.class));
	}

	@Test
	public void shouldRemoveStoragePartFromOffsetIndex() {
		StoragePart storagePart = new EntityBodyStoragePart(1);

		service.putStoragePart(1L, storagePart);

		service.removeStoragePart(1L, 1L, EntityBodyStoragePart.class);

		assertFalse(service.containsStoragePart(1L, 1L, EntityBodyStoragePart.class));
	}

	@Test
	public void shouldRemoveStoragePartFromOffsetIndexAndDelegate() {
		StoragePart storagePart = new EntityBodyStoragePart(1);
		when(delegateService.getStoragePart(anyLong(), anyLong(), any())).thenReturn(storagePart);
		when(delegateService.containsStoragePart(anyLong(), anyLong(), any())).thenReturn(true);

		service.putStoragePart(1L, storagePart);

		service.removeStoragePart(1L, 1L, EntityBodyStoragePart.class);

		assertFalse(service.containsStoragePart(1L, 1L, EntityBodyStoragePart.class));
	}

	@Test
	public void shouldReturnFalseWhenStoragePartDoesNotExistInOffsetIndex() {
		assertFalse(service.containsStoragePart(1L, 1L, EntityBodyStoragePart.class));
	}

}
