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

import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Function;

/**
 * Implementation of a stream processor that converts incoming items to DTOs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class DtoConvertingStreamProcessor<T, D> implements Processor<T, D> {

	@Nullable
	private Subscriber<? super D> downstream;

	@Nonnull
	private final Flow.Publisher<T> upstream;
	@Nonnull
	private final Function<T, D> dtoMapper;

	@Override
	public void subscribe(Subscriber<? super D> subscriber) {
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
	public void onNext(T item) {
		if (this.downstream != null) {
			final D dto = this.dtoMapper.apply(item);
			this.downstream.onNext(dto);
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
