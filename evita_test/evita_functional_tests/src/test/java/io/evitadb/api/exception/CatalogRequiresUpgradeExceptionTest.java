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

package io.evitadb.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire-level payload of `CatalogRequiresUpgradeException` — `Evita#loadCatalogInternal`
 * reads `fromProtocolVersion` / `toProtocolVersion` to build a matching `UpgradeCatalogFormatMutation`,
 * and the message is surfaced verbatim by `JsonApiExceptionHandler`. Other constructor-set fields are
 * verified implicitly (Lombok plumbing is not retested).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CatalogRequiresUpgradeException — catalog-name + protocol-version payload")
class CatalogRequiresUpgradeExceptionTest {

	private static final String CATALOG = "testCatalog";

	@Test
	@DisplayName("single-arg ctor should default both protocol versions to -1 (unknown)")
	void shouldDefaultProtocolVersionsToMinusOneWhenSingleArgCtorUsed() {
		// when — single-arg ctor for reporting paths that do not inspect the on-disk header
		final CatalogRequiresUpgradeException exception = new CatalogRequiresUpgradeException(CATALOG);

		// then — the auto-upgrade retry hook keys off these sentinels to detect "unknown" version pairs
		assertEquals(CATALOG, exception.getCatalogName());
		assertEquals(-1, exception.getFromProtocolVersion());
		assertEquals(-1, exception.getToProtocolVersion());
	}

	@Test
	@DisplayName("three-arg ctor should preserve catalog name, both protocol versions, and embed them in the message")
	void shouldPreserveAllFieldsWhenThreeArgCtorUsed() {
		// when — three-arg ctor used by verifyAndUpgradeStorageFormat (the authoritative thrower)
		final CatalogRequiresUpgradeException exception = new CatalogRequiresUpgradeException(CATALOG, 4, 5);

		// then — protocol versions round-trip and the message embeds them so operators can diagnose
		// upgrades from logs alone (the message is also surfaced verbatim by JsonApiExceptionHandler).
		assertEquals(CATALOG, exception.getCatalogName());
		assertEquals(4, exception.getFromProtocolVersion());
		assertEquals(5, exception.getToProtocolVersion());
		final String message = exception.getMessage();
		assertTrue(message.contains(CATALOG), "Message must mention the catalog name; was: " + message);
		assertTrue(message.contains("v4"), "Message must mention the from-protocol version; was: " + message);
		assertTrue(message.contains("v5"), "Message must mention the to-protocol version; was: " + message);
	}
}
