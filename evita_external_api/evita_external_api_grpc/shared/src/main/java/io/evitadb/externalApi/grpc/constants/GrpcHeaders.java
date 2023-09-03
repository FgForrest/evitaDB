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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.constants;

/**
 * Shared gRPC constant repository. The constants that we want o be shared between the Java client and the gRPC server.
 * The interface also serves as a guideline for clients on different platforms.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface GrpcHeaders {
	/**
	 * Constant string representing catalog name that is used to fetch session from context.
	 */
	String CATALOG_NAME_HEADER = "catalogName";
	/**
	 * Constant string representing sessionId that is used to fetch session from context.
	 */
	String SESSION_ID_HEADER = "sessionId";
	/**
	 * Constant string representing clientId that is used to fetch session from context.
	 */
	String CLIENT_ID_HEADER = "clientId";
	/**
	 * Constant string representing requestId that is used to fetch session from context.
	 */
	String REQUEST_ID_HEADER = "requestId";
}
