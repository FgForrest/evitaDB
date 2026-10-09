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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import net.javacrumbs.jsonunit.assertj.JsonAssert;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

/**
 * Tests support for testing External API WebSocket API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ExternalApiWebSocketFunctionTestsSupport {

	Random RND = new Random();

	default long getStartVersionForCatalogCDC(@Nonnull Evita evita, @Nonnull String catalogName) {
		return evita.queryCatalog(
			catalogName,
			EvitaSessionContract::getCatalogVersion
		) + 1;
	}

	default long getStartVersionForEvitaCDC(@Nonnull Evita evita, @Nonnull String catalogName) {
		return evita.queryCatalog(
			catalogName,
			EvitaSessionContract::getCatalogVersion
		) + 1;
	}

	/**
	 * How long a test listens for further events after a CDC subscription has been refused, to prove that nothing
	 * follows the error. The refusal happens while the subscription is being registered, so no capture can follow it
	 * by construction - the window only has to be long enough for a capture of an accepted subscription to arrive.
	 */
	Duration REFUSED_SUBSCRIPTION_QUIET_WINDOW = Duration.ofSeconds(2);

	/**
	 * How many versions past the start version a test subscribes from to provoke the refusal of a resume position
	 * that lies ahead of the catalog. A refusal test commits a probe change once the connection is acknowledged, which
	 * may happen before the server has registered the subscription. One version past the start version would then be
	 * exactly the next version of the catalog, which is accepted, and the test would wait in vain for the refusal. The
	 * margin keeps the position ahead whatever the test commits meanwhile.
	 */
	long AHEAD_OF_CATALOG_MARGIN = 100L;

	/**
	 * Returns the identity of the current incarnation of the catalog - the `catalogId` a CDC consumer stores with its
	 * resume position.
	 */
	@Nonnull
	default UUID getCatalogIdForCatalogCDC(@Nonnull Evita evita, @Nonnull String catalogName) {
		return evita.queryCatalog(
			catalogName,
			EvitaSessionContract::getCatalogId
		);
	}

	/**
	 * Asserts that the event is a terminal `error` event of the subscription.
	 */
	@Nonnull
	default JsonAssert assertErrorEvent(@Nonnull String receivedEvent, @Nonnull String expectedSubscriptionId) {
		return assertThatJson(receivedEvent)
			.and(
				it -> it.node("type").isEqualTo("error"),
				it -> it.node("id").isEqualTo("\"" + expectedSubscriptionId + "\"")
			);
	}

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
}
