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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.server.configuration;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.externalApi.configuration.ApiOptions;

import javax.annotation.Nonnull;

/**
 * Encapsulating DTO record allowing to setup {@link EvitaConfiguration} and {@link ApiOptions} within one root object.
 * This class is used by Jackson to deserialize settings from configuration YAML file.
 *
 * @param name         refers to {@link EvitaConfiguration#name()}
 * @param server       refers to {@link EvitaConfiguration#server()}
 * @param storage      refers to {@link EvitaConfiguration#storage()}
 * @param transactions refers to {@link EvitaConfiguration#transactions()}
 * @param cache        refers to {@link EvitaConfiguration#cache()}
 * @param api          contains configuration for API endpoints
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaServerConfiguration(
	@Nonnull String name,
	@Nonnull ServerOptions server,
	@Nonnull StorageOptions storage,
	@Nonnull TransactionOptions transactions,
	@Nonnull CacheOptions cache,
	@Nonnull ApiOptions api
) {

}
