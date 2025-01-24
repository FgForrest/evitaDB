/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.requestResponse.trafficRecording;


import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Shared contract for all objects that represent a traffic recording.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface TrafficRecording {

	/**
	 * The session sequence order of the recording. The sequence order is a unique, monotonically increasing identifier
	 * of the session in the session stream. All recordings within the session have the same session sequence order as
	 * well as {@link #sessionId()}. The sequence order might not be present for recordings that hasn't been collected
	 * in the session stream yet.
	 * @return the session sequence order
	 */
	@Nullable
	Long sessionSequenceOrder();

	/**
	 * The session id which the recording belongs to.
	 * @return the session id
	 */
	@Nonnull
	UUID sessionId();

	/**
	 * The order (sequence) of the traffic recording in the session. First record in the session has sequence ID 0 and
	 * represents the session start, additional records are numbered sequentially.
	 * @return the record offset sequence ID within particular session
	 */
	int recordSessionOffset();

	/**
	 * Contains total count of the records in the session. This number allows clients to determine whether
	 * the {@link #recordSessionOffset()} is the last record in the session
	 * (i.e. when recordSessionOffset = recordsInSession - 1, then it is the last record).
	 *
	 * The value is known only when the entire session has been stored.
	 *
	 * @return the total count of the records in the session
	 */
	@Nullable
	Integer sessionRecordsCount();

	/**
	 * The type of the recording.
	 * @return the recording type
	 */
	@Nonnull
	TrafficRecordingType type();

	/**
	 * The time when the recording was created.
	 * @return the creation time
	 */
	@Nonnull OffsetDateTime created();

	/**
	 * The duration of the operation in milliseconds.
	 * @return the duration in milliseconds
	 */
	int durationInMilliseconds();

	/**
	 * The size of the data fetched from the permanent storage in bytes.
	 * @return the size in bytes fetched
	 */
	int ioFetchedSizeBytes();

	/**
	 * The number of objects fetched from the permanent storage.
	 * @return the number of objects fetched
	 */
	int ioFetchCount();

}
