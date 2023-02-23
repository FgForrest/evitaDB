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

package io.evitadb.store.spi.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.Serial;
import java.nio.file.Path;

/**
 * Exception is thrown when the catalog directory doesn't contain the file `header.dat` which contains the key
 * information for work with MemTable files representing the catalog data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HeaderFileNotFound extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -337988885403543275L;
	@Getter private final Path catalogDirectory;
	@Getter private final File headerFile;

	public HeaderFileNotFound(@Nonnull Path catalogDirectory, @Nonnull File headerFile) {
		super(
			"Failed to locate header file for catalog `" + catalogDirectory + "`.",
			"Failed to locate header file for catalog `" + catalogDirectory.getName(catalogDirectory.getNameCount() - 1) + "`."
		);
		this.catalogDirectory = catalogDirectory;
		this.headerFile = headerFile;
	}
}
