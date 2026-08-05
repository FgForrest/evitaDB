/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.driver.cdc;

import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;

import javax.annotation.Nonnull;

/**
 * This interface can be implemented by client subscribing to the CDC events to receive notifications about incoming
 * HeartBeat events from the server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface HeartBeatSensor {

	/**
	 * Processes an incoming HeartBeat event to maintain connection state and handle related updates.
	 *
	 * **This callback is invoked asynchronously**, on the client thread pool rather than on the gRPC inbound
	 * thread that received the heartbeat. That is deliberate: implementations commonly react to a suspicious
	 * gap by re-establishing the subscription, and running that on the inbound thread would block the very
	 * thread that has to deliver the new subscription's acknowledgement, killing the connection. Two
	 * consequences follow:
	 *
	 * - Notifications for one subscription are delivered **in order**, one at a time, so
	 *   {@link HeartBeat#index()} continuity is a valid way to detect missed heartbeats. This holds even when
	 *   the client thread pool is saturated: delivery then falls back to a rescue thread, which is still the
	 *   single active drain for this subscription.
	 * - The acknowledgement heartbeat may still be in flight when `subscribe()` returns. Do not read state
	 *   written by this method immediately after subscribing and expect it to be populated.
	 * - Ordering holds **only among heartbeats**. This SPI is deliberately not part of the
	 *   `Flow.Subscriber` signal sequence, and heartbeats travel their own queue, so a delegate that
	 *   implements both interfaces may observe a heartbeat *after* `onComplete`/`onError` — one that was
	 *   already queued when the stream terminated. Implementations that react by re-establishing the
	 *   subscription must therefore check whether they still consider it live, otherwise a terminated
	 *   subscription can be resurrected. The queues are kept separate on purpose: a sensor that
	 *   re-subscribes blocks until the server acknowledges, and funnelling terminal notifications behind
	 *   it would delay every consumer's error handling to spare a rare reordering.
	 *
	 * Implementations should return promptly. Blocking here does not stall the transport, but it does hold
	 * a pool thread and delays subsequent heartbeat notifications for the same subscription.
	 *
	 * @param heartBeat the HeartBeat instance containing details about the server heartbeat event,
	 *                  including subscription ID, event index, timestamp, last observed version,
	 *                  and the time interval until the next heartbeat.
	 */
	void onHeartBeat(@Nonnull HeartBeat heartBeat);

}
