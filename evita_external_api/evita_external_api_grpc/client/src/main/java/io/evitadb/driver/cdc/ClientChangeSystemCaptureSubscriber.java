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

package io.evitadb.driver.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureSubscriber;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Subscription;

/**
 * Abstract class implementing {@link ChangeSystemCaptureSubscriber} that is expected to be extended by the client
 * application logic. Client must implement {@link #subscribed(ClientSubscription)} method to request items from the
 * server and the {@link #onNext(ChangeSystemCapture)} to handle the incoming events.
 *
 * If there are any resources associated with the subscriber, they should be released in {@link #onError(Throwable)} and
 * {@link #onComplete()} methods.
 *
 * TODO TPO - write tests to cover the functionality of the class
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public abstract class ClientChangeSystemCaptureSubscriber implements ChangeSystemCaptureSubscriber {

	@Override
	public final void onSubscribe(Subscription subscription) {
		subscribed((ClientSubscription) subscription);
	}

	@Override
	public final void onError(Throwable throwable) {
		/* TODO TPO - clean client information about the subscription */
		errorOccurred(throwable);
	}

	@Override
	public final void onComplete() {
		/* TODO TPO - clean client information about the subscription */
		completed();
	}

	/**
	 * The implementation must react to a subscription by requesting items from the server by calling
	 * {@link ClientSubscription#request(long)} with desired amount of items.
	 *
	 * @param subscription the initiated subscription
	 */
	protected abstract void subscribed(@Nonnull ClientSubscription subscription);

	/**
	 * Method might to be overridden by the implementation to react on subscription completion with error.
	 */
	protected final void errorOccurred(@Nonnull Throwable throwable) {
		// do nothing by default
	}

	/**
	 * Method might to be overridden by the implementation to react on subscription completion.
	 */
	protected final void completed() {
		// do nothing by default
	}

}
