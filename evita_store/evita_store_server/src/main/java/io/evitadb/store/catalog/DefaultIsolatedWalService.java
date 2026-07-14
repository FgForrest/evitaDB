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
import io.evitadb.api.TransactionContract;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.transaction.stage.mutation.ServerEntityMutation;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The DefaultIsolatedWalService class is a default implementation of the IsolatedWalPersistenceService interface.
 * It provides methods for writing mutations to the Write-Ahead Log (WAL), retrieving metadata about the mutations,
 * obtaining a reference to the WAL data, and closing the service.
 *
 * There is always single instance per {@link TransactionContract} instance identified by same {@link UUID}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DefaultIsolatedWalService implements IsolatedWalPersistenceService {
	/**
	 * The catalogName is the name of the catalog associated with this isolated WAL instance.
	 */
	@Nonnull private final String catalogName;
	/**
	 * The conflict resolution considered when collecting conflict keys. In schema-aware mode (a non-null
	 * {@link #catalogSchema}) it is the engine-wide default forming the base of the per-entity schema
	 * precedence walk; otherwise it is the fixed resolution applied to every mutation.
	 */
	@Nonnull private final ConflictResolution conflictResolution;
	/**
	 * The catalog schema whose (nullable) resolution overrides the engine default when collecting conflict
	 * keys. Null enables the legacy global-backed key generation (used by tests that construct the service
	 * without a schema); non-null enables schema-aware resolution.
	 */
	@Nullable private final CatalogSchemaContract catalogSchema;
	/**
	 * Accessor returning the entity schema for a given entity type (or null when the type has no schema
	 * yet), consulted in schema-aware mode. Null in global-backed mode.
	 */
	@Nullable private final Function<String, EntitySchemaContract> entitySchemaAccessor;
	/**
	 * The transactionId is the unique identifier for the transaction.
	 */
	@Nonnull @Getter private final UUID transactionId;
	/**
	 * The writeKryo is the Kryo instance used for serializing WAL records = mutations.
	 */
	@Nonnull private final Kryo writeKryo;
	/**
	 * The writeHandle is the handle to the WAL file.
	 */
	@Nonnull private final WriteOnlyOffHeapWithFileBackupHandle writeHandle;
	/**
	 * The mutationCount is the number of mutations written to this isolated WAL instance.
	 */
	@Getter private int mutationCount;
	/**
	 * The mutationSizeInBytes is the total size of the mutations written to this isolated WAL instance.
	 */
	@Getter private long mutationSizeInBytes;
	/**
	 * Container for the conflict keys registered for each of the mutations written to this isolated WAL instance.
	 */
	private final CompositeObjectArray<ConflictKey> conflictKeys = new CompositeObjectArray<>(ConflictKey.class);

	/**
	 * Creates a global-backed WAL service: every mutation's conflict keys are generated from the fixed
	 * {@code conflictResolution} without consulting any schema. Retained for callers (chiefly tests) that
	 * have no living schema at hand.
	 */
	public DefaultIsolatedWalService(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		@Nonnull ConflictResolution conflictResolution,
		@Nonnull Kryo writeKryo,
		@Nonnull WriteOnlyOffHeapWithFileBackupHandle writeHandle
	) {
		this(catalogName, transactionId, conflictResolution, null, null, writeKryo, writeHandle);
	}

	/**
	 * Creates a schema-aware WAL service: conflict keys are generated from the effective, schema-declared
	 * conflict resolution resolved per entity type (entity schema → catalog schema → engine default) with
	 * per-item overrides applied (issue #503).
	 *
	 * @param conflictResolution   the engine-wide default resolution forming the base of the walk
	 * @param catalogSchema        the catalog schema whose resolution overrides the engine default
	 * @param entitySchemaAccessor accessor returning the entity schema for a type, or null when absent
	 */
	public DefaultIsolatedWalService(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		@Nonnull ConflictResolution conflictResolution,
		@Nullable CatalogSchemaContract catalogSchema,
		@Nullable Function<String, EntitySchemaContract> entitySchemaAccessor,
		@Nonnull Kryo writeKryo,
		@Nonnull WriteOnlyOffHeapWithFileBackupHandle writeHandle
	) {
		this.catalogName = catalogName;
		this.transactionId = transactionId;
		this.conflictResolution = conflictResolution;
		this.catalogSchema = catalogSchema;
		this.entitySchemaAccessor = entitySchemaAccessor;
		this.writeKryo = writeKryo;
		this.writeHandle = writeHandle;
	}

	@Override
	public void write(long catalogVersion, @Nonnull Mutation mutation) {
		this.mutationSizeInBytes += this.writeHandle.checkAndExecute(
			"write mutation",
			() -> { },
			output -> {
				final Mutation mutationToWrite = mutation instanceof ServerEntityMutation sem ?
					sem.getDelegate() : mutation;
				// collect conflict keys — schema-aware when a living schema was threaded in, otherwise
				// global-backed from the fixed resolution. The catalog schema and the entity schema accessor
				// are always supplied together (see the schema-aware constructor), so the accessor is
				// non-null whenever the catalog schema is; assert it to fail fast on any inconsistent state.
				final CatalogSchemaContract localCatalogSchema = this.catalogSchema;
				final ConflictGenerationContext context = localCatalogSchema == null ?
					new ConflictGenerationContext(this.conflictResolution) :
					new ConflictGenerationContext(
						this.conflictResolution,
						localCatalogSchema,
						Objects.requireNonNull(
							this.entitySchemaAccessor,
							"Entity schema accessor must accompany the catalog schema in schema-aware mode."
						)
					);
				final Iterator<ConflictKey> it = context.withCatalogName(
					this.catalogName,
					mutationToWrite::collectConflictKeys
				).iterator();
				// register collected conflict keys
				boolean conflictKeyCollected = false;
				while (it.hasNext()) {
					this.conflictKeys.add(it.next());
					conflictKeyCollected = true;
				}
				// register catalog conflict key if none collected and catalog policy is requested
				if (!conflictKeyCollected && this.conflictResolution.policy() == ConflictPolicy.CATALOG) {
					this.conflictKeys.add(new CatalogConflictKey(this.catalogName));
				}
				// write the mutation
				final StorageRecord<Mutation> record = new StorageRecord<>(
					output, catalogVersion, false,
					theOutput -> {
						this.writeKryo.writeClassAndObject(output, mutationToWrite);
						return mutationToWrite;
					}
				);
				return record.fileLocation().recordLength();
			}
		);
		this.mutationCount++;
	}

	@Nonnull
	@Override
	public OffHeapWithFileBackupReference getWalReference() {
		return this.writeHandle.toReadOffHeapWithFileBackupReference();
	}

	@Nonnull
	@Override
	public Set<ConflictKey> getConflictKeys() {
		final Set<ConflictKey> resultConflictKeys = CollectionUtils.createHashSet(this.conflictKeys.getSize());
		for (ConflictKey conflictKey : this.conflictKeys) {
			resultConflictKeys.add(conflictKey);
		}
		if (resultConflictKeys.isEmpty() && this.conflictResolution.policy() == ConflictPolicy.CATALOG) {
			// at least catalog conflict key must be present
			resultConflictKeys.add(new CatalogConflictKey(this.catalogName));
		}
		return resultConflictKeys;
	}

	@Override
	public void close() {
		this.writeHandle.close();
	}

}
