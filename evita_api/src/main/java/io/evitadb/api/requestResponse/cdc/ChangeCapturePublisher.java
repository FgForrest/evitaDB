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

package io.evitadb.api.requestResponse.cdc;

import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

/**
 * {@link Publisher} for {@link ChangeCapture} instances. Publisher should support multiple concurrent subscribers. The
 * captures should be multicasted to all subscribers that are able to receive more captures.
 *
 * <h3>Published captures</h3>
 * The publisher is not expected to have any buffer of captures, therefore, if a client doesn't request more captures right
 * away in the {@link Subscriber#onNext(Object)} callback, it may not receive all captures in a consumed sequence of captures
 * (there may be holes).
 *
 * <h3>Subscriber lifecycle</h3>
 * Subscribers may stop requesting captures at any time, and start requesting them again later without publisher closing
 * the subscriber. The publisher may even not have any subscribers at all, but it must still remain open for any future
 * subscribers to come. The publisher must be manually closed by the {@link #close()} method to stop accepting subscribers
 * and sending new captures.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ChangeCapturePublisher<C extends ChangeCapture>
	extends Publisher<C>, AutoCloseable {

	/**
	 * When the publisher is closed, no new subscribers can be subscribed and no new captures can be sent to the subscribers.
	 */
	@Override
	void close();

}
