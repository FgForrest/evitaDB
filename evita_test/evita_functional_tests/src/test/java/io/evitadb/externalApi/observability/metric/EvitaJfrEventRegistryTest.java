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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.core.metric.event.transaction.TransactionConflictEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link EvitaJfrEventRegistry} knows about the transaction conflict metric event so
 * that it can be resolved and recorded through the JFR pipeline. The registry is the single source
 * of truth used by the recording path to translate a fully-qualified event class name back into the
 * concrete {@link io.evitadb.core.metric.event.CustomMetricsExecutionEvent} implementation; a
 * newly introduced event that is not present here is silently invisible to JFR recording.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EvitaJfrEventRegistry transaction conflict event registration")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class EvitaJfrEventRegistryTest {

	@Test
	@DisplayName("Registry resolves the transaction conflict event by its fully-qualified name for JFR recording")
	void shouldRegisterTransactionConflictEventForJfrRecording() {
		// the recording path looks the event up by its fully-qualified class name
		assertSame(
			TransactionConflictEvent.class,
			EvitaJfrEventRegistry.getEventClass(
				"io.evitadb.core.metric.event.transaction.TransactionConflictEvent"
			),
			"TransactionConflictEvent must be resolvable by its fully-qualified name so JFR recording " +
				"can reconstruct it"
		);

		// it must also be part of the flat set of all known custom metric events
		assertTrue(
			EvitaJfrEventRegistry.getEventClasses().contains(TransactionConflictEvent.class),
			"TransactionConflictEvent must be present among all registered custom metric event classes"
		);
	}

}
