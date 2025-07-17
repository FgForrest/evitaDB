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

import io.evitadb.core.UnusableCatalog;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.nio.file.Path;

/**
 * This exception is thrown when there is any attempt to work with {@link UnusableCatalog}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogCorruptedException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -29086463503529567L;

	public CatalogCorruptedException(
		@Nonnull String catalogName,
		@Nonnull Path absolutePath,
		@Nonnull Throwable cause
	) {
		super(
			"Catalog on path `" + absolutePath +
				"` was not correctly initialized due to: " + cause.getMessage() + ". " +
				"The catalog `" + catalogName + "` cannot be used - only deleted or repaired.",
			"Catalog `" + catalogName + "` cannot be used because it wasn't correctly " +
				"initialized. You can only delete it or try to repair it manually.",
			cause
		);
	}

}
