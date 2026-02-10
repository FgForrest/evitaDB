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

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to use an evitaDB resource that has already been terminated and released
 * its internal resources.
 *
 * This exception indicates a programming error where client code attempts to invoke methods on resources
 * that have been explicitly closed or shut down. Common scenarios include:
 * - Calling methods on an {@link EvitaContract} instance after {@link EvitaContract#close()} has been invoked
 * - Using an {@link EvitaSessionContract} after the session has been terminated
 * - Accessing a client instance that has been disconnected
 * - Using task trackers or change observers after they have been closed
 *
 * **Thread-Safety:**
 * This exception is typically thrown after atomic checks on termination flags (e.g., `AtomicBoolean` flags)
 * to ensure consistent behavior in concurrent environments.
 *
 * **Usage Context:**
 * - {@link io.evitadb.core.Evita}: thrown when calling methods on a terminated Evita instance
 * - {@link io.evitadb.core.session.EvitaSession}: thrown when using a closed session
 * - {@link io.evitadb.driver.EvitaClient}: thrown when using a terminated client connection
 * - {@link io.evitadb.driver.EvitaClientSession}: thrown when accessing a closed client session
 * - Change Data Capture (CDC) publishers and observers: thrown when accessing terminated CDC streams
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InstanceTerminatedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -9062588323022507459L;

	/**
	 * Creates a new exception indicating that a specific evitaDB resource instance has been terminated.
	 *
	 * @param instanceSpecification human-readable description of the terminated resource
	 *                              (e.g., "session", "client instance", "client task tracker")
	 */
	public InstanceTerminatedException(@Nonnull String instanceSpecification) {
		super("Evita " + instanceSpecification + " has been already terminated! No calls are accepted since all resources has been released.");
	}

}
