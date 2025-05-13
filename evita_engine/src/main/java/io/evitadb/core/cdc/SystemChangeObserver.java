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

import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.Mutation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Publisher;

/**
 * Main implementation class handling notification of all created {@link ChangeCapturePublisher}s.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
@Slf4j
public class SystemChangeObserver implements AutoCloseable {

	/**
	 * Whether this observer is still active and can fire new events.
	 */
	private boolean active = true;

	/**
	 * Registers a new subscriber with a first request.
	 * @param request {@link ChangeSystemCaptureRequest} to create {@link java.util.concurrent.Flow.Publisher} for
	 * @see EvitaContract#registerSystemChangeCapture(ChangeSystemCaptureRequest) (Subscriber)
	 * @return new {@link Publisher} publishing {@link ChangeSystemCapture} events that match the request
	 */
	public ChangeCapturePublisher<ChangeSystemCapture> registerPublisher(@Nonnull ChangeSystemCaptureRequest request) {
		assertActive();
		// TODO JNO - implementovat
		return null;
	}

	/**
	 * Notifies all registered {@link DelegatingChangeCapturePublisher}s about a new {@link ChangeSystemCapture} event.
	 *
	 * @param catalog name of the catalog the event belongs to
	 * @param mutation {@link Mutation} that was executed
	 */
	public void notifyPublishers(@Nonnull String catalog, @Nonnull Mutation mutation) {
		assertActive();
		// TODO JNO - implementovat
	}

	@Override
	public void close() {
		if (this.active) {
			this.active = false;
			// TODO JNO - implementovat
		}
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertActive() {
		if (!this.active) {
			throw new InstanceTerminatedException("system change observer");
		}
	}
}
