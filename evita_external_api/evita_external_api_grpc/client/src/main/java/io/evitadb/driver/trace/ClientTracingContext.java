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

package io.evitadb.driver.trace;

import io.grpc.ClientInterceptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Client contexts interface that defines all necessary resources for proper tracing.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public interface ClientTracingContext {
	/**
	 * Marker default method that should in its implementation provide an implementation of a gRPC ClientInterceptor.
	 */
	@Nullable
	default ClientInterceptor getClientInterceptor() {
		return null;
	}

	/**
	 * Marker default method that should in its implementation set the tracing endpoint URL and protocol.
	 */
	default void setTracingEndpointUrlAndProtocol(@Nonnull String tracingEndpointUrl, @Nonnull String tracingEndpointProtocol) {
		// do nothing
	}
}
