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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.externalApi.grpc.generated.GrpcConflictResolution;
import io.evitadb.externalApi.grpc.generated.GrpcGranularConflictPolicy;

import javax.annotation.Nonnull;
import java.util.EnumSet;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toConflictPolicy;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGranularConflictPolicy;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcConflictPolicy;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcGranularConflictPolicy;

/**
 * Converts the nullable {@link ConflictResolution} record (used on catalog and entity schemas) to and from its gRPC
 * representation {@link GrpcConflictResolution}. Nullability is handled by callers via the `has…` presence flag on the
 * containing message — this converter operates on the present value only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictResolutionConverter {

	/**
	 * Converts a {@link GrpcConflictResolution} message to the {@link ConflictResolution} record.
	 *
	 * @param grpcConflictResolution the message to convert
	 * @return the reconstructed {@link ConflictResolution}
	 */
	@Nonnull
	public static ConflictResolution toConflictResolution(@Nonnull GrpcConflictResolution grpcConflictResolution) {
		final EnumSet<GranularConflictPolicy> granularity = EnumSet.noneOf(GranularConflictPolicy.class);
		final int granularityCount = grpcConflictResolution.getGranularityCount();
		for (int i = 0; i < granularityCount; i++) {
			granularity.add(toGranularConflictPolicy(grpcConflictResolution.getGranularity(i)));
		}
		return new ConflictResolution(
			toConflictPolicy(grpcConflictResolution.getPolicy()),
			granularity
		);
	}

	/**
	 * Converts a {@link ConflictResolution} record to the {@link GrpcConflictResolution} message.
	 *
	 * @param conflictResolution the record to convert
	 * @return the built {@link GrpcConflictResolution}
	 */
	@Nonnull
	public static GrpcConflictResolution toGrpcConflictResolution(@Nonnull ConflictResolution conflictResolution) {
		final GrpcConflictResolution.Builder builder = GrpcConflictResolution.newBuilder()
			.setPolicy(toGrpcConflictPolicy(conflictResolution.policy()));
		for (final GranularConflictPolicy granularPolicy : conflictResolution.granularity()) {
			builder.addGranularity(toGrpcGranularConflictPolicy(granularPolicy));
		}
		return builder.build();
	}

	private ConflictResolutionConverter() {
		// utility class, no instances
	}

}
