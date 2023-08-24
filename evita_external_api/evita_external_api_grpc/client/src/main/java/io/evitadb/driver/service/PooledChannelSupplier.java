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

import io.evitadb.driver.pooling.ChannelPool;
import io.evitadb.utils.Assert;
import io.grpc.ManagedChannel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ChannelSupplier} which uses shared {@link ChannelPool} for supplied channels.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class PooledChannelSupplier implements ChannelSupplier {

	@Nonnull private final ChannelPool channelPool;
	/**
	 * Leased channel instance from pool.
	 */
	@Nullable private ManagedChannel channel;

	public PooledChannelSupplier(@Nonnull ChannelPool channelPool) {
		this.channelPool = channelPool;
	}

	@Override
	public ManagedChannel getChannel() {
		if (channel == null) {
			channel = this.channelPool.getChannel();
		}
		return channel;
	}

	@Override
	public void releaseChannel() {
		Assert.isPremiseValid(
			channel != null,
			"Channel instance is not leased from pool. Do you call the `releaseChannel()` correctly?"
		);
		channelPool.releaseChannel(channel);
	}
}
