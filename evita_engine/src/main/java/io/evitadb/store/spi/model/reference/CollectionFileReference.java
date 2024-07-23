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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.spi.model.reference;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.model.CatalogVariableContentFileReference;
import io.evitadb.store.spi.model.EntityCollectionHeader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;

import static io.evitadb.store.spi.CatalogPersistenceService.getEntityCollectionDataStoreFileName;

/**
 * Represents a reference to a file that contains the data for particular entity collection.
 *
 * @param entityType           The entity type.
 * @param entityTypePrimaryKey The entity type primary key.
 * @param fileIndex            The file index that is incremented for each new file during vacuuming and renaming
 *                             operations
 * @param fileLocation         Location of the {@link EntityCollectionHeader} in the file
 */
public record CollectionFileReference(
	@Nonnull String entityType,
	int entityTypePrimaryKey,
	int fileIndex,
	@Nullable FileLocation fileLocation
) implements CatalogVariableContentFileReference {

	@Override
	@Nonnull
	public Path toFilePath(@Nonnull Path catalogFolder) {
		return catalogFolder.resolve(
			getEntityCollectionDataStoreFileName(this.entityType, this.entityTypePrimaryKey, this.fileIndex)
		);
	}

	@Override
	@Nonnull
	public CollectionFileReference incrementAndGet() {
		return new CollectionFileReference(this.entityType, this.entityTypePrimaryKey, this.fileIndex + 1, null);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		CollectionFileReference that = (CollectionFileReference) o;

		if (this.fileIndex != that.fileIndex) return false;
		if (this.entityTypePrimaryKey != that.entityTypePrimaryKey) return false;
		return this.entityType.equals(that.entityType);
	}

	@Override
	public int hashCode() {
		int result = this.entityType.hashCode();
		result = 31 * result + this.entityTypePrimaryKey;
		result = 31 * result + this.fileIndex;
		return result;
	}

}
