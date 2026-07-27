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

package io.evitadb.store.offsetIndex;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the binding between an {@link OffsetIndex}'s read Kryo pool and the
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadOnlyKeyCompressor} it deserializes
 * with.
 *
 * A `VersionedKryo` handed out by the pool carries a *snapshot* of the compressed keys, taken when the descriptor
 * it was built from was created. Every write that introduces a previously unseen key (an `AssociatedDataKey`, an
 * `AttributeKey`, a `PriceKey`, ...) mints a fresh id in the **write** compressor, so the snapshot must be refreshed
 * and the pool purged whenever a flush publishes a new descriptor. If a Kryo bound to a superseded snapshot is ever
 * admitted back into the pool it is reused indefinitely, and every later read of a record written with one of the
 * newer ids fails with `There is no key for id N!` - long after the flush that caused it.
 *
 * Both flush paths ({@link OffsetIndex#flush(long)} and the implicit soft flush performed when a not-yet-synced
 * record is read back) publish a new descriptor and expire the pool, and both are covered here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@DisplayName("OffsetIndex read-compressor lifecycle")
class OffsetIndexKeyCompressorLifecycleTest {
	/**
	 * Operation label the offset index passes to the write handle when it promotes non-flushed values during
	 * {@link OffsetIndex#flush(long)}.
	 */
	private static final String FLUSH_OPERATION = "Writing mem table";
	/**
	 * Operation label the offset index passes to the write handle when it syncs the output buffer so that a record
	 * written moments ago can be read back.
	 */
	private static final String SOFT_FLUSH_OPERATION = "Syncing changes to disk.";
	private static final String ENTITY_TYPE = "whatever";

	private final OffsetIndexRecordTypeRegistry recordTypeRegistry = new OffsetIndexRecordTypeRegistry();
	private Path targetFile;
	private ObservableOutputKeeper observableOutputKeeper;
	private HookableWriteHandle writeHandle;
	private OffsetIndex offsetIndex;

	/**
	 * Builds the Kryo factory used by both the write and the read side of the index under test. The
	 * `EntityStoragePartConfigurer` is what wires the passed key compressor into
	 * `EntityBodyStoragePartSerializer`, which is the serializer that compresses associated data keys.
	 */
	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryoFactory() {
		return keyInputs -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(new EntityStoragePartConfigurer(keyInputs.keyCompressor()))
		);
	}

	/**
	 * Creates an entity body storage part carrying exactly one associated data key. Each distinct key name mints
	 * a new id in the write compressor the first time the part is serialized.
	 *
	 * @param primaryKey          primary key of the entity the part belongs to
	 * @param associatedDataName  name of the single associated data key the part declares
	 * @return the storage part to be written into the offset index
	 */
	@Nonnull
	private static EntityBodyStoragePart createPartWithAssociatedData(
		int primaryKey,
		@Nonnull String associatedDataName
	) {
		return new EntityBodyStoragePart(
			1, primaryKey, Scope.LIVE, null, Set.of(), Set.of(),
			Set.of(new AssociatedDataKey(associatedDataName)),
			0
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		this.targetFile = Files.createTempFile("offsetIndexCompressorLifecycle", "kryo");
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		final StorageSettings storageSettings = new StorageSettings(
			StorageOptions.temporary(),
			TransactionOptions.builder().build()
		);
		this.writeHandle = new HookableWriteHandle(
			new WriteOnlyFileHandle(
				this.targetFile,
				storageSettings.outputBufferSize(),
				storageSettings.syncWrites(),
				storageSettings,
				storageSettings,
				this.observableOutputKeeper
			)
		);
		this.offsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0),
				createKryoFactory(),
				1.0, 0L
			),
			storageSettings.outputBufferSize(),
			storageSettings.maxOpenedReadHandlesOrDefault(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings.waitOnCloseSeconds(),
			storageSettings,
			storageSettings,
			this.recordTypeRegistry,
			this.writeHandle,
			(Consumer<OffsetIndex.NonFlushedBlock>) nonFlushedBlock -> {
			},
			(Consumer<Optional<OffsetDateTime>>) oldestRecord -> {
			}
		);
	}

	@AfterEach
	void tearDown() {
		if (this.offsetIndex != null && this.offsetIndex.isOperative()) {
			IOUtils.closeQuietly(() -> this.offsetIndex.close());
		}
		if (this.observableOutputKeeper != null) {
			IOUtils.closeQuietly(this.observableOutputKeeper::close);
		}
		if (this.targetFile != null) {
			this.targetFile.toFile().delete();
		}
	}

	@Nested
	@DisplayName("Descriptor publication vs. read pool expiry")
	class DescriptorPublicationOrdering {

		@Test
		@DisplayName("should read back a record written with a freshly minted key when a reader borrows during flush")
		void shouldReadBackRecordWrittenWithFreshKeyWhenReaderBorrowsDuringFlush() {
			// version 1 introduces the first compressed key, so the post-flush descriptor knows exactly one id
			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.put(
				1L, createPartWithAssociatedData(1, "alpha"));
			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.flush(1L);

			// version 2 introduces a second one, which only the *next* descriptor will know about
			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.put(
				2L, createPartWithAssociatedData(2, "beta"));

			// a reader that borrows a Kryo while version 2 is being flushed must not be served one built from
			// the superseded descriptor - it reads an old record successfully and hands the instance back, so
			// nothing here fails and the damage stays invisible until the next read of the new record
			OffsetIndexKeyCompressorLifecycleTest.this.writeHandle.armAfter(
				FLUSH_OPERATION,
				() -> assertNotNull(
					OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.get(
						2L, 1L, EntityBodyStoragePart.class)
				)
			);
			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.flush(2L);
			assertTrue(
				OffsetIndexKeyCompressorLifecycleTest.this.writeHandle.hookFired(),
				"the flush hook never fired - the operation label must have changed"
			);

			// the record written with the freshly minted id must still be readable
			final EntityBodyStoragePart reloaded = OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.get(
				2L, 2L, EntityBodyStoragePart.class);
			assertNotNull(reloaded);
			assertEquals(Set.of(new AssociatedDataKey("beta")), reloaded.getAssociatedDataKeys());
		}

		/**
		 * The soft flush publishes the descriptor and expires the pool from inside a single critical section, so the
		 * hook - which fires once the whole call returns - lands after both and cannot tell the two orderings apart
		 * the way the sibling test above can. What it does pin is the step that has to happen at all: a soft flush
		 * exists so that a record written moments ago can be read back, and that is only true if the read compressor
		 * is republished before the record becomes readable. Drop the descriptor rebuild from `doSoftFlush` and the
		 * final read below fails.
		 */
		@Test
		@DisplayName("should republish the read compressor when a soft flush makes a freshly keyed record readable")
		void shouldRepublishReadCompressorWhenSoftFlushMakesFreshKeyedRecordReadable() {
			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.put(
				1L, createPartWithAssociatedData(1, "alpha"));
			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.flush(1L);

			OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.put(
				2L, createPartWithAssociatedData(2, "beta"));

			// reading a record that has not been synced yet forces a soft flush, which republishes the descriptor
			// and expires the pool exactly like a regular flush does; the borrow below therefore has to be served
			// a Kryo that already knows the key minted for this record
			OffsetIndexKeyCompressorLifecycleTest.this.writeHandle.armAfter(
				SOFT_FLUSH_OPERATION,
				() -> assertNotNull(
					OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.get(
						2L, 1L, EntityBodyStoragePart.class)
				)
			);
			final EntityBodyStoragePart reloaded = OffsetIndexKeyCompressorLifecycleTest.this.offsetIndex.get(
				2L, 2L, EntityBodyStoragePart.class);
			assertTrue(
				OffsetIndexKeyCompressorLifecycleTest.this.writeHandle.hookFired(),
				"the soft flush hook never fired - the operation label must have changed"
			);

			assertNotNull(reloaded);
			assertEquals(Set.of(new AssociatedDataKey("beta")), reloaded.getAssociatedDataKeys());
		}

	}

	/**
	 * A {@link WriteOnlyHandle} decorator that runs a one-shot hook immediately after the delegate finishes a
	 * named operation. The offset index publishes its new descriptor and expires the read Kryo pool from inside
	 * that call, so a hook attached to its return covers the window a concurrent reader would otherwise have to
	 * be raced into.
	 */
	private static final class HookableWriteHandle implements WriteOnlyHandle {
		@Nonnull private final WriteOnlyHandle delegate;
		private String armedOperation;
		private Runnable armedHook;
		private boolean fired;

		HookableWriteHandle(@Nonnull WriteOnlyHandle delegate) {
			this.delegate = delegate;
		}

		/**
		 * Arms a one-shot hook to be executed right after the delegate completes `operation`.
		 *
		 * @param operation the operation label to match
		 * @param hook      the action to run once the delegate returns
		 */
		void armAfter(@Nonnull String operation, @Nonnull Runnable hook) {
			this.armedOperation = operation;
			this.armedHook = hook;
			this.fired = false;
		}

		/**
		 * Returns TRUE when the armed hook has actually been executed - guards the test against a silently
		 * renamed operation label that would make the scenario pass without ever exercising the window.
		 */
		boolean hookFired() {
			return this.fired;
		}

		/**
		 * Runs the armed hook when `operation` matches, disarming it beforehand so that reads issued by the hook
		 * itself cannot re-enter it.
		 *
		 * @param operation the label of the operation the delegate has just completed
		 */
		private void fireIfArmed(@Nonnull String operation) {
			if (operation.equals(this.armedOperation)) {
				final Runnable hook = this.armedHook;
				this.armedOperation = null;
				this.armedHook = null;
				if (hook != null) {
					this.fired = true;
					hook.run();
				}
			}
		}

		@Override
		public <T> T checkAndExecute(
			@Nonnull String operation,
			@Nonnull Runnable premise,
			@Nonnull Function<ObservableOutput<?>, T> logic
		) {
			final T result = this.delegate.checkAndExecute(operation, premise, logic);
			fireIfArmed(operation);
			return result;
		}

		@Override
		public void checkAndExecuteAndSync(
			@Nonnull String operation,
			@Nonnull Runnable premise,
			@Nonnull Consumer<ObservableOutput<?>> logic
		) {
			this.delegate.checkAndExecuteAndSync(operation, premise, logic);
			fireIfArmed(operation);
		}

		@Override
		public <S, T> T checkAndExecuteAndSync(
			@Nonnull String operation,
			@Nonnull Runnable premise,
			@Nonnull Function<ObservableOutput<?>, S> logic,
			@Nonnull BiFunction<ObservableOutput<?>, S, T> postExecutionLogic
		) {
			final T result = this.delegate.checkAndExecuteAndSync(operation, premise, logic, postExecutionLogic);
			fireIfArmed(operation);
			return result;
		}

		@Override
		public void forceDurable() {
			this.delegate.forceDurable();
		}

		@Override
		public long getLastWrittenPosition() {
			return this.delegate.getLastWrittenPosition();
		}

		@Nonnull
		@Override
		public ReadOnlyHandle toReadOnlyHandle() {
			return this.delegate.toReadOnlyHandle();
		}

		@Override
		public void close() {
			this.delegate.close();
		}

	}

}
