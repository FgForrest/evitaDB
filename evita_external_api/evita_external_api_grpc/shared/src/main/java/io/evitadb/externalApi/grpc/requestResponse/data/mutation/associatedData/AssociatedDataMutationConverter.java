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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData;

import com.google.protobuf.Message;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcLocale;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;
import io.evitadb.utils.VersionUtils.SemVer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Ancestor for all converters converting implementations of {@link AssociatedDataMutation}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AssociatedDataMutationConverter<J extends AssociatedDataMutation, G extends Message> implements LocalMutationConverter<J, G> {
	private static final ThreadLocal<SemVer> CLIENT_VERSION = new ThreadLocal<>();

	/**
	 * Executes the provided lambda within the context of a specific client version.
	 * Sets the client version in a thread-local variable for the duration of the lambda's execution.
	 * Once the lambda finishes execution, the client version is removed from the thread-local storage.
	 *
	 * @param clientVersion the semantic version of the client to be set for this execution context
	 * @param lambda the task to be executed within the context of the specified client version
	 * @deprecated
	 * TOBEDONE #538 - remove this enum when all clients are `2025.4` or newer
	 */
	@Deprecated
	public static void doWithClientVersion(@Nullable SemVer clientVersion, @Nonnull Runnable lambda) {
		CLIENT_VERSION.set(clientVersion);
		try {
			lambda.run();
		} finally {
			CLIENT_VERSION.remove();
		}
	}

	/**
	 * Retrieves the client version stored in the thread-local context, if available.
	 * The client version represents a semantic version (SemVer) of the client making the request.
	 *
	 * @return An {@code Optional} containing the client version if it has been set; otherwise, an empty {@code Optional}.
	 */
	@Nonnull
	protected Optional<SemVer> getClientVersion() {
		return Optional.ofNullable(CLIENT_VERSION.get());
	}

	@Nonnull
	protected static AssociatedDataContract.AssociatedDataKey buildAssociatedDataKey(
		@Nonnull String associatedDataName,
	    @Nonnull GrpcLocale associatedDataLocale
	) {
		if (!associatedDataLocale.getDefaultInstanceForType().equals(associatedDataLocale)) {
			return new AssociatedDataKey(
				associatedDataName,
				EvitaDataTypesConverter.toLocale(associatedDataLocale)
			);
		} else {
			return new AssociatedDataKey(associatedDataName);
		}
	}
}
