/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi;

import net.javacrumbs.jsonunit.assertj.JsonAssert;

import javax.annotation.Nonnull;

import java.util.Random;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

/**
 * Tests support for testing External API WebSocket API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ExternalApiWebSocketFunctionTestsSupport {

	Random RND = new Random();

	@Nonnull
	default JsonAssert assertNextEvent(@Nonnull String receivedEvent, @Nonnull String expectedSubscriptionId) {
		return assertThatJson(receivedEvent)
			.and(
				it -> it.node("type").isEqualTo("next"),
				it -> it.node("id").isEqualTo("\"" + expectedSubscriptionId + "\"")
			);
	}

	default void assertConnectionAckEvent(@Nonnull String receivedEvent) {
		assertThatJson(receivedEvent)
			.node("type").isEqualTo("connection_ack");
	}

	@Nonnull
	default String createPingMessage() {
		return "{\"type\":\"ping\"}";
	}

	@Nonnull
	default String createConnectionInitMessage() {
		return "{\"type\":\"connection_init\"}";
	}

	@Nonnull
	default String createSubscriptionId() {
		return String.valueOf(RND.nextInt(Integer.MAX_VALUE));
	}

	default void wait(int milliseconds) {
		try {
			Thread.sleep(milliseconds);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
}
