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

package io.evitadb.spi.store.catalog.trafficRecorder;

import io.evitadb.stream.RandomAccessFileInputStream;

import javax.annotation.Nonnull;
import java.io.RandomAccessFile;

/**
 * Interface representing a session sink that can be used to export session data
 * before they are deleted from the disk ring buffer. Implementations of this interface
 * should provide logic for initializing the source input stream and handling session
 * location updates and closure events.
 */
public interface RandomAccessFileSessionSink extends SessionSink {

	/**
	 * Initializes the source input stream for the session sink. The input stream is expected
	 * to provide data from a disk buffer file and must not be null.
	 *
	 * @param inputStream the {@link RandomAccessFileInputStream} instance to set as the source input stream.
	 *                    This stream is used for reading session data from a specific file location, starting
	 *                    from the current position of the {@link RandomAccessFile}.
	 */
	void initSourceInputStream(@Nonnull RandomAccessFileInputStream inputStream);

}
