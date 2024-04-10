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

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.spi.StoragePartPersistenceService;

import java.io.Serial;

/**
 * Exception is thrown when the {@link StoragePartPersistenceService} is called but it was previously
 * {@link StoragePartPersistenceService#close() closed}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PersistenceServiceClosed extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -7895102315008153201L;

	public PersistenceServiceClosed() {
		super("The persistence service was already closed!");
	}

}
