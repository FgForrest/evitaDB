/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.spi.export;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;

import javax.annotation.Nonnull;
import java.util.ServiceLoader;

/**
 * This interface and layer of abstraction was introduced because we want to have different implementations of an export
 * service - namely local file system and S3. Therefore, we used {@link ServiceLoader} pattern to dynamically
 * locate proper implementation of this interface and link these modules in runtime.
 *
 * The export service itself also needs initial configuration from the main evitaDB class, and therefore we need this
 * factory to pass the configuration from the main module into the export module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ExportServiceFactory {

	/**
	 * Returns unique implementation code that identifies this export service type.
	 * This code must match the implementation code returned by the corresponding
	 * {@link ExportOptions#getImplementationCode()}.
	 *
	 * @return implementation code (e.g., "fileSystem" or "s3")
	 */
	@Nonnull
	String getImplementationCode();

	/**
	 * Returns the configuration class for this export service type.
	 * Used for dynamic YAML deserialization via Jackson.
	 *
	 * @return configuration class extending {@link ExportOptions}
	 */
	@Nonnull
	Class<? extends ExportOptions> getConfigurationClass();

	/**
	 * Returns priority for default selection when no implementation is explicitly enabled.
	 * Higher value means higher priority. FileSystem implementation should return higher
	 * priority to be the default choice.
	 *
	 * @return priority value (higher = more preferred as default)
	 */
	default int getPriority() {
		return 0;
	}

	/**
	 * Creates default configuration instance with sensible defaults.
	 * Used when no configuration is provided in YAML for this implementation.
	 *
	 * @return default configuration options
	 */
	@Nonnull
	ExportOptions createDefaultOptions();

	/**
	 * Creates new instance of {@link ExportService}.
	 *
	 * @param exportOptions export configuration options
	 * @param scheduler     scheduler for background tasks
	 * @return configured export service instance
	 */
	@Nonnull
	ExportService create(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull FileManagementService fileManagementService
	);

}
