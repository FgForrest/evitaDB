/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.externalApi.rest.api.system.dto.ChangeSystemCaptureRequestDto;
import io.evitadb.externalApi.rest.api.system.resolver.serializer.ChangeSystemCaptureSerializer;
import io.evitadb.externalApi.rest.io.webSocket.DtoConvertingStreamProcessor;
import io.evitadb.externalApi.rest.io.webSocket.RestWebSocketExecutor;
import io.evitadb.externalApi.rest.io.webSocket.RestWebSocketHandler;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ChangeSystemCaptureStreamHandler extends RestWebSocketHandler<SystemRestHandlingContext> {

	public ChangeSystemCaptureStreamHandler(
		@Nonnull SystemRestHandlingContext restHandlingContext
	) {
		super(
			restHandlingContext,
			new ChangeSystemCaptureStreamExecutor(restHandlingContext),
			null // todo lho should there be something?
		);
	}

	private static class ChangeSystemCaptureStreamExecutor extends RestWebSocketExecutor<SystemRestHandlingContext, ChangeSystemCaptureRequestDto> {

		private static final ChangeSystemCaptureRequest EMPTY_REQUEST = new ChangeSystemCaptureRequest(
			null,
			null,
			ChangeCaptureContent.HEADER
		);

		@Nonnull private final ChangeSystemCaptureSerializer changeSystemCaptureSerializer;

		public ChangeSystemCaptureStreamExecutor(@Nonnull SystemRestHandlingContext restHandlingContext) {
			super(restHandlingContext);
			this.changeSystemCaptureSerializer = new ChangeSystemCaptureSerializer(restHandlingContext);
		}

		@Nonnull
		@Override
		protected Publisher<Object> doSubscribe(@Nullable ChangeSystemCaptureRequestDto payload) {
			final ChangeSystemCaptureRequest request = Optional.ofNullable(payload)
				.map(ChangeSystemCaptureRequestDto::toRequest)
				.orElse(EMPTY_REQUEST);

			return FlowAdapters.toProcessor(
				new DtoConvertingStreamProcessor<>(
					this.restHandlingContext.getEvita().registerSystemChangeCapture(
						request
					),
					this.changeSystemCaptureSerializer::serialize
				)
			);
		}

		@Nonnull
		@Override
		protected Class<ChangeSystemCaptureRequestDto> getPayloadType() {
			return ChangeSystemCaptureRequestDto.class;
		}
	}
}
