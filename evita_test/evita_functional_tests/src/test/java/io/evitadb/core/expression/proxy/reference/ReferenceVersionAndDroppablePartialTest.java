/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy.reference;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.ReferenceProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ReferenceVersionAndDroppablePartial} verifying that version and dropped method implementations
 * correctly delegate to the proxy state.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Reference version and droppable partial")
class ReferenceVersionAndDroppablePartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface VersionTestReference extends ReferenceContract {}

	/**
	 * Creates a ReferenceContract proxy with the given partials plus the CatchAllPartial safety net.
	 *
	 * @param state    the proxy state record
	 * @param partials specific partials to include
	 * @return reference contract proxy
	 */
	@Nonnull
	private static ReferenceContract createReferenceProxy(
		@Nonnull ReferenceProxyState state,
		@Nonnull PredicateMethodClassification<?, ?, ?>... partials
	) {
		final PredicateMethodClassification[] all = new PredicateMethodClassification[partials.length + 2];
		System.arraycopy(partials, 0, all, 0, partials.length);
		all[partials.length] = CatchAllPartial.OBJECT_METHODS;
		all[partials.length + 1] = CatchAllPartial.INSTANCE;

		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state, all),
			new Class<?>[]{ VersionTestReference.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	@Test
	@DisplayName("version() returns value from reference state")
	void shouldReturnVersionFromReferenceState() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 7, null, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertEquals(7, proxy.version(), "version() should return 7 from the proxy state");
	}

	@Test
	@DisplayName("dropped() returns false")
	void shouldReturnFalseForDropped() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, null, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertFalse(proxy.dropped(), "dropped() should always return false for expression proxies");
	}
}
