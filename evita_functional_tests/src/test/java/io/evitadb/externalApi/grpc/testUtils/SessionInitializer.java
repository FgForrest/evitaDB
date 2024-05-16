/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.externalApi.grpc.testUtils;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcSessionType;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestConstants.TEST_CATALOG;

/**
 * This class is used in tests to create session with specified type and set its {@link EvitaSessionContract#getId()} and type to the {@link SessionIdHolder}.
 * This information is used in {@link ClientSessionInterceptor} where it's passed as {@link Metadata} with each call.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionInitializer {
	/**
	 * Sets a session into the {@link SessionIdHolder} with enabled {@link SessionFlags#DRY_RUN}.
	 *
	 * @param channel     upon which the service stub will be created
	 * @param sessionType type of session to be created
	 */
	public static void setSession(@Nonnull ManagedChannel channel, @Nonnull GrpcSessionType sessionType) {
		setSession(channel, sessionType, true);
	}

	/**
	 * Sets a session into the {@link SessionIdHolder} with set {@link SessionFlags#DRY_RUN} to the passed value as {@code dryRun}.
	 *
	 * @param channel     upon which the service stub will be created
	 * @param sessionType type of session to be created
	 * @param dryRun      whether the session should be created with {@link SessionFlags#DRY_RUN}
	 */
	public static void setSession(@Nonnull ManagedChannel channel, @Nonnull GrpcSessionType sessionType, boolean dryRun) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = EvitaServiceGrpc.newBlockingStub(channel);

		final GrpcEvitaSessionResponse response;
		if (sessionType == GrpcSessionType.READ_WRITE) {
			response = evitaBlockingStub.createReadWriteSession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.setDryRun(dryRun)
				.build());
		} else if (sessionType == GrpcSessionType.BINARY_READ_ONLY) {
			response = evitaBlockingStub.createBinaryReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build());
		} else {
			response = evitaBlockingStub.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder()
				.setCatalogName(TEST_CATALOG)
				.build());
		}
		SessionIdHolder.setSessionId(TEST_CATALOG, response.getSessionId());
	}
}
