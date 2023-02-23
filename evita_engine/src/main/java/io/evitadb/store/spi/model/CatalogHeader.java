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

package io.evitadb.store.spi.model;

import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.model.FileLocation;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;

/**
 * Catalog header contains crucial information to read data from a single data storage file. The catalog header needs
 * to be stored in {@link CatalogBootstrap} and maps the data maintained by {@link Catalog} object.
 *
 * @see PersistentStorageHeader
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogHeader extends PersistentStorageHeader {
	@Serial private static final long serialVersionUID = -3595987669559870397L;

	/**
	 * Contains name of the catalog that originates in {@link CatalogSchema#getName()}.
	 */
	@Getter @Nonnull private final String catalogName;
	/**
	 * Contains the last assigned {@link EntityCollection#getEntityTypePrimaryKey()}.
	 */
	@Getter private final int lastEntityCollectionPrimaryKey;

	public CatalogHeader(@Nonnull String catalogName, int lastEntityCollectionPrimaryKey) {
		this.catalogName = catalogName;
		this.lastEntityCollectionPrimaryKey = lastEntityCollectionPrimaryKey;
	}

	public CatalogHeader(long version, @Nullable FileLocation fileLocation, @Nonnull Map<Integer, Object> compressedKeys, @Nonnull String catalogName, int lastEntityCollectionPrimaryKey) {
		super(version, fileLocation, compressedKeys);
		this.catalogName = catalogName;
		this.lastEntityCollectionPrimaryKey = lastEntityCollectionPrimaryKey;
	}

}
