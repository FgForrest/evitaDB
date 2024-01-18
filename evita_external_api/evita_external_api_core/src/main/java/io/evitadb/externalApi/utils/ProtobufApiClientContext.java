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

package io.evitadb.externalApi.utils;

import io.evitadb.api.ClientContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.function.Supplier;

public abstract class ProtobufApiClientContext extends ExternalApiClientContext {

	public ProtobufApiClientContext(@Nonnull ClientContext internalClientContext) {
		super(internalClientContext);
	}

	public void executeWithClientAndRequestId(@Nonnull SocketAddress clientAddress,
	                                          @Nullable String clientId,
	                                          @Nullable String requestId,
	                                          @Nonnull Runnable lambda) {
		super.executeWithClientAndRequestId(clientAddress, clientId, requestId, lambda);
	}

	public <T> T executeWithClientAndRequestId(@Nonnull SocketAddress clientAddress,
	                                           @Nullable String clientId,
	                                           @Nullable String requestId,
	                                           @Nonnull Supplier<T> lambda) {
		return super.executeWithClientAndRequestId(clientAddress, clientId, requestId, lambda);
	}
}
