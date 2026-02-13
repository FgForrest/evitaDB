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

import io.evitadb.api.exception.UnsatisfiedDependencyException;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link UnsatisfiedDependencyFactory} - the fallback factory used when
 * Proxycian library is not on the classpath.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("UnsatisfiedDependencyFactory")
class UnsatisfiedDependencyFactoryTest {

	@Test
	@DisplayName("should provide singleton instance")
	void shouldProvideSingletonInstance() {
		final UnsatisfiedDependencyFactory instance = UnsatisfiedDependencyFactory.INSTANCE;
		assertNotNull(instance);
		assertSame(UnsatisfiedDependencyFactory.INSTANCE, instance);
	}

	@Test
	@DisplayName("should throw UnsatisfiedDependencyException on createEntityProxy")
	void shouldThrowUnsatisfiedDependencyExceptionOnCreateEntityProxy() {
		final EntityContract entity = mock(EntityContract.class);
		final Map<String, EntitySchemaContract> schemas = Map.of();

		assertThrows(
			UnsatisfiedDependencyException.class,
			() -> UnsatisfiedDependencyFactory.INSTANCE.createEntityProxy(
				Object.class, entity, schemas
			)
		);
	}

	@Test
	@DisplayName("should include Proxycian mention in exception message")
	void shouldIncludeProxycianMentionInExceptionMessage() {
		final EntityContract entity = mock(EntityContract.class);
		final Map<String, EntitySchemaContract> schemas = Map.of();

		final UnsatisfiedDependencyException exception = assertThrows(
			UnsatisfiedDependencyException.class,
			() -> UnsatisfiedDependencyFactory.INSTANCE.createEntityProxy(
				Object.class, entity, schemas
			)
		);
		assertTrue(
			exception.getPrivateMessage().contains("Proxycian"),
			"Exception message should mention Proxycian"
		);
	}

	@Test
	@DisplayName("should include ByteBuddy mention in exception message")
	void shouldIncludeByteBuddyMentionInExceptionMessage() {
		final EntityContract entity = mock(EntityContract.class);
		final Map<String, EntitySchemaContract> schemas = Map.of();

		final UnsatisfiedDependencyException exception = assertThrows(
			UnsatisfiedDependencyException.class,
			() -> UnsatisfiedDependencyFactory.INSTANCE.createEntityProxy(
				Object.class, entity, schemas
			)
		);
		assertTrue(
			exception.getPrivateMessage().contains("ByteBuddy"),
			"Exception message should mention ByteBuddy"
		);
	}

	@Test
	@DisplayName("should throw fresh exception on each call with accurate stack trace")
	void shouldThrowFreshExceptionOnEachCall() {
		final EntityContract entity = mock(EntityContract.class);
		final Map<String, EntitySchemaContract> schemas = Map.of();

		final UnsatisfiedDependencyException first = assertThrows(
			UnsatisfiedDependencyException.class,
			() -> UnsatisfiedDependencyFactory.INSTANCE.createEntityProxy(
				Object.class, entity, schemas
			)
		);
		final UnsatisfiedDependencyException second = assertThrows(
			UnsatisfiedDependencyException.class,
			() -> UnsatisfiedDependencyFactory.INSTANCE.createEntityProxy(
				String.class, entity, schemas
			)
		);
		// Each call should produce a new exception with fresh stack trace
		assertNotSame(first, second, "Should create fresh exception for each call");
		assertEquals(first.getPrivateMessage(), second.getPrivateMessage());
	}
}
