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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.CatalogState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure record-invariant tests for {@link HostSystemEvent} and its sealed subtypes
 * {@link HostSystemEvent.CatalogInstalledIntoLiveView} and
 * {@link HostSystemEvent.CatalogRemovedFromLiveView}. The tests focus on the compact
 * constructors' validation rules — non-transient state precondition, mandatory catalog
 * name, observed-state non-nullness — and on the marker-interface assignability that lets
 * these events ride on the system CDC stream alongside engine mutations.
 *
 * No Evita instance is required; the tests use only the record's compact constructors.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HostSystemEvent")
@Tag(CONTRACT)
@Tag(CDC)
class HostSystemEventTest implements EvitaTestSupport {

	/**
	 * Catalog name reused across tests; intentionally simple so failures are easy to triage.
	 */
	private static final String CATALOG_NAME = "hostEventCatalog";

	@Nested
	@DisplayName("CatalogInstalledIntoLiveView construction")
	class CatalogInstalledConstruction {

		@ParameterizedTest(name = "should accept non-transient state {0}")
		@DisplayName("should construct successfully for every non-transient state")
		@EnumSource(value = CatalogState.class)
		void shouldConstructWithEachNonTransientState(@javax.annotation.Nonnull final CatalogState state) {
			// only non-transient states are valid input — transient ones are validated in a
			// separate test below; here we filter them out via JUnit assumption
			if (state.isTransitional()) {
				return;
			}

			final HostSystemEvent.CatalogInstalledIntoLiveView event =
				new HostSystemEvent.CatalogInstalledIntoLiveView(CATALOG_NAME, state, 42L);

			assertEquals(CATALOG_NAME, event.catalogName());
			assertEquals(state, event.observedState());
			assertEquals(42L, event.currentEngineVersion());
		}

		@ParameterizedTest(name = "should reject transient state {0}")
		@DisplayName("should reject every transient state with internal error")
		@EnumSource(value = CatalogState.class)
		void shouldRejectTransientStates(@javax.annotation.Nonnull final CatalogState state) {
			// the test method runs against the entire enum and only asserts on the transient subset
			if (!state.isTransitional()) {
				return;
			}

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> new HostSystemEvent.CatalogInstalledIntoLiveView(CATALOG_NAME, state, 0L)
			);
			assertTrue(
				error.getPrivateMessage().contains("non-transient state") ||
					error.getMessage().contains("non-transient state"),
				"Error message must explain the non-transient precondition; got: " + error.getMessage()
			);
		}

		@Test
		@DisplayName("should reject null observed state")
		void shouldRejectNullObservedState() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new HostSystemEvent.CatalogInstalledIntoLiveView(CATALOG_NAME, null, 0L)
			);
		}

		@Test
		@DisplayName("should reject null catalog name")
		void shouldRejectNullCatalogName() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new HostSystemEvent.CatalogInstalledIntoLiveView(null, CatalogState.ALIVE, 0L)
			);
		}

		@Test
		@DisplayName("should reject empty catalog name")
		void shouldRejectEmptyCatalogName() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new HostSystemEvent.CatalogInstalledIntoLiveView("", CatalogState.ALIVE, 0L)
			);
		}

		@Test
		@DisplayName("should expose all record components via accessors")
		void shouldExposeAllAccessors() {
			final HostSystemEvent.CatalogInstalledIntoLiveView event =
				new HostSystemEvent.CatalogInstalledIntoLiveView(CATALOG_NAME, CatalogState.ALIVE, 1234L);

			assertEquals(CATALOG_NAME, event.catalogName());
			assertEquals(CatalogState.ALIVE, event.observedState());
			assertEquals(1234L, event.currentEngineVersion());
		}
	}

	@Nested
	@DisplayName("CatalogRemovedFromLiveView construction")
	class CatalogRemovedConstruction {

		@Test
		@DisplayName("should construct with valid catalog name and version")
		void shouldConstructWithValidArguments() {
			final HostSystemEvent.CatalogRemovedFromLiveView event =
				new HostSystemEvent.CatalogRemovedFromLiveView(CATALOG_NAME, 7L);

			assertEquals(CATALOG_NAME, event.catalogName());
			assertEquals(7L, event.currentEngineVersion());
		}

		@Test
		@DisplayName("should reject null catalog name")
		void shouldRejectNullCatalogName() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new HostSystemEvent.CatalogRemovedFromLiveView(null, 0L)
			);
		}

		@Test
		@DisplayName("should reject empty catalog name")
		void shouldRejectEmptyCatalogName() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new HostSystemEvent.CatalogRemovedFromLiveView("", 0L)
			);
		}

		@Test
		@DisplayName("should expose all record components via accessors")
		void shouldExposeAllAccessors() {
			final HostSystemEvent.CatalogRemovedFromLiveView event =
				new HostSystemEvent.CatalogRemovedFromLiveView(CATALOG_NAME, 99L);

			assertEquals(CATALOG_NAME, event.catalogName());
			assertEquals(99L, event.currentEngineVersion());
		}
	}

	@Nested
	@DisplayName("Type hierarchy")
	class TypeHierarchy {

		@Test
		@DisplayName("CatalogInstalledIntoLiveView should be assignable to SystemCaptureBody")
		void shouldBeAssignableToSystemCaptureBodyInstalled() {
			final HostSystemEvent.CatalogInstalledIntoLiveView event =
				new HostSystemEvent.CatalogInstalledIntoLiveView(CATALOG_NAME, CatalogState.ALIVE, 0L);

			assertInstanceOf(SystemCaptureBody.class, event);
			assertInstanceOf(HostSystemEvent.class, event);
			assertNotNull(event);
		}

		@Test
		@DisplayName("CatalogRemovedFromLiveView should be assignable to SystemCaptureBody")
		void shouldBeAssignableToSystemCaptureBodyRemoved() {
			final HostSystemEvent.CatalogRemovedFromLiveView event =
				new HostSystemEvent.CatalogRemovedFromLiveView(CATALOG_NAME, 0L);

			assertInstanceOf(SystemCaptureBody.class, event);
			assertInstanceOf(HostSystemEvent.class, event);
			assertNotNull(event);
		}
	}
}
