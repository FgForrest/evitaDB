/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.observability.configuration;

/**
 * Selects which error hierarchies have the *place they were created* written to the log the first time that place is
 * seen. The error metrics themselves (`io_evitadb_errors_total`, `io_evitadb_client_errors_total`) are unaffected by
 * this setting - they are always collected, with exactly the same name and labels, whichever mode is in force.
 *
 * The setting exists because the metric counts an exception being *constructed*, and carries nothing but the class
 * name. An exception that is built and then swallowed, or thrown at a caller that has already disconnected, moves the
 * counter while leaving no other trace anywhere - no failed response, no error span, no log line. Recording the
 * origin is what turns such a counter movement into something an operator can act on.
 *
 * Java errors ({@link VirtualMachineError}) are deliberately absent from every mode: the JVM throws pre-allocated
 * `OutOfMemoryError` instances without running a constructor, so the hook is not reliably reached anyway, and
 * allocating a log message inside an `OutOfMemoryError` constructor is a good way to turn a survivable failure into
 * a fatal one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum ErrorOriginLogging {

	/**
	 * No origin is ever resolved or logged. The cheapest mode, and cheaper than evitaDB was before origin logging
	 * existed, because resolving an error code is lazy - nothing walks a stack unless somebody asks for the code.
	 */
	NONE,

	/**
	 * Origins of internal errors (`EvitaInternalError` and its subtypes) are logged. The default: an internal error
	 * is by its own definition a fault worth examining, its rate is low, and it is precisely the case that the bare
	 * counter cannot explain.
	 */
	INTERNAL,

	/**
	 * Origins of internal errors *and* client errors (`EvitaInvalidUsageException` and its subtypes) are logged.
	 * Opt-in, because client errors are raised on ordinary rejection paths - an unknown entity, a malformed query -
	 * so they are far more frequent, and each newly-seen origin costs one stack trace in the log.
	 */
	ALL

}
