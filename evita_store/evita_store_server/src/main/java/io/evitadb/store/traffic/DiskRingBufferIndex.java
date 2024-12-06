/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.traffic;


import io.evitadb.core.traffic.TrafficRecording;
import io.evitadb.core.traffic.TrafficRecordingCaptureRequest;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.traffic.data.SessionLocation;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class DiskRingBufferIndex {
	/**
	 * Map of session positions in the disk buffer file.
	 */
	private final Map<Long, FileLocation> sessionLocationIndex = new ConcurrentHashMap<>(1_024);
	/**
	 * TODO JNO - document me
	 */
	private final Map<UUID, Long> sessionIdIndex = new ConcurrentHashMap<>(1_024);

	public void setupSession(@Nonnull SessionLocation sessionLocation) {

	}

	public void removeSession(long sessionSequenceOrder) {

	}

	public void indexRecording(long sessionSequenceOrder, @Nonnull TrafficRecording recording) {

	}

	public boolean sessionExists(long sessionSequenceOrder) {
		return false;
	}

	@Nonnull
	public Stream<SessionLocation> getSessionStream(@Nonnull TrafficRecordingCaptureRequest request) {
		return null;
	}
}
