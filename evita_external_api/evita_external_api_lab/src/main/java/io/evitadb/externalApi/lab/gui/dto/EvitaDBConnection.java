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

package io.evitadb.externalApi.lab.gui.dto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a single connection to any remote or local evitaDB instance. This is used in the evitaLab
 * to maintain several connections to different evitaDB instances. We need this here to pass preconfigured connections
 * to the evitaLab.
 *
 * @param id optional unique identifier of the connection
 * @param name name of the connection displayed to users
 * @param systemUrl URL of the system API
 * @param grpcUrl gRPC API URL
 * @param restUrl optional URL of the REST API of the target evitaDB instance
 * @param gqlUrl optional URL of the GraphQL API of the target evitaDB instance
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public record EvitaDBConnection(@Nullable String id,
                                @Nonnull String name,
                                @Nonnull String systemUrl,
								@Nonnull String grpcUrl,
								@Nullable String gqlUrl,
								@Nullable String restUrl) {
}
