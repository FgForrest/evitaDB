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

package io.evitadb.core.session;

import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.CatalogVersionPin;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogConsumerControl;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies that a catalog version held against reclamation is given back to **the catalog instance that granted it**,
 * even when the name it was granted under has meanwhile been handed to a different instance by a rename or a replace.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog consumer control")
@Tag(ENGINE)
@Tag(SESSION)
class CatalogConsumerControlTest {
	private static final String TEST_CATALOG = "testCatalog";
	private static final long PINNED_VERSION = 40L;

	@Test
	@DisplayName("should release the pin on the catalog that granted it, not on the one that replaced it")
	void shouldReleaseThePinOnTheCatalogThatGrantedIt() {
		final Catalog pinnedCatalog = Mockito.mock(Catalog.class);
		final Catalog replacementCatalog = Mockito.mock(Catalog.class);
		// the supplier every consumer resolves the catalog through - a rename or a replace swaps what it answers
		final AtomicReference<Catalog> currentCatalog = new AtomicReference<>(pinnedCatalog);

		final SessionRegistry sessionRegistry = new SessionRegistry(
			Mockito.mock(TracingContext.class),
			currentCatalog::get,
			SessionRegistry.createDataStore()
		);
		final CatalogConsumerControl consumerControl = sessionRegistry.createCatalogConsumerControl(TEST_CATALOG);

		final CatalogVersionPin versionPin = consumerControl.pinCatalogVersion(PINNED_VERSION);
		verify(pinnedCatalog).catalogVersionPinned(PINNED_VERSION);

		// the catalog is replaced while the consumer - a backup copying that version - is still running
		currentCatalog.set(replacementCatalog);

		versionPin.close();

		// resolving the catalog by name a second time would reach the replacement, and decrementing *its* counter
		// takes protection away from whichever session or backup legitimately pinned that version on the new instance
		// - while the version this consumer really held would stay pinned on an instance nothing ever reconciles
		verify(pinnedCatalog).catalogVersionReleased(PINNED_VERSION);
		verify(replacementCatalog, never()).catalogVersionReleased(PINNED_VERSION);
	}

	@Test
	@DisplayName("should release a pin exactly once however many times the lease is closed")
	void shouldReleaseThePinExactlyOnce() {
		final Catalog pinnedCatalog = Mockito.mock(Catalog.class);
		final SessionRegistry sessionRegistry = new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> pinnedCatalog,
			SessionRegistry.createDataStore()
		);

		final CatalogVersionPin versionPin = sessionRegistry
			.createCatalogConsumerControl(TEST_CATALOG)
			.pinCatalogVersion(PINNED_VERSION);

		// a backup's constructor unwind and its tear-down both reach the release, and a second decrement does not
		// merely no-op - it takes away the protection of whichever consumer still holds that version
		versionPin.close();
		versionPin.close();

		verify(pinnedCatalog, times(1)).catalogVersionReleased(PINNED_VERSION);
	}

	@Test
	@DisplayName("should hold nothing when no pin could be taken")
	void shouldHoldNothingWhenNoPinWasTaken() {
		// a session registering against a catalog that is gone or in transition gets a lease over nothing. That is
		// what lets the release path stay unconditional: closing it must neither throw nor give back a pin that was
		// never taken, which is why the omission needs no separate record anywhere
		assertTrue(CatalogVersionPin.NONE.getCatalogVersion().isEmpty());

		assertDoesNotThrow(CatalogVersionPin.NONE::close);
		assertDoesNotThrow(CatalogVersionPin.NONE::close);

		// and it stays reusable afterwards - it is a shared constant, so closing it must not consume it for the next
		// consumer that fails to pin
		assertFalse(CatalogVersionPin.NONE.isReleased());
	}

	@Test
	@DisplayName("should refuse to pin at all when the catalog is gone")
	void shouldRefuseToPinWhenTheCatalogIsGone() {
		final SessionRegistry sessionRegistry = new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> null,
			SessionRegistry.createDataStore()
		);
		final CatalogConsumerControl consumerControl = sessionRegistry.createCatalogConsumerControl(TEST_CATALOG);

		// deliberately intolerant, unlike session registration: this pin is the whole of a backup's protection against
		// having the history it is copying reclaimed underneath it, so handing back a lease over nothing would remove
		// the guarantee silently and let the backup run to completion over files that are free to be deleted
		assertThrows(
			GenericEvitaInternalError.class,
			() -> consumerControl.pinCatalogVersion(PINNED_VERSION)
		);
	}
}
