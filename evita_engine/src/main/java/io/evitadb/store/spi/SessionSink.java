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

package io.evitadb.store.spi;


import io.evitadb.stream.RandomAccessFileInputStream;

import javax.annotation.Nonnull;
import java.io.RandomAccessFile;
import java.util.Deque;

/**
 * The {@code SessionSink} interface provides a mechanism for handling and processing
 * session data stored in a disk buffer. It defines methods for initializing a data
 * source and responding to updates in session locations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface SessionSink {

	/**
	 * Initializes the source input stream for the session sink. The input stream is expected
	 * to provide data from a disk buffer file and must not be null.
	 *
	 * @param inputStream the {@link RandomAccessFileInputStream} instance to set as the source input stream.
	 *                    This stream is used for reading session data from a specific file location, starting
	 *                    from the current position of the {@link RandomAccessFile}.
	 */
	void initSourceInputStream(@Nonnull RandomAccessFileInputStream inputStream);

	/**
	 * Callback function that is called whenever queue of session locations is updated. Each location contains
	 * details about an individual session, including its sequence order and file location.
	 *
	 * @param sessionLocations the deque of {@link SessionLocation} instances representing the
	 *                         sessions in the current disk buffer
	 */
	void onSessionLocationsUpdated(@Nonnull Deque<SessionLocation> sessionLocations);

	/**
	 * Handles cleanup operations when the disk buffer is closed. This method will handle final tasks
	 * associated with the provided queue of session locations, such as releasing resources or writing
	 * final data to storage.
	 *
	 * @param sessionLocations the deque of {@link SessionLocation} instances representing the sessions
	 *                         in the current disk buffer to be processed during the close operation
	 */
	void onClose(@Nonnull Deque<SessionLocation> sessionLocations);

}
