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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.proxy.SealedEntityProxy.Propagation;
import io.evitadb.api.proxy.impl.AbstractEntityProxyState.ProxyWithUpsertCallback;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxyWithUpsertCallback} which holds proxy instances and manages
 * upsert callbacks for entity mutation persistence.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProxyWithUpsertCallback")
class ProxyWithUpsertCallbackTest {

	@Nested
	@DisplayName("proxy() lookup")
	class ProxyLookup {

		@Test
		@DisplayName("should return existing proxy when type matches")
		void shouldReturnExistingProxyWhenTypeMatches() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);

			final String result = holder.proxy(
				String.class, () -> new ProxyWithUpsertCallback("fallback")
			);
			assertSame(proxy, result);
		}

		@Test
		@DisplayName("should return existing proxy via supertype")
		void shouldReturnExistingProxyViaSupertype() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);

			final CharSequence result = holder.proxy(
				CharSequence.class,
				() -> new ProxyWithUpsertCallback("fallback")
			);
			assertSame(proxy, result);
		}

		@Test
		@DisplayName("should create new proxy when type does not match")
		void shouldCreateNewProxyWhenTypeDoesNotMatch() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);

			final Integer result = holder.proxy(
				Integer.class,
				() -> new ProxyWithUpsertCallback(42)
			);
			assertEquals(42, result);
		}

		@Test
		@DisplayName("should cache newly created proxy")
		void shouldCacheNewlyCreatedProxy() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);

			// First call creates the proxy
			final Integer first = holder.proxy(
				Integer.class,
				() -> new ProxyWithUpsertCallback(42)
			);
			// Second call should return same instance
			final Integer second = holder.proxy(
				Integer.class,
				() -> new ProxyWithUpsertCallback(99)
			);
			assertSame(first, second);
		}
	}

	@Nested
	@DisplayName("proxyIfPossible() lookup")
	class ProxyIfPossibleLookup {

		@Test
		@DisplayName("should return Optional with proxy when type matches")
		void shouldReturnOptionalWithProxyWhenTypeMatches() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);

			final Optional<String> result =
				holder.proxyIfPossible(String.class);
			assertTrue(result.isPresent());
			assertSame(proxy, result.get());
		}

		@Test
		@DisplayName("should return empty Optional when type does not match")
		void shouldReturnEmptyOptionalWhenTypeDoesNotMatch() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);

			final Optional<Integer> result =
				holder.proxyIfPossible(Integer.class);
			assertTrue(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("proxies() collection")
	class ProxiesCollection {

		@Test
		@DisplayName("SHALLOW should return only first proxy")
		void shallowShouldReturnOnlyFirstProxy() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);
			// Add another proxy type
			holder.proxy(
				Integer.class, () -> new ProxyWithUpsertCallback(42)
			);

			final Collection<Object> result =
				holder.proxies(Propagation.SHALLOW);
			assertEquals(1, result.size());
			assertSame(proxy, result.iterator().next());
		}

		@Test
		@DisplayName("DEEP should return all proxies")
		void deepShouldReturnAllProxies() {
			final String proxy = "hello";
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback(proxy);
			// Add another proxy type
			holder.proxy(
				Integer.class, () -> new ProxyWithUpsertCallback(42)
			);

			final Collection<Object> result =
				holder.proxies(Propagation.DEEP);
			assertEquals(2, result.size());
		}
	}

	@Nested
	@DisplayName("Callback handling")
	class CallbackHandling {

		@Test
		@DisplayName("should have non-null default callback")
		void shouldHaveNonNullDefaultCallback() {
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("proxy");
			assertNotNull(holder.callback());
		}

		@Test
		@DisplayName("should store custom callback")
		void shouldStoreCustomCallback() {
			final AtomicReference<EntityReferenceContract> captured =
				new AtomicReference<>();
			final Consumer<EntityReferenceContract> callback = captured::set;

			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("proxy", callback);
			assertSame(callback, holder.callback());
		}

		@Test
		@DisplayName(
			"should merge callback when adding proxy with callback"
		)
		void shouldMergeCallbackWhenAddingProxyWithCallback() {
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("proxy");

			// Add a proxy with a callback
			final AtomicReference<EntityReferenceContract> captured =
				new AtomicReference<>();
			holder.proxy(
				Integer.class,
				() -> new ProxyWithUpsertCallback(42, captured::set)
			);

			// The callback should have been merged
			assertNotNull(holder.callback());
		}

		@Test
		@DisplayName("should throw when merging two non-default callbacks")
		void shouldThrowWhenMergingTwoNonDefaultCallbacks() {
			final Consumer<EntityReferenceContract> callback1 = ref -> {};
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("proxy", callback1);

			final Consumer<EntityReferenceContract> callback2 = ref -> {};
			assertThrows(
				Exception.class,
				() -> holder.proxy(
					Integer.class,
					() -> new ProxyWithUpsertCallback(42, callback2)
				)
			);
		}
	}

	@Nested
	@DisplayName("getSealedEntityProxies()")
	class SealedEntityProxiesStream {

		@Test
		@DisplayName("should return empty stream when no SealedEntityProxy")
		void shouldReturnEmptyStreamWhenNoSealedEntityProxy() {
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("not a proxy");
			assertEquals(
				0, holder.getSealedEntityProxies().count()
			);
		}
	}

	@Nested
	@DisplayName("getSealedEntityReferenceProxies()")
	class SealedEntityReferenceProxiesStream {

		@Test
		@DisplayName(
			"should return empty stream when no reference proxy"
		)
		void shouldReturnEmptyStreamWhenNoReferenceProxy() {
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("not a proxy");
			assertEquals(
				0,
				holder.getSealedEntityReferenceProxies(Propagation.DEEP)
					.count()
			);
		}

		@Test
		@DisplayName(
			"SHALLOW should return empty for non-reference proxies"
		)
		void shallowShouldReturnEmptyForNonReferenceProxies() {
			final ProxyWithUpsertCallback holder =
				new ProxyWithUpsertCallback("not a proxy");
			assertEquals(
				0,
				holder.getSealedEntityReferenceProxies(Propagation.SHALLOW)
					.count()
			);
		}
	}
}
