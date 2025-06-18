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

package io.evitadb.externalApi.grpc.services;


import com.linecorp.armeria.internal.shaded.guava.util.concurrent.MoreExecutors;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A StreamObserver wrapper that properly handles gRPC context cancellation
 * by monitoring the current context and triggering cleanup when cancelled.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
@Slf4j
public class CancellableStreamObserver<T> implements StreamObserver<T> {
	private final StreamObserver<T> delegate;
	private final Context context;
	private Runnable cancellationHandler;
	private volatile boolean cancelled = false;

	public static <T> CancellableStreamObserver<T> wrap(StreamObserver<T> delegate) {
		return new CancellableStreamObserver<>(delegate, Context.current());
	}

	public void setCancellationHandler(Runnable cancellationHandler) {
		this.cancellationHandler = cancellationHandler;

		// Set up context cancellation listener
		this.context.addListener(context -> handleCancellation(), MoreExecutors.directExecutor());

		// Check if already cancelled
		if (this.context.isCancelled()) {
			handleCancellation();
		}
	}

	@Override
	public void onNext(T value) {
		if (!this.cancelled && !this.context.isCancelled()) {
			this.delegate.onNext(value);
		}
	}

	@Override
	public void onError(Throwable t) {
		if (!this.cancelled) {
			this.delegate.onError(t);
		}
	}

	@Override
	public void onCompleted() {
		if (!this.cancelled) {
			this.delegate.onCompleted();
		}
	}

	private void handleCancellation() {
		if (!this.cancelled) {
			this.cancelled = true;
			if (this.cancellationHandler != null) {
				try {
					this.cancellationHandler.run();
				} catch (Exception e) {
					log.warn("Error during cancellation handling", e);
				}
			}
		}
	}
}