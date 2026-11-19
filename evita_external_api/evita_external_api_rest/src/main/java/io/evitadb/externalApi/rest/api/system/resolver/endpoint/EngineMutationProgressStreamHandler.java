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

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import io.evitadb.externalApi.rest.api.system.dto.EngineMutationProgressRequestDto;
import io.evitadb.externalApi.rest.api.system.model.EngineMutationProgressRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.resolver.serializer.EngineMutationProgressChangeSerializer;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.webSocket.DtoConvertingStreamProcessor;
import io.evitadb.externalApi.rest.io.webSocket.RestWebSocketExecutor;
import io.evitadb.externalApi.rest.io.webSocket.RestWebSocketHandler;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * REST API for {@link io.evitadb.core.Evita#subscribeToEngineMutationProgress(java.util.UUID)} using WebSockets:
 * streams the progress of an engine operation started by an asynchronous (`catalogs-async`) endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class EngineMutationProgressStreamHandler extends RestWebSocketHandler<SystemRestHandlingContext> {

	public EngineMutationProgressStreamHandler(@Nonnull SystemRestHandlingContext restHandlingContext) {
		super(
			restHandlingContext,
			new EngineMutationProgressStreamExecutor(restHandlingContext)
		);
	}

	/**
	 * Subscribes to the progress named by the `progressId` of the payload.
	 */
	private static class EngineMutationProgressStreamExecutor
		extends RestWebSocketExecutor<SystemRestHandlingContext, EngineMutationProgressRequestDto> {

		@Nonnull private final EngineMutationProgressChangeSerializer serializer;

		public EngineMutationProgressStreamExecutor(@Nonnull SystemRestHandlingContext restHandlingContext) {
			super(restHandlingContext);
			this.serializer = new EngineMutationProgressChangeSerializer(restHandlingContext);
		}

		@Nonnull
		@Override
		protected Publisher<Object> doSubscribe(@Nullable EngineMutationProgressRequestDto payload) {
			if (payload == null || payload.progressId() == null) {
				throw new RestInvalidArgumentException(
					"The subscription payload must contain `" +
						EngineMutationProgressRequestDescriptor.PROGRESS_ID.name() + "`."
				);
			}
			return FlowAdapters.toProcessor(
				new DtoConvertingStreamProcessor<>(
					this.restHandlingContext.getEvita().subscribeToEngineMutationProgress(payload.progressId()),
					this.serializer::serialize
				)
			);
		}

		@Nonnull
		@Override
		protected Class<EngineMutationProgressRequestDto> getPayloadType() {
			return EngineMutationProgressRequestDto.class;
		}
	}
}
