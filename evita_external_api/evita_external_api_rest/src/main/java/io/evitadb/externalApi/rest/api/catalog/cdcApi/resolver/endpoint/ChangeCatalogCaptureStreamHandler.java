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

package io.evitadb.externalApi.rest.api.catalog.cdcApi.resolver.endpoint;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.dto.ChangeCatalogCaptureRequestDto;
import io.evitadb.externalApi.rest.api.catalog.cdcApi.resolver.serializer.ChangeCatalogCaptureSerializer;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.io.webSocket.DtoConvertingStreamProcessor;
import io.evitadb.externalApi.rest.io.webSocket.RestWebSocketExecutor;
import io.evitadb.externalApi.rest.io.webSocket.RestWebSocketHandler;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * REST API for {@link io.evitadb.core.EvitaSession#registerChangeCatalogCapture(ChangeCatalogCaptureRequest)} using WebSockets.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ChangeCatalogCaptureStreamHandler extends RestWebSocketHandler<CatalogRestHandlingContext> {

	public ChangeCatalogCaptureStreamHandler(
		@Nonnull CatalogRestHandlingContext restHandlingContext
	) {
		super(
			restHandlingContext,
			new ChangeCatalogCaptureStreamExecutor(restHandlingContext)
		);
	}

	private static class ChangeCatalogCaptureStreamExecutor extends RestWebSocketExecutor<CatalogRestHandlingContext, ChangeCatalogCaptureRequestDto> {

		private static final ChangeCatalogCaptureRequest EMPTY_REQUEST = new ChangeCatalogCaptureRequest(
			null,
			null,
			null,
			ChangeCaptureContent.HEADER
		);

		@Nonnull private final ChangeCatalogCaptureSerializer changeCatalogCaptureSerializer;

		public ChangeCatalogCaptureStreamExecutor(@Nonnull CatalogRestHandlingContext restHandlingContext) {
			super(restHandlingContext);
			this.changeCatalogCaptureSerializer = new ChangeCatalogCaptureSerializer(restHandlingContext);
		}

		@Nonnull
		@Override
		protected Publisher<Object> doSubscribe(@Nullable ChangeCatalogCaptureRequestDto payload) {
			final ChangeCatalogCaptureRequest request = Optional.ofNullable(payload)
				.map(ChangeCatalogCaptureRequestDto::toRequest)
				.orElse(EMPTY_REQUEST);

			return FlowAdapters.toProcessor(
				new DtoConvertingStreamProcessor<>(
					this.restHandlingContext.getEvita().queryCatalog(
						this.restHandlingContext.getCatalogSchema().getName(),
						session -> {
							return session.registerChangeCatalogCapture(request);
						}
					),
					this.changeCatalogCaptureSerializer::serialize
				)
			);
		}

		@Nonnull
		@Override
		protected Class<ChangeCatalogCaptureRequestDto> getPayloadType() {
			return ChangeCatalogCaptureRequestDto.class;
		}
	}
}
