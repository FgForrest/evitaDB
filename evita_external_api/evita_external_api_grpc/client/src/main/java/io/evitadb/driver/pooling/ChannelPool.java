/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.driver.pooling;

import io.evitadb.driver.EvitaClient;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * This class serves as a concurrent channel pooling solution for {@link EvitaClient}. Each time there is a need for a
 * new channel from the {@link EvitaClient}, the pool will return an existing one if available. Otherwise, a new channel
 * will be created and returned afterwards.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ChannelPool {
	/**
	 * The pool is implemented as a queue of channels. The queue is thread-safe and can be accessed by multiple threads
	 */
	private final ConcurrentLinkedQueue<ManagedChannel> channels;
	/**
	 * The channel builder is used to create new channels while initialization or if the pool is empty.
	 */
	private final NettyChannelBuilder channelBuilder;

	/**
	 * Creates a new channel pool with the specified size.
	 *
	 * @param channelBuilder the channel builder used to create new channels
	 * @param poolSize       the size of the pool
	 */
	public ChannelPool(NettyChannelBuilder channelBuilder, int poolSize) {
		channels = new ConcurrentLinkedQueue<>();
		this.channelBuilder = channelBuilder;
		for (int i = 0; i < poolSize; i++) {
			ManagedChannel channel = channelBuilder.build();
			channels.add(channel);
		}
	}

	/**
	 * Returns a channel from the pool. If the pool is empty, a new channel is created and returned.
	 *
	 * @return a channel either from the pool if available or a new one
	 */
	public ManagedChannel getChannel() {
		ManagedChannel channel = channels.poll();
		if (channel == null || channel.isShutdown() || channel.isTerminated()) {
			// No available channel in the pool, create a new one
			channel = this.channelBuilder.build();
		}
		return channel;
	}

	/**
	 * Releases the channel back to the pool. If the channel is already shutdown or terminated, it is not added to the
	 * pool.
	 *
	 * @param channel the channel to be released
	 */
	public void releaseChannel(ManagedChannel channel) {
		if (channel.isShutdown() || channel.isTerminated()) {
			return;
		}
		channels.offer(channel);
	}

	/**
	 * Shuts down all channels in the pool.
	 */
	public void shutdown() {
		for (ManagedChannel channel : channels) {
			channel.shutdownNow();
		}
	}

	/**
	 * Terminate all channels in the pool and after elapsed time units ensure that all channels has been closed.
	 */
	public boolean awaitTermination(long timeout, @Nonnull TimeUnit timeoutUnit) throws InterruptedException {
		for (ManagedChannel channel : channels) {
			channel.awaitTermination(timeout, timeoutUnit);
		}
		return channels.stream().allMatch(ManagedChannel::isTerminated);
	}
}