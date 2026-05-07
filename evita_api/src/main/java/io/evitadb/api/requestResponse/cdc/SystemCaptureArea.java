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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.mutation.EngineMutation;

/**
 * Capture area selector for the system CDC stream.
 *
 * **Why this enum exists separately from `CaptureArea`.** The catalog-stream
 * {@link CaptureArea} has three values — `SCHEMA`, `DATA`, `INFRASTRUCTURE` — which
 * model sub-areas inside a single catalog. The system stream has no schema- or data-level
 * semantics at all; it carries only engine-level mutations and host-local host
 * events. Reusing {@link CaptureArea} on the system stream would either force runtime
 * rejection of `SCHEMA` / `DATA` requests (silent or noisy — both bad) or pretend that
 * all four areas exist there, neither of which is correct. A dedicated, narrower enum
 * makes the contract honest and lets the type system enforce it.
 *
 * **Default-criteria divergence vs `ChangeCatalogCaptureRequest`.** On
 * {@link ChangeSystemCaptureRequest}, when `criteria == null` the default is `ENGINE`
 * only — `HOST` is **not** included. This is intentional and differs from
 * the catalog stream where a null criteria captures everything. The reason is that
 * `HOST` here means host-local, non-replicable, live-tail-only events
 * (see {@link HostSystemEvent}); existing clients that have not opted in must keep
 * receiving exactly the engine-mutation flow they already see, otherwise the stream
 * shape changes silently between versions. Subscribers that want host events must
 * explicitly request {@link #HOST}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum SystemCaptureArea {

	/**
	 * Engine-level mutations executed against the entire evitaDB instance — durable,
	 * WAL-replicated, version-bearing. Carries {@link EngineMutation} bodies.
	 */
	ENGINE,

	/**
	 * Host events about the live view of catalogs on this host —
	 * transient (not persisted), non-replicable, live-tail-only (no historical replay).
	 * Carries {@link HostSystemEvent} bodies.
	 *
	 * Requires explicit opt-in via {@link ChangeSystemCaptureCriteria}; not included
	 * in the default (null-criteria) flow.
	 */
	HOST

}
