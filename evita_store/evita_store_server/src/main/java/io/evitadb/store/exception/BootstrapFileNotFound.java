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

package io.evitadb.store.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.Serial;
import java.nio.file.Path;

/**
 * Exception is thrown when the catalog directory doesn't contain its bootstrap file - the `<storagePrefix>.boot`
 * file which contains the key information for work with file offset index files representing the catalog data.
 * The directory may hold catalog data whose bootstrap record is gone, or nothing beyond evitaDB's own markers
 * because the operation that allocated it never finished writing into it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class BootstrapFileNotFound extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -337988885403543275L;
	@Getter private final Path catalogDirectory;
	@Getter private final File bootstrapFile;

	/**
	 * Reports a catalog directory that has no bootstrap file, without saying why it has none.
	 *
	 * The message it produces claims the directory is not empty whether or not that holds, so prefer the
	 * three-argument variant wherever the caller can tell an unfinished allocation apart from a lost bootstrap
	 * record.
	 *
	 * @param catalogDirectory directory the catalog was expected in
	 * @param bootstrapFile    bootstrap file that was looked for and not found
	 */
	public BootstrapFileNotFound(@Nonnull Path catalogDirectory, @Nonnull File bootstrapFile) {
		super(
			"Failed to locate bootstrap file for catalog `" + catalogDirectory + "` and the directory is not empty!",
			"Failed to locate bootstrap file for catalog `" + catalogDirectory.getName(catalogDirectory.getNameCount() - 1) + "`."
		);
		this.catalogDirectory = catalogDirectory;
		this.bootstrapFile = bootstrapFile;
	}

	/**
	 * Variant that distinguishes a folder holding *nothing but* evitaDB's own markers from one holding data it
	 * cannot find a bootstrap record for.
	 *
	 * The two are the same failure - a catalog that must exist does not - but they point at different causes, and
	 * saying which is what stops the reader looking for the wrong thing. A folder wearing only its markers was
	 * allocated by a create, restore or duplicate that never finished writing; a folder with data in it and no
	 * bootstrap record has lost the record rather than the data.
	 *
	 * @param catalogDirectory  directory the catalog was expected in
	 * @param bootstrapFile     bootstrap file that was looked for and not found
	 * @param holdsNoCatalogData whether the directory holds nothing beyond evitaDB's own marker files
	 */
	public BootstrapFileNotFound(
		@Nonnull Path catalogDirectory,
		@Nonnull File bootstrapFile,
		boolean holdsNoCatalogData
	) {
		super(
			holdsNoCatalogData ?
				"Catalog directory `" + catalogDirectory + "` holds no catalog data at all - it was allocated " +
					"by an operation that never finished writing into it!" :
				"Failed to locate bootstrap file for catalog `" + catalogDirectory +
					"` and the directory is not empty!",
			"Failed to locate bootstrap file for catalog `" +
				catalogDirectory.getName(catalogDirectory.getNameCount() - 1) + "`."
		);
		this.catalogDirectory = catalogDirectory;
		this.bootstrapFile = bootstrapFile;
	}
}
