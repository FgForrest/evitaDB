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


import io.evitadb.externalApi.rest.io.RestHandlingContext;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RestWebSocketExecutor<CTX extends RestHandlingContext, P> {

	@Nonnull
	protected final CTX restHandlingContext;

	@Nonnull
	public Publisher<Object> subscribe(@Nonnull Map<String, Object> payload) {
		return this.doSubscribe(this.parsePayload(payload, this.getPayloadType()));
	}

	@Nonnull
	protected abstract Publisher<Object> doSubscribe(@Nullable P payload);

	@Nonnull
	protected abstract Class<P> getPayloadType();

	/**
	 * Tries to parse input payload into data class.
	 */
	@Nullable
	protected <T> T parsePayload(@Nonnull Map<String, Object> payload, @Nonnull Class<T> dataClass) {
		if (Void.class.equals(dataClass) || payload.isEmpty()) {
			return null;
		}
		return this.restHandlingContext.getObjectMapper().convertValue(payload, dataClass);
	}
}
