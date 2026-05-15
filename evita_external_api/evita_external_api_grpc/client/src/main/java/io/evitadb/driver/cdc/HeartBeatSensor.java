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
	 * @param heartBeat the HeartBeat instance containing details about the server heartbeat event,
	 *                  including subscription ID, event index, timestamp, last observed version,
	 *                  and the time interval until the next heartbeat.
	 */
	void onHeartBeat(@Nonnull HeartBeat heartBeat);

}
