/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.export.file;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.ExportServiceFactory;

import javax.annotation.Nonnull;

/**
 * This factory is implementation that instantiates local file system based export service, which stores exported files
 * into local file system to a location defined in {@link FileSystemExportOptions#getDirectory()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExportFileServiceFactory implements ExportServiceFactory {

	@Nonnull
	@Override
	public String getImplementationCode() {
		return FileSystemExportOptions.IMPLEMENTATION_CODE;
	}

	@Nonnull
	@Override
	public Class<FileSystemExportOptions> getConfigurationClass() {
		return FileSystemExportOptions.class;
	}

	@Override
	public int getPriority() {
		return 100;
	}

	@Nonnull
	@Override
	public ExportOptions createDefaultOptions() {
		return new FileSystemExportOptions();
	}

	@Nonnull
	@Override
	public ExportService create(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull FileManagementService fileManagementService
	) {
		return new ExportFileService(exportOptions, scheduler);
	}

}
