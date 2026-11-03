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

package io.evitadb.store.wal;

import io.evitadb.store.checksum.Checksum;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Leaves a write-ahead log in the state a crash between a rotation and the first append into the new file leaves it
 * in: the active file finalized with the trailer rotation writes, and the next file holding nothing but the 8-byte
 * cumulative checksum header rotation seeds it with.
 *
 * Rotation is private to the log and runs only inside an append, so an engine cannot be stopped between the two.
 * The crash state is therefore produced byte for byte the way `AbstractMutationLog#rotateWalFileInternal` produces
 * it - the trailer and the header carry the values the log itself would have computed - and only the append that
 * would have followed is left out. The values are captured from the live log, which is closed before they are
 * written, because the log keeps the active file open for appending.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class WalRotationCrashTestSupport {

	private WalRotationCrashTestSupport() {
	}

	/**
	 * Captures what rotating the active file of the passed log away would write, without writing anything yet.
	 *
	 * @param mutationLog the open log whose active file holds at least one transaction
	 * @return the crash state to write once the log is closed
	 */
	@Nonnull
	public static RotationCrash captureRotationCrash(@Nonnull AbstractMutationLog<?> mutationLog) {
		final CurrentMutationLogFile activeFile = mutationLog.currentWalFile.get();
		final long firstVersion = activeFile.getFirstVersionOfCurrentWalFile();
		final long lastVersion = activeFile.getLastWrittenVersion();
		if (firstVersion < 1L || lastVersion < firstVersion) {
			throw new IllegalStateException(
				"The active WAL file must hold at least one transaction to be rotated away, but it holds versions " +
					firstVersion + " to " + lastVersion + "!"
			);
		}
		// the same steps `AbstractMutationLog#writeWalTail` performs on the log's own checksum
		final long cumulativeChecksum = activeFile.getCumulativeChecksum();
		final Checksum checksum = mutationLog.storageSettings.createCumulativeChecksum(cumulativeChecksum);
		checksum.reset(cumulativeChecksum);
		checksum.update(cumulativeChecksum);
		checksum.update(firstVersion);
		checksum.update(lastVersion);
		return new RotationCrash(
			activeFile.getWalFilePath(),
			mutationLog.storageFolder.resolve(
				mutationLog.walFileNameProvider.apply(activeFile.getWalFileIndex() + 1)
			),
			firstVersion,
			lastVersion,
			checksum.getValue()
		);
	}

	/**
	 * The bytes a rotation writes before the append that triggered it lands in the new file.
	 *
	 * @param activeWalFile           the file being rotated away
	 * @param nextWalFile             the file rotation creates
	 * @param firstVersion            the first version in the file being rotated away
	 * @param lastVersion             the last version in the file being rotated away
	 * @param finalCumulativeChecksum the checksum closing the trailer, which also seeds the next file
	 */
	public record RotationCrash(
		@Nonnull Path activeWalFile,
		@Nonnull Path nextWalFile,
		long firstVersion,
		long lastVersion,
		long finalCumulativeChecksum
	) {

		/**
		 * Writes the trailer to the rotated file and creates the next file with only its header. The log must be
		 * closed by now.
		 *
		 * @throws IOException when either file cannot be written, or the next file already exists
		 */
		public void write() throws IOException {
			final ByteBuffer trailer = ByteBuffer.allocate(AbstractMutationLog.WAL_TAIL_LENGTH)
				.order(ByteOrder.LITTLE_ENDIAN)
				.putLong(this.firstVersion)
				.putLong(this.lastVersion)
				.putLong(this.finalCumulativeChecksum);
			Files.write(this.activeWalFile, trailer.array(), StandardOpenOption.APPEND);
			final ByteBuffer header = ByteBuffer.allocate(AbstractMutationLog.CUMULATIVE_CRC32_SIZE)
				.order(ByteOrder.LITTLE_ENDIAN)
				.putLong(this.finalCumulativeChecksum);
			Files.write(this.nextWalFile, header.array(), StandardOpenOption.CREATE_NEW);
		}

	}

}
