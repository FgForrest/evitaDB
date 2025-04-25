/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureRequest;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
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
public class DelegatingChangeCapturePublisher
	<C extends ChangeCapture, R extends ChangeCaptureRequest>
	extends AbstractChangeCapturePublisher<DelegatingChangeCapturePublisher<C, R>, C, R> {

	public DelegatingChangeCapturePublisher(@Nonnull Executor executor, @Nonnull R request) {
		this(executor, request, it -> {});
	}

	public DelegatingChangeCapturePublisher(@Nonnull Executor executor,
	                                        @Nonnull R request,
	                                        @Nonnull Consumer<DelegatingChangeCapturePublisher<C, R>> terminationCallback) {
		super(executor, request, terminationCallback);
	}
	/**
	 * Tries to {@link SubmissionPublisher#offer(Object, long, TimeUnit, BiPredicate)} and returns immediately,
	 * even if subscriber is saturated.
	 */
	public int tryOffer(C item) {
		/* TODO LHO / JNO, update here */
		//return delegate.offer(item, 0, TimeUnit.MILLISECONDS, null);
		return 0;
	}
}
