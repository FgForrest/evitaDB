/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.traffic;


import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Interface for exporting a consistent, on-demand snapshot of the currently buffered traffic recording window
 * (as opposed to {@link TrafficRecordingReader}, which offers filtered, on-going query access to the same data).
 *
 * **Purpose and Usage**
 *
 * The disk-backed traffic recording ring buffer only ever retains a bounded, recent window of data. This interface
 * lets a caller pull the *entire current window* out verbatim (raw bytes, no filtering) so it can be persisted
 * outside the ring buffer (e.g. zipped and offered for download), without needing the recording to have been
 * explicitly started as a one-shot task and without interrupting live recording.
 *
 * **Resource Management**
 *
 * Implementations stream each exported session's bytes directly to the caller-supplied {@link SessionByteSource},
 * so no full-buffer materialization happens in memory. The export walks a snapshot frozen at call time - sessions
 * appended afterward are simply not part of it.
 *
 * **Skipped Sessions**
 *
 * A session may be skipped (never causing a truncated result) if its data is being concurrently overwritten by the
 * live recorder, or if it was evicted from the ring buffer window before the export could validate it. Skipped
 * sessions are counted in the returned {@link ExportSummary}, never partially emitted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see TrafficRecordingReader
 */
public interface TrafficRecordingExporter {

	/**
	 * Exports a consistent snapshot of the currently buffered traffic recording window, invoking
	 * {@code sessionConsumer} once per exported session with a {@link SessionByteSource} that streams that
	 * session's raw bytes on demand, and {@code progressListener} after each processed (exported or skipped)
	 * session.
	 *
	 * @param sessionConsumer  invoked once per exported session
	 * @param progressListener invoked after each processed (exported or skipped) session
	 * @return a summary of how many sessions were exported/skipped and how many bytes were copied
	 * @throws IOException if the sessionConsumer's own I/O (e.g. writing to a destination stream) fails
	 */
	@Nonnull
	ExportSummary exportTrafficRecording(
		@Nonnull ExportedSessionConsumer sessionConsumer,
		@Nonnull ExportProgressListener progressListener
	) throws IOException;

	/**
	 * Invoked once per exported session, identified by its {@code sequenceOrder}, with a {@link SessionByteSource}
	 * that can stream that session's raw bytes into any destination the implementation chooses (e.g. deciding
	 * whether to open a new archive entry before writing).
	 */
	@FunctionalInterface
	interface ExportedSessionConsumer {

		/**
		 * @param sequenceOrder monotonically increasing sequence order identifying the exported session
		 * @param byteSource    source that streams the session's raw bytes on demand
		 * @throws IOException if writing the bytes to the implementation's destination fails
		 */
		void accept(long sequenceOrder, @Nonnull SessionByteSource byteSource) throws IOException;

	}

	/**
	 * Streams one session's raw, verbatim bytes into the given output stream.
	 */
	@FunctionalInterface
	interface SessionByteSource {

		/**
		 * @param outputStream destination to copy the session's raw bytes into
		 * @throws IOException if reading the session data or writing to the output stream fails
		 */
		void copyTo(@Nonnull OutputStream outputStream) throws IOException;

	}

	/**
	 * Reports export progress after each processed (exported or skipped) session.
	 */
	@FunctionalInterface
	interface ExportProgressListener {

		/**
		 * @param processed number of sessions processed so far (exported + skipped)
		 * @param total     total number of sessions in the snapshot
		 */
		void onProgress(int processed, int total);

	}

	/**
	 * Summary of one {@link #exportTrafficRecording} run.
	 *
	 * @param exportedSessionCount number of sessions whose raw bytes were handed to the session consumer
	 * @param exportedByteCount    total number of raw bytes copied
	 * @param skippedSessionCount  number of sessions skipped (concurrently overwritten or evicted during the walk)
	 * @param totalSessionCount    total number of sessions in the frozen snapshot (exported + skipped)
	 */
	record ExportSummary(
		int exportedSessionCount,
		long exportedByteCount,
		int skippedSessionCount,
		int totalSessionCount
	) {
	}

}
