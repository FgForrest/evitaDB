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

package io.evitadb.driver.service;

import io.grpc.ManagedChannel;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Abstracts the logic of supplying an instance of a channel to a gRPC service.
 * It's not thread safe and a single instance should be used only during a single service call.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NotThreadSafe
public interface ChannelSupplier {

	/**
	 * Get a channel instance ready to use.
	 */
	ManagedChannel getChannel();

	/**
	 * Release the channel instance from {@link #getChannel()}
	 */
	void releaseChannel();
}
