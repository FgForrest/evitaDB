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

package io.evitadb.externalApi.http;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;

import javax.annotation.Nonnull;

/**
 * Configures and registers provider of particular external API to HTTP server ({@link ExternalApiServer}).
 * Each provider have to have unique code and have to implement {@link #register(Undertow.Builder, PathHandler, Evita, T)}
 * method which registers provider to the server to be later started by the server.
 *
 * It is based on {@link java.util.ServiceLoader} which requires appropriate registration of implementation of this interface.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ExternalApiProviderRegistrar<T extends AbstractApiConfiguration> {

    /**
     * Returns unique identification code of the API registrar.
     *
     * @return same code as linked {@link ExternalApiProvider#getCode()}
     */
    @Nonnull
    String getExternalApiCode();

    /**
     * Returns configuration initialized with default values for this external API.
     *
     * @return configuration object instance with sane default values
     */
    @Nonnull
    Class<T> getConfigurationClass();

    /**
     * Configures and registers this provider
     *
     * @param evitaSystemDataProvider ready-to-use Evita with access to internal data structures
     * @param externalApiConfiguration configuration parameters for this provider (structure is defined by provider itself)
     */
    @Nonnull
    ExternalApiProvider register(@Nonnull EvitaSystemDataProvider evitaSystemDataProvider, @Nonnull ApiOptions apiOptions, @Nonnull T externalApiConfiguration);
}
