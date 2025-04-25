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

package io.evitadb.api.exception;


import io.evitadb.api.CatalogState;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is throw when catalog is expected to be in {@link CatalogState#ALIVE} state but it is not.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CatalogNotAliveException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2375491490020341915L;
	@Getter private final String catalogName;

	public CatalogNotAliveException(@Nonnull String catalogName) {
		super(
			"Catalog `" + catalogName + "` is not alive. Please check the status of the catalog.",
			"Catalog `" + catalogName + "` is not alive. Please check the status of the catalog."
		);
		this.catalogName = catalogName;
	}
}
