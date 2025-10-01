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

package io.evitadb.externalApi.rest.io.webSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectMapper;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class DummyRestWebSocketExecutor implements RestWebSocketExecutor<Map<String, Object>> {

	@Nonnull private final Evita evita;

	@Override
	@Nonnull
	public Publisher<Map<String, Object>> subscribe(@Nonnull Map<String, Object> payload) {
		// todo lNho params
		return FlowAdapters.toProcessor(
			new Pub(this.evita.registerSystemChangeCapture(
				new ChangeSystemCaptureRequest(
					null,
					null,
					ChangeCaptureContent.BODY
				)
			))
		);
	}

	@RequiredArgsConstructor
	class Pub implements Processor<ChangeSystemCapture, Map<String, Object>> {

		@Nullable
		private Subscriber<? super Map<String, Object>> downstream;

		@Nonnull
		private final Flow.Publisher<ChangeSystemCapture> upstream;

		@Nonnull
		private final DelegatingEngineMutationConverter delegatingEngineMutationConverter = new DelegatingEngineMutationConverter(
			new RestMutationObjectMapper(/*restApiHandlingContext.getObjectMapper()*/ new ObjectMapper()), // todo lho
			RestMutationResolvingExceptionFactory.INSTANCE
		);

		@Override
		public void subscribe(Subscriber<? super Map<String, Object>> subscriber) {
			this.downstream = subscriber;
			this.upstream.subscribe(this);
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			if (this.downstream != null) {
				this.downstream.onSubscribe(subscription);
			}
		}

		@Override
		public void onNext(ChangeSystemCapture item) {
			if (this.downstream != null) {
				final Map<String, Object> itemDto = new HashMap<>();
				itemDto.put("version", item.version());
				itemDto.put("index", item.index());
				itemDto.put("operation", item.operation());
				if (item.body() != null) {
					itemDto.put("body", this.delegatingEngineMutationConverter.convertToOutput(item.body()));
				}
				// todo lho
				this.downstream.onNext(itemDto);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			if (this.downstream != null) {
				this.downstream.onError(throwable);
			}
		}

		@Override
		public void onComplete() {
			if (this.downstream != null) {
				this.downstream.onComplete();
			}
		}
	}
}
