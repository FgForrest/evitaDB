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

package io.evitadb.externalApi.configuration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * This DTO record encapsulates mTLS configuration that will be used to hold information about gRPC hosts.
 *
 * @param enabled defines whether mTLS will be used to secure the server connection
 * @param allowedClientCertificatePaths defines a list of paths to the certificates of root certificate authorities that
 *                                      are not trusted publicly, but should be trusted by evitaDB's gRPC API server.
 *                                      Only clients who present themselves with a trusted certificate will be allowed
 *                                      to connect to the server.
 */
public record MtlsConfiguration(
	@Nullable Boolean enabled,
	@Nonnull List<String> allowedClientCertificatePaths
) {

}
