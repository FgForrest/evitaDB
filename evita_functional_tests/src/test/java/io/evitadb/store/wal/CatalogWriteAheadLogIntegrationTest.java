/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.EvitaSession;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.CatalogWriteAheadLog.MutationSupplier;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Test verifying the behaviour of {@link CatalogWriteAheadLog}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class CatalogWriteAheadLogIntegrationTest {
	private final Path walDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita").resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final WalFileReference walFileReference = new WalFileReference(TEST_CATALOG, 0, null);
	private final CatalogWriteAheadLog tested = new CatalogWriteAheadLog(
		TEST_CATALOG,
		walDirectory,
		walFileReference,
		catalogKryoPool,
		StorageOptions.builder().build(),
		Mockito.mock(ScheduledExecutorService.class)
	);
	private final Path walFilePath = walDirectory.resolve(getWalFileName(TEST_CATALOG, walFileReference.fileIndex()));
	private final Path isolatedWalFilePath = walDirectory.resolve("isolatedWal.tmp");
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		StorageOptions.builder().build()
	);
	private final OffHeapMemoryManager noOffHeapMemoryManager = new OffHeapMemoryManager(0, 0);
	private final OffHeapMemoryManager bigOffHeapMemoryManager = new OffHeapMemoryManager(10_000_000, 128);
	private final int[] txSizes = new int[]{2000, 3000, 4000, 5000, 7000, 9000, 1_000};

	@BeforeEach
	void setUp() {
		observableOutputKeeper.prepare();
	}

	@AfterEach
	void tearDown() throws IOException {
		observableOutputKeeper.free();
		tested.close();
		walFilePath.toFile().delete();
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldWriteAndRealSmallAmountOfTransactionsAndReuseCacheOnNextAccess() {
		final int[] aFewTransactions = {1, 2, 3, 2, 1};
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, aFewTransactions);
		readAndVerifyWal(txInMutations, aFewTransactions, 0);

		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 4);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 3);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 2);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 1);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 0);
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldReadAllTransactionsUsingOffHeapIsolatedWal() {
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, txSizes);
		readAndVerifyWal(txInMutations, txSizes, 0);
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldReadAllTransactionsUsingFileIsolatedWal() {
		final Map<Long, List<Mutation>> txInMutations = writeWal(noOffHeapMemoryManager, txSizes);
		readAndVerifyWal(txInMutations, txSizes, 0);
	}

	private void createCachedSupplierReadAndVerifyFrom(Map<Long, List<Mutation>> txInMutations, int[] aFewTransactions, int index) {
		final MutationSupplier supplier = tested.createSupplier(index + 1, false, walFilePath.toFile());
		assertEquals(1, supplier.getTransactionsRead());
		readAndVerifyWal(txInMutations, aFewTransactions, index);
	}

	/**
	 * Writes the Write-Ahead Log (WAL) using the provided off-heap memory manager.
	 *
	 * @param offHeapMemoryManager the off-heap memory manager to use
	 * @param transactionSizes     an array of transaction sizes
	 * @return a map of catalog versions to corresponding mutations
	 */
	@Nonnull
	private Map<Long, List<Mutation>> writeWal(@Nonnull OffHeapMemoryManager offHeapMemoryManager, int[] transactionSizes) {
		final DataGenerator dataGenerator = new DataGenerator();
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			EnumSet.allOf(CatalogEvolutionMode.class),
			io.evitadb.api.mock.EmptyEntitySchemaAccessor.INSTANCE
		);
		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(new CatalogSchemaDecorator(catalogSchema));

		final IsolatedWalPersistenceService walPersistenceService = new DefaultIsolatedWalService(
			UUID.randomUUID(),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				isolatedWalFilePath, observableOutputKeeper, offHeapMemoryManager
			)
		);

		final Map<Long, List<Mutation>> txInMutations = CollectionUtils.createHashMap(transactionSizes.length);
		for (int i = 0; i < transactionSizes.length; i++) {
			int txSize = transactionSizes[i];
			final LinkedList<Mutation> mutations = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						mockSession,
						EntitySchemaBuilder::toInstance
					),
					(serializable, faker) -> null,
					42
				)
				.limit(txSize)
				.map(EntityBuilder::toMutation)
				.map(Optional::get)
				.collect(Collectors.toCollection(LinkedList::new));

			for (Mutation mutation : mutations) {
				walPersistenceService.write(1, mutation);
			}

			final OffHeapWithFileBackupReference walReference = walPersistenceService.getWalReference();
			final long catalogVersion = i + 1;
			final TransactionMutation transactionMutation = new TransactionMutation(
				UUIDUtil.randomUUID(),
				catalogVersion,
				mutations.size(),
				walReference.getContentLength()
			);
			tested.append(
				transactionMutation,
				walReference
			);

			mutations.addFirst(transactionMutation);
			txInMutations.put(catalogVersion, mutations);
		}
		return txInMutations;
	}

	/**
	 * Reads and verifies the Write-Ahead Log (WAL) using the provided transaction
	 * mutations map.
	 *
	 * @param txInMutations    a map of catalog versions to corresponding mutations
	 * @param transactionSizes an array of transaction sizes
	 */
	private void readAndVerifyWal(@Nonnull Map<Long, List<Mutation>> txInMutations, int[] transactionSizes, int startIndex) {
		long lastCatalogVersion = startIndex;
		final Iterator<Mutation> mutationIterator = tested.getCommittedMutationStream(startIndex + 1).iterator();
		int txRead = 0;
		while (mutationIterator.hasNext()) {
			txRead++;
			final Mutation mutation = mutationIterator.next();
			assertInstanceOf(TransactionMutation.class, mutation);

			final TransactionMutation transactionMutation = (TransactionMutation) mutation;
			final List<Mutation> mutationsInTx = txInMutations.get(transactionMutation.getCatalogVersion());
			assertEquals(mutationsInTx.get(0), transactionMutation);
			for (int i = 0; i < transactionMutation.getMutationCount(); i++) {
				final Mutation mutationInTx = mutationIterator.next();
				assertEquals(mutationsInTx.get(i + 1), mutationInTx);
			}

			lastCatalogVersion = transactionMutation.getCatalogVersion();
		}

		assertEquals(transactionSizes.length, lastCatalogVersion);
		assertEquals(txRead, transactionSizes.length - startIndex);
	}

}
