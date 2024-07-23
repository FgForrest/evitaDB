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

package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

import java.io.Serial;

/**
 * The exception is thrown when implementation of {@link CatalogPersistenceServiceFactory} is not found on the
 * classpath.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class StorageImplementationNotFoundException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 3177166005139359286L;

	public StorageImplementationNotFoundException() {
		super(
			"Storage implementation is not found on classpath! " +
			"Please, make sure you have `evita_store_server` JAR (or the other implementation of " +
				"`io.evitadb.store.spi.CatalogPersistenceServiceFactory`) on the classpath."
		);
	}

}
