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
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.SubmissionPublisher;

/**
 * Pre-configured {@link SubmissionPublisher} for {@link ChangeCapture} objects.
 * It is limited only to one subscriber to have control over internal buffer overflows as the {@link SubmissionPublisher}
 * doesn't provide tools to control buffers for individual subscribers and thus submit new events only to such subscribers
 * that have space in their buffers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class BufferedPublisher<C extends ChangeCapture> extends SubmissionPublisher<C> {

	/**
	 * Flag to validate that this publisher can serve only one subscriber.
	 */
	private boolean hasSubscriber;

	public BufferedPublisher(@Nonnull Executor executor) {
		// for now, we will use default buffer size as we don't have any information about what number to use otherwise
		super(executor, Flow.defaultBufferSize());
	}

	@Override
	public void subscribe(Subscriber<? super C> subscriber) {
		if (hasSubscriber) {
			throw new EvitaInvalidUsageException("Only one subscriber is supported.");
		}
		super.subscribe(subscriber);
		hasSubscriber = true;
	}
}
