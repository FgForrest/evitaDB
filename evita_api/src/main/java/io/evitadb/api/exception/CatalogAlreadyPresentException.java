/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when client code tries to create a new catalog with same name as existing catalog. This is
 * not allowed and client must choose different name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogAlreadyPresentException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4492948359569503645L;
	@Getter private final String catalogName;
	@Getter private final String existingCatalogName;

	public CatalogAlreadyPresentException(@Nonnull String catalogName, @Nonnull String existingCatalogName) {
		super(
			"Catalog with name `" + catalogName + "` already exists! " +
				"Please choose different catalog name."
		);
		this.catalogName = catalogName;
		this.existingCatalogName = existingCatalogName;
	}

	public CatalogAlreadyPresentException(
		@Nonnull String catalogName,
		@Nonnull String existingCatalogName,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName) {
		super(
			"Catalog `" + catalogName + "` and existing " +
				"catalog `" + existingCatalogName + "` produce the same " +
				"name `" + conflictingName + "` in `" + convention + "` convention! " +
				"Please choose different catalog name."
		);
		this.catalogName = catalogName;
		this.existingCatalogName = existingCatalogName;
	}
}
