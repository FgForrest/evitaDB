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

import io.evitadb.cdc.NamedSubscription;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The client mirror of the server side {@link NamedSubscription}. It keeps the assigned subscription id and serves
 * as proxy for sending {@link #request(long)} and {@link #cancel()} requests to the server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ClientSubscription implements NamedSubscription {
	private final UUID id;

	/**
	 * Consumer to be called to cancel the subscription.
	 */
	private final Consumer<UUID> canceller;
	/**
	 * Consumer to be called to request more data.
	 */
	private final BiConsumer<UUID, Long> requester;

	@Nonnull
	@Override
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
