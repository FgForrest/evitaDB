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

package io.evitadb.externalApi.grpc.constants;

import io.evitadb.utils.VersionUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Metadata;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Shared gRPC constant repository. The constants that we want o be shared between the Java client and the gRPC server.
 * The interface also serves as a guideline for clients on different platforms.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface GrpcHeaders {
	/**
	 * Constant string representing sessionId that is used to fetch session from the context.
	 */
	String SESSION_ID_HEADER = "sessionId";
	/**
	 * Constant string representing metadata that is used to fetch gRPC metadata object from the context.
	 */
	String METADATA_HEADER = "metadata";
	/**
	 * Constant string representing metadata that is used to fetch gRPC method name from the context.
	 */
	String METHOD_NAME_HEADER = "methodName";
	/**
	 * Constant string representing clientId that is used to fetch session from context.
	 */
	String CLIENT_ID_HEADER = "clientId";
	/**
	 * Constant string representing metadata that is used to fetch gRPC metadata object from the context.
	 */
	String CLIENT_VERSION = "clientVersion";

	/**
	 * Returns the gRPC trace task name with method name.
	 *
	 * @param metadata gRPC metadata object
	 * @return gRPC trace task name with method name
	 */
	@Nonnull
	static String getGrpcTraceTaskNameWithMethodName(@Nonnull Metadata metadata) {
		final Metadata.Key<String> methodName = Metadata.Key.of(METHOD_NAME_HEADER, Metadata.ASCII_STRING_MARSHALLER);
		return "gRPC - " + metadata.get(methodName);
	}

	/**
	 * Retrieves the client version from the provided gRPC metadata.
	 *
	 * @param metadata the gRPC metadata object containing client metadata
	 * @return an {@link Optional} containing the client version as a {@link VersionUtils.SemVer} object
	 *         if the version metadata exists; otherwise, an empty {@link Optional}
	 */
	@Nonnull
	static Optional<VersionUtils.SemVer> getClientVersion(@Nonnull Metadata metadata) {
		final Metadata.Key<String> clientVersion = Metadata.Key.of(CLIENT_VERSION, Metadata.ASCII_STRING_MARSHALLER);
		return Optional.ofNullable(metadata.get(clientVersion)).map(SemVer::fromString);
	}

}
