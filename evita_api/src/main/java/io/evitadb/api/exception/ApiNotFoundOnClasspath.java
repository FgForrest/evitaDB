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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown when attempting to enable an external API (gRPC, REST, GraphQL, System, Lab, or Observability)
 * that requires implementation libraries not present on the classpath.
 *
 * evitaDB's external APIs are modular - each API type (gRPC, REST, GraphQL, etc.) is packaged in a separate
 * module that must be included as a dependency to use that API. This exception is thrown during server
 * startup or API configuration when the requested API code is referenced but the corresponding implementation
 * module is missing from the classpath.
 *
 * **When this is thrown:**
 * - During evitaDB server startup when configuration enables an API without its module
 * - When programmatically enabling an API via `ApiOptions`
 * - Thrown by `ApiOptions.getEndpointConfiguration()` and `getProviderInstance()` methods
 *
 * **Resolution:**
 * - Add the missing API module to your project dependencies (e.g., `evita_external_api_grpc`)
 * - Remove the API configuration from your evitaDB configuration file
 * - Check that the API code matches a valid external API module
 *
 * **Valid API codes:** `grpc`, `rest`, `graphql`, `system`, `lab`, `observability`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ApiNotFoundOnClasspath extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 1747287542191934066L;

	/**
	 * Creates exception identifying which API module is missing from the classpath.
	 */
	public ApiNotFoundOnClasspath(@Nonnull String apiCode) {
		super("API `" + apiCode + "` was not found on classpath and cannot be used.");
	}

}
