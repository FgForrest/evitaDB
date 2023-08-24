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

package io.evitadb.cdc;

import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.Flow.Subscription;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Subscription implementation to a CDC stream for both client and server side.
 * Internal use only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class NamedSubscription implements Subscription {

	/**
	 * Unique identifier of this subscription.
	 */
	private final UUID id = UUID.randomUUID();
	/**
	 * Consumer to be called to cancel the subscription.
	 */
	private final Consumer<UUID> canceller;
	/**
	 * Consumer to be called to request more data.
	 */
	private final BiConsumer<UUID, Long> requester;

	@Nonnull
	public UUID id() {
		return id;
	}

	@Override
	public void request(long n) {
		requester.accept(id, n);
	}

	@Override
	public void cancel() {
		canceller.accept(id);
	}
}
