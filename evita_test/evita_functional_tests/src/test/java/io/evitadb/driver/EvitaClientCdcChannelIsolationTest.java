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

package io.evitadb.driver;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.common.util.EventLoopGroups;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the Armeria behaviour that the dedicated change data capture channel is built on.
 *
 * {@link EvitaClient} builds its main {@link ClientFactory}, then re-points the **same**
 * {@link ClientFactoryBuilder} at a dedicated single-thread event loop group and builds a second factory from
 * it. The whole isolation — capture traffic on its own connection and its own I/O thread, so a stalled capture
 * callback cannot stall ordinary request/response calls (issue #1387) — rests on `build()` taking an immutable
 * snapshot of the builder's options, so that the second `workerGroup(...)` call cannot retroactively re-point
 * the factory built first.
 *
 * That is Armeria's documented builder contract and it holds in 1.40.0
 * (`ClientFactoryBuilder#build` → `buildOptions()` → `ClientFactoryOptions.of(...)`, whose result
 * `HttpClientFactory` reads into `final` fields at construction). It is asserted here rather than merely
 * reasoned about because **the failure would be silent**: both factories would share one event loop, every
 * functional test would still pass — request correctness is unaffected, only *which thread* serves them — and
 * the regression would resurface in production as issue #1387 arriving through a different door.
 *
 * This test therefore also serves as an upgrade guard: if a future Armeria version makes `build()` share
 * mutable state with its builder, this fails immediately instead of at a customer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EvitaClient CDC channel isolation")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
@Tag(STREAM)
class EvitaClientCdcChannelIsolationTest {

	@Test
	@DisplayName("Re-pointing the builder's worker group does not disturb an already-built client factory")
	void shouldNotRepointAnAlreadyBuiltClientFactory() {
		final EventLoopGroup mainGroup = EventLoopGroups.newEventLoopGroup(1, "test-main-eventloop");
		final EventLoopGroup cdcGroup = EventLoopGroups.newEventLoopGroup(1, "test-cdc-eventloop");
		final ClientFactoryBuilder builder = ClientFactory.builder().workerGroup(mainGroup, false);

		// mirrors EvitaClient's constructor exactly: build, re-point the same builder, build again
		try (
			ClientFactory mainFactory = builder.build();
			ClientFactory cdcFactory = builder.workerGroup(cdcGroup, false).build()
		) {
			assertSame(
				mainGroup, mainFactory.eventLoopGroup(),
				"the factory built first must keep the worker group it was built with - if it does not, CDC " +
					"traffic and ordinary request/response traffic share one event loop and issue #1387 is back"
			);
			assertSame(
				cdcGroup, cdcFactory.eventLoopGroup(),
				"the factory built second must use the re-pointed worker group"
			);
			assertNotSame(
				mainFactory.eventLoopGroup(), cdcFactory.eventLoopGroup(),
				"the two factories must not share an event loop group - that separation is the isolation"
			);
		} finally {
			mainGroup.shutdownGracefully();
			cdcGroup.shutdownGracefully();
		}
	}

}
