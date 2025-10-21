/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.grpc.services.converter;


import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor.TransactionChanges;
import io.evitadb.externalApi.grpc.generated.GrpcEntityCollectionChanges;
import io.evitadb.externalApi.grpc.generated.GrpcTransactionChanges;
import io.evitadb.externalApi.grpc.generated.GrpcTransactionOverview;
import io.evitadb.store.spi.model.wal.CatalogTransactionChanges;
import io.evitadb.store.spi.model.wal.EntityCollectionChanges;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;

/**
 * Converts {@link WriteAheadLogVersionDescriptor} into its gRPC counterpart {@link GrpcTransactionOverview}.
 *
 * The method maps the basic transaction metadata and aggregates inner transaction changes. When the
 * {@link TransactionChanges} instance contains additional details (i.e. it's a
 * {@link CatalogTransactionChanges}), those are converted as well; otherwise defaults are used.
 *
 * Note: Currently a single {@link TransactionChanges} is produced per descriptor, hence only one
 * {@link GrpcTransactionChanges} entry is added to the overview.
 *
 * author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class WriteAheadLogVersionDescriptorConverter {

	/**
	 * Converts the provided {@link WriteAheadLogVersionDescriptor} into a {@link GrpcTransactionOverview}.
	 * The method maps transaction metadata and aggregates transaction changes. If the
	 * {@link TransactionChanges} object contains catalog-specific details via {@link CatalogTransactionChanges},
	 * those details are included in the mapping; otherwise, defaults are applied.
	 *
	 * @param descriptor the source {@link WriteAheadLogVersionDescriptor} containing transaction data and changes
	 * @return a {@link GrpcTransactionOverview} representing the mapped transaction overview in gRPC format
	 */
	@Nonnull
	public static GrpcTransactionOverview toGrpcTransactionOverview(
		@Nonnull WriteAheadLogVersionDescriptor descriptor
	) {
		final TransactionChanges transactionChanges = descriptor.transactionChanges();

		final GrpcTransactionChanges.Builder changesBuilder = GrpcTransactionChanges.newBuilder()
			.setMutationCount(transactionChanges.mutationCount())
			.setWalSizeInBytes(transactionChanges.walSizeInBytes());

		// If we have detailed catalog transaction changes, map them
		if (transactionChanges instanceof CatalogTransactionChanges detailed) {
			changesBuilder.setCatalogSchemaChangeCount(detailed.catalogSchemaChanges());
			final EntityCollectionChanges[] entityChanges = detailed.entityCollectionChanges();
			for (final EntityCollectionChanges entityChange : entityChanges) {
				final GrpcEntityCollectionChanges grpcEntityChanges = GrpcEntityCollectionChanges.newBuilder()
					.setEntityName(entityChange.entityName())
					.setSchemaChanges(entityChange.schemaChanges())
					.setUpserted(entityChange.upserted())
					.setRemoved(entityChange.removed())
					.build();
				changesBuilder.addEntityCollectionChanges(grpcEntityChanges);
			}
		}

		return GrpcTransactionOverview.newBuilder()
			.setCatalogVersion(descriptor.version())
			.setTransactionId(toGrpcUuid(descriptor.transactionId()))
			.setCommitTimestamp(toGrpcOffsetDateTime(transactionChanges.commitTimestamp()))
			.setProcessedTimestamp(toGrpcOffsetDateTime(descriptor.processedTimestamp()))
			.setProcessingLagInMillis(transactionChanges.processingLag(descriptor.processedTimestamp()).toMillis())
			.setReversible(descriptor.reversible())
			.addTransactionChanges(changesBuilder.build())
			.build();
	}
}
