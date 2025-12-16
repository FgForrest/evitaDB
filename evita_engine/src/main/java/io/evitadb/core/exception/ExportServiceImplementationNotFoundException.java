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

package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.spi.export.ExportServiceFactory;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * The exception is thrown when implementation of {@link ExportServiceFactory} is not found on the classpath.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExportServiceImplementationNotFoundException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -7687226196958501956L;

	public ExportServiceImplementationNotFoundException() {
		super(
			"Export service implementation is not found on classpath! " +
			"Please, make sure you have `evita_export_fs` JAR (or the other implementation of " +
				"`io.evitadb.spi.export.ExportServiceFactory`) on the classpath."
		);
	}

	/**
	 * Creates exception indicating that the requested export service implementation was not found.
	 *
	 * @param implementationCode the implementation code that was requested but not found
	 */
	public ExportServiceImplementationNotFoundException(@Nonnull String implementationCode) {
		super(
			"Export service implementation `" + implementationCode + "` is not found on classpath! " +
			"Please, make sure you have the appropriate JAR (e.g. `evita_export_fs` for `fileSystem` " +
			"or `evita_export_s3` for `s3`) on the classpath."
		);
	}

}
