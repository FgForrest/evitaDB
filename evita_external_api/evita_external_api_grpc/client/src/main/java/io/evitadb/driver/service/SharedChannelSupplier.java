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
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link ChannelSupplier} which uses a single shared {@link ManagedChannel} for all service calls.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SharedChannelSupplier implements ChannelSupplier {

	@Nonnull @Getter private final ManagedChannel channel;

	public SharedChannelSupplier(@Nonnull ManagedChannel channel) {
		this.channel = channel;
	}

	@Override
	public void releaseChannel() {
		// do nothing, its shared channel
	}
}
