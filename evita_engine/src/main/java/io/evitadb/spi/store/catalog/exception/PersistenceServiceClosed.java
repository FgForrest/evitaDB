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

package io.evitadb.spi.store.catalog.exception;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;

import java.io.Serial;

/**
 * Exception is thrown when a {@link StoragePartPersistenceService} method is invoked after the service has been
 * {@link StoragePartPersistenceService#close() closed}.
 *
 * Every data-access method of `StoragePartPersistenceService` implementations (e.g., `OffsetIndexStoragePartPersistenceService`)
 * guards against use-after-close by checking whether the underlying offset index is still operative. When it is not,
 * this exception is raised instead of attempting an operation on a released resource.
 *
 * Callers that need to tolerate a race between shutdown and in-flight operations (for example `Catalog.forgetVolatileData`)
 * are expected to catch this exception explicitly and treat it as a benign signal that the service is already gone.
 *
 * This is an {@link EvitaInternalError}, meaning it signals a programming error or an unexpected system state rather
 * than a recoverable client-side condition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PersistenceServiceClosed extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -7895102315008153201L;

	/**
	 * Creates the exception with a fixed diagnostic message. No additional context is provided because the failure
	 * mode is unambiguous: the service lifecycle has ended before all callers were notified.
	 */
	public PersistenceServiceClosed() {
		super("The persistence service was already closed!");
	}

}
