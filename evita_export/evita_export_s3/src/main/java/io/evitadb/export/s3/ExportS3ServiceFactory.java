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

package io.evitadb.export.s3;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.ExportServiceFactory;

import javax.annotation.Nonnull;

/**
 * This factory is implementation that instantiates S3-based export service, which stores exported files
 * into S3-compatible storage (such as Amazon S3 or MinIO).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExportS3ServiceFactory implements ExportServiceFactory {

	@Nonnull
	@Override
	public String getImplementationCode() {
		return S3ExportOptions.IMPLEMENTATION_CODE;
	}

	@Nonnull
	@Override
	public Class<S3ExportOptions> getConfigurationClass() {
		return S3ExportOptions.class;
	}

	@Override
	public int getPriority() {
		return 50;
	}

	@Nonnull
	@Override
	public ExportOptions createDefaultOptions() {
		return new S3ExportOptions();
	}

	@Nonnull
	@Override
	public ExportService create(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull FileManagementService fileManagementService
	) {
		return new ExportS3Service(exportOptions, scheduler, fileManagementService);
	}

}
