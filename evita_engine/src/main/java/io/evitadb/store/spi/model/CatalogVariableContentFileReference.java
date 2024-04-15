/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.spi.model;

import io.evitadb.store.model.FileLocation;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.nio.file.Path;

/**
 * Interface defines shared methods for referencing files with records with variable length. Such files require
 * a header to be stored in the file allowing to read the records in the file. The header is stored in the file
 * among other records and the reference needs to provide its location in order to be able to read it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface CatalogVariableContentFileReference extends Serializable {

	/**
	 * Calculates file name for the entity collection file in catalog path.
	 *
	 * @param catalogFolder folder where catalog is stored
	 * @return file name for the entity collection file
	 */
	@Nonnull
	Path toFilePath(@Nonnull Path catalogFolder);

	/**
	 * Returns the numeric index of the file that is increased with each major operation with the file (vacuuming,
	 * renaming, rotating).
	 *
	 * @return the index of the file
	 */
	int fileIndex();

	/**
	 * Returns the location of the header in the file.
	 * @return the location of the header in the file
	 */
	@Nonnull
	FileLocation fileLocation();

	/**
	 * Increments the file index and returns a reference to the incremented value.
	 * This method is typically used after major operations with the file, such as vacuuming,
	 * renaming, or rotating.
	 *
	 * @return a reference to the incremented file index
	 */
	@Nonnull
	CatalogVariableContentFileReference incrementAndGet();

}
