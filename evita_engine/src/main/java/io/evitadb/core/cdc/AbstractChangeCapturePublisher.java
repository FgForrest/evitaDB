/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureRequest;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Consumer;

/**
 * TODO LHO: rewrite docs
 * Server-side implementation of the {@link ChangeCapturePublisher}. Basically, it delegates captures from internal
 * evitaDB observer to the subscribers with back-pressure implementation.
 *
 * <p>
 * It supports multiple concurrent subscribers. The captures are multicasted to all subscribers that are able to receive
 * more captures.
 *
 * <p>
 * This publisher doesn't magically know about captures, all captures that should be sent to subscribers must be explicitly handed
 * to this publisher using the {@link #tryOffer(ChangeCapture)}. This publisher handles the rest of the
 * delegation to subscribers.
 *
 * @author Lukáš Hornych, 2023
 */
public abstract class AbstractChangeCapturePublisher
	<P extends AbstractChangeCapturePublisher<P, C, R>, C extends ChangeCapture, R extends ChangeCaptureRequest>
	implements ChangeCapturePublisher<C> {

	@Getter @Nonnull private final UUID id = UUID.randomUUID();
	@Getter @Nonnull private final R request;
	@Nonnull private final Consumer<P> terminationCallback;
	@Nonnull protected final BufferedPublisher<C> delegate;

	private boolean active = true;

	protected AbstractChangeCapturePublisher(@Nonnull Executor executor, @Nonnull R request) {
		this(executor, request, it -> {});
	}

	protected AbstractChangeCapturePublisher(@Nonnull Executor executor,
	                                         @Nonnull R request,
	                                         @Nonnull Consumer<P> terminationCallback) {
		this.request = request;
		this.terminationCallback = terminationCallback;
		this.delegate = new BufferedPublisher<>(executor);
	}

	@Override
	public void subscribe(Subscriber<? super C> subscriber) {
		delegate.subscribe(subscriber);
	}

	@Override
	public void close() {
		if (active) {
			delegate.close();
			//noinspection unchecked
			terminationCallback.accept((P) this);
			active = false;
		}
	}
}
