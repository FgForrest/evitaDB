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

package io.evitadb.api.requestResponse.system;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Curated view of the engine-wide settings a client needs in order to reason about the behaviour
 * of the server it talks to - the behaviour it cannot observe any other way, plus the capabilities
 * it may or may not rely on.
 *
 * This is deliberately **not** the full configuration.
 * {@link io.evitadb.api.EvitaManagementContract#getConfiguration()} renders the entire
 * configuration file - including filesystem paths and credentials - and is therefore restricted to
 * administrators and refused outright while the engine runs in read-only mode. The values collected
 * here carry no sensitive information, are safe to hand to any client, and remain readable in
 * read-only mode.
 *
 * The record is intentionally flat rather than mirroring the sectioning of
 * {@link io.evitadb.api.configuration.EvitaConfiguration}: only a small fraction of the
 * configuration is client-actionable, and which section a value happens to live in is an accident
 * of the server's own configuration history that the caller should not have to know. The vast
 * majority of configuration properties are internal tuning knobs (buffer sizes, compaction
 * thresholds, thread-pool shapes) that a client can neither act on nor benefit from.
 *
 * Deliberately absent, because they are already published elsewhere:
 *
 * - the enabled external APIs together with their base URLs and endpoints, and the read-only flag -
 *   see {@link SystemStatus} and the server status endpoint
 *
 * All values originate from the configuration file and are therefore constant for the entire
 * lifetime of the server process - a client may safely cache the result until it reconnects.
 *
 * @param conflictResolution       the engine-wide default conflict resolution applied to a
 *                                 transaction commit when neither the catalog schema nor the entity
 *                                 schema declares its own; forms the base of the precedence walk
 *                                 performed by `EffectiveConflictResolutionResolver`, never null
 * @param timeTravelEnabled        true when the engine retains historical data, so queries and
 *                                 restores targeting a past point in time are available at all
 * @param changeDataCaptureEnabled true when clients may subscribe to change data capture streams
 * @param trafficRecordingEnabled  true when the server records client traffic, so recordings can be
 *                                 started, inspected and exported
 * @param queryCacheEnabled        true when the engine caches computed query results; affects
 *                                 latency characteristics only, never query results
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record EngineSettings(
	@Nonnull ConflictResolution conflictResolution,
	boolean timeTravelEnabled,
	boolean changeDataCaptureEnabled,
	boolean trafficRecordingEnabled,
	boolean queryCacheEnabled
) implements Serializable {

}
