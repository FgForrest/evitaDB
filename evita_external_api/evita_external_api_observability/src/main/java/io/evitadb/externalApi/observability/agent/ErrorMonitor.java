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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.observability.agent;

import io.evitadb.externalApi.observability.ObservabilityManager;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * This simple class serves as a mediator between advices in the ErrorMonitoringAgent and the {@link ObservabilityManager},
 * which registers lambda functions to be called when an error is detected.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ErrorMonitor {
	@Setter private static Consumer<String> javaErrorConsumer;
	@Setter private static Consumer<String> evitaErrorConsumer;

	/**
	 * Method is called by the ErrorMonitoringAgent advice when a Java error is detected.
	 * @param errorType the type of the error
	 */
	public static void registerJavaError(@Nonnull String errorType) {
		if (javaErrorConsumer != null) {
			javaErrorConsumer.accept(errorType);
		}
	}

	/**
	 * Method is called by the ErrorMonitoringAgent advice when an Evita error is detected.
	 * @param errorType the type of the error
	 */
	public static void registerEvitaError(@Nonnull String errorType) {
		if (evitaErrorConsumer != null) {
			evitaErrorConsumer.accept(errorType);
		}
	}

}
