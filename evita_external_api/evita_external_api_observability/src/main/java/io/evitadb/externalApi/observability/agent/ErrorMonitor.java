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

package io.evitadb.externalApi.observability.agent;

import lombok.Setter;

import java.util.function.Consumer;

/**
 * Mediator between the advice woven in by `ErrorMonitoringAgent` and the observability manager, which registers the
 * lambdas to be called when an error is constructed.
 *
 * ## This class is injected into the bootstrap classloader
 *
 * `ErrorMonitoringAgent#premain` injects these bytes into the bootstrap loader, so that the advice - which ends up
 * inlined into exception constructors loaded by every classloader in the JVM - can always resolve it. A bootstrap
 * class can only see `java.*`. **No signature or method body here may name a type from `io.evitadb`, from Lombok's
 * runtime, or from any library**: doing so compiles, passes every unit test, and then fails with
 * `NoClassDefFoundError` only in an agent-attached server. That is why the parameters below are
 * `java.lang.Throwable` and `java.util.function.Consumer`, why nothing is annotated, and why the types this class
 * talks about are named in prose rather than linked - a javadoc `{@link}` would need an import, and an import here
 * is one careless "optimise imports" away from becoming a real reference.
 *
 * Everything that needs to inspect the exception - reading its type, honouring the `io.evitadb.exception.NotMonitored`
 * opt-out, resolving where it was created - therefore happens on the consumer side, in the observability manager,
 * which is an ordinary application class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ErrorMonitor {
	@Setter private static Consumer<Throwable> javaErrorConsumer;
	@Setter private static Consumer<Throwable> evitaErrorConsumer;
	@Setter private static Consumer<Throwable> clientErrorConsumer;

	/**
	 * Called by the agent advice when a JVM error (a `java.lang.VirtualMachineError`) is constructed.
	 *
	 * @param error the error being constructed; not yet fully initialised
	 */
	public static void registerJavaError(Throwable error) {
		final Consumer<Throwable> consumer = javaErrorConsumer;
		if (consumer != null) {
			consumer.accept(error);
		}
	}

	/**
	 * Called by the agent advice when an evitaDB internal error (an `io.evitadb.exception.EvitaInternalError`) is
	 * constructed.
	 *
	 * @param error the error being constructed; not yet fully initialised
	 */
	public static void registerEvitaError(Throwable error) {
		final Consumer<Throwable> consumer = evitaErrorConsumer;
		if (consumer != null) {
			consumer.accept(error);
		}
	}

	/**
	 * Called by the agent advice when a client error (an `io.evitadb.exception.EvitaInvalidUsageException`) is
	 * constructed.
	 *
	 * @param error the error being constructed; not yet fully initialised
	 */
	public static void registerClientError(Throwable error) {
		final Consumer<Throwable> consumer = clientErrorConsumer;
		if (consumer != null) {
			consumer.accept(error);
		}
	}

}
