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

package io.evitadb.api.requestResponse.cdc;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * The contract for the CDC subscribers that are interested in receiving the global evitaDB system events.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
// todo lho remove
public interface ChangeSystemCaptureSubscriber extends Subscriber<ChangeSystemCapture> {

	/**
	 * May return a filter for the {@link ChangeSystemCaptureRequest} that will be respected by the server and will
	 * reduce the amount of data sent to the subscriber.
	 *
	 * @return the request for the subscription, by default all events are sent only with header information (i.e.
	 * without details about particular operations).
	 */
	@Nonnull
	default ChangeSystemCaptureRequest initialSystemCaptureRequest() {
		// todo lho
		return new ChangeSystemCaptureRequest(/*UUID.randomUUID(), */CaptureContent.HEADER);
	}

	/**
	 * Called when the subscription is established. The subscriber should use the subscription to request items
	 * from the server by calling {@link Subscription#request(long)}. For infinite streams, the subscriber should
	 * call {@link Subscription#request(long)} with {@link Long#MAX_VALUE} as the argument.
	 *
	 * The subscription implements {@link NamedSubscription} interface.
	 *
	 * @param subscription the subscription
	 */
	@Override
	void onSubscribe(Subscription subscription);

	/**
	 * Called when a new item is available. The subscriber should process the item. If the item was the last one
	 * requested by {@link Subscription#request(long)}, the subscriber is automatically discarded on the server side
	 * and the {@link Subscription#request(long)} called within this last invocation will return an exception.
	 * The method {@link #onComplete()} is guaranteed to be called after the last requested item has been passed.
	 *
	 * If the subscriber is interested in receiving more items, it should needs to be registered again as a new
	 * subscriber.
	 *
	 * @param item the item
	 */
	@Override
	void onNext(ChangeSystemCapture item);

	/**
	 * Called when an error occurs in the subscription. The subscriber should clean up any resources used by the
	 * subscription on its end. The subscription is considered to be terminated after this method returns.
	 * @param throwable the exception
	 */
	@Override
	void onError(Throwable throwable);

	/**
	 * Called when the evitaDB is shut down. The subscriber should clean up any resources related to the subscription
	 * on its end.
	 */
	@Override
	void onComplete();

}
