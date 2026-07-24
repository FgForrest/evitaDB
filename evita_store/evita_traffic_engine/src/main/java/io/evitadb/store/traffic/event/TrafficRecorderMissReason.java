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

package io.evitadb.store.traffic.event;

/**
 * Classifies why a traffic record or an entire session was not persisted by the traffic recorder.
 * The value is exported as the `reason` dimension of the traffic-recorder skip metrics so operators
 * can tell benign sampling from genuine resource pressure at a glance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum TrafficRecorderMissReason {

	/**
	 * The record/session was deliberately not recorded because the current recorded fraction already
	 * meets the configured sampling target - this is expected, healthy behaviour and NOT a problem.
	 */
	SAMPLING,

	/**
	 * The session was dropped because no free off-heap memory block was available while it was being
	 * recorded - the in-memory buffer is under pressure (too small for the current write throughput).
	 */
	MEMORY_SHORTAGE,

	/**
	 * The finalized session was dropped because it was larger than the whole disk ring buffer and thus
	 * could never be appended - the disk buffer is too small for such large sessions.
	 */
	DISK_SHORTAGE,

	/**
	 * The finalized session was dropped because a disk write failed part-way through persisting it.
	 */
	IO_ERROR,

	/**
	 * The session was dropped because serializing one of its records failed unexpectedly - a genuine
	 * error worth investigating (as opposed to the resource-pressure reasons above).
	 */
	SERIALIZATION_ERROR

}
