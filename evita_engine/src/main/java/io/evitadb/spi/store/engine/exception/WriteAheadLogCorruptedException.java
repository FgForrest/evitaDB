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

package io.evitadb.spi.store.engine.exception;

import io.evitadb.exception.EvitaInternalError;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.nio.file.Path;
import java.util.function.IntFunction;

/**
 * Exception indicating that a Write-Ahead Log (WAL) file has been corrupted. evitaDB owns two flavors
 * of WAL — the per-catalog WAL and the engine-level mutation log — and this exception covers both.
 * The {@link WalKind} carried with each instance disambiguates the origin so diagnostics, logs, and
 * tooling can attribute the corruption to the correct subsystem without requiring a separate
 * exception class per flavor.
 *
 * Each occurrence represents a serious data-integrity problem that must be examined and resolved.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class WriteAheadLogCorruptedException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -9127477065404573236L;

	@Getter private final WalKind walKind;

	public WriteAheadLogCorruptedException(
		@Nonnull WalKind walKind,
		@Nonnull String privateMessage,
		@Nonnull String publicMessage
	) {
		super(privateMessage, publicMessage);
		this.walKind = walKind;
	}

	public WriteAheadLogCorruptedException(
		@Nonnull WalKind walKind,
		@Nonnull String privateMessage,
		@Nonnull String publicMessage,
		@Nonnull Throwable cause
	) {
		super(privateMessage, publicMessage, cause);
		this.walKind = walKind;
	}

	/**
	 * Creates an exception describing a boundary mismatch between two adjacent WAL files — the first
	 * version of `walIndex` does not directly follow the last version of `walIndex - 1`.
	 *
	 * @param walKind             which WAL flavor the boundary mismatch was detected on
	 * @param walIndex            index of the WAL file whose first version is being validated
	 * @param lastVersion         last version found in the previous WAL file
	 * @param firstVersion        first version found in this WAL file
	 * @param walFileNameProvider function that resolves a WAL file index to its file name
	 */
	public WriteAheadLogCorruptedException(
		@Nonnull WalKind walKind,
		int walIndex,
		long lastVersion,
		long firstVersion,
		@Nonnull IntFunction<String> walFileNameProvider
	) {
		super(
			"First version of the " + walKind.fileLabel + " `" + walFileNameProvider.apply(walIndex) +
				"` doesn't follow up to the last version of the previous " + walKind.fileLabel + " `" +
				walFileNameProvider.apply(walIndex - 1) + "`! Last version found: `" + lastVersion +
				"`, first version of the next " + walKind.fileLabel + " : `" + firstVersion + "`! ",
			"First version of the " + walKind.fileLabel + " doesn't follow up to the last version of the previous " +
				walKind.fileLabel + "!"
		);
		this.walKind = walKind;
	}

	/**
	 * Creates an exception describing a cumulative CRC32C checksum mismatch in a WAL file.
	 *
	 * @param walKind          which WAL flavor the mismatch was detected on
	 * @param walFilePath      the path to the WAL file where the mismatch was detected
	 * @param position         the byte position in the file where the mismatch was detected
	 * @param expectedChecksum the checksum value stored in the WAL file
	 * @param actualChecksum   the checksum value computed from the data
	 */
	public WriteAheadLogCorruptedException(
		@Nonnull WalKind walKind,
		@Nonnull Path walFilePath,
		long position,
		long expectedChecksum,
		long actualChecksum
	) {
		super(
			"Cumulative CRC32C mismatch at position " + position + " in " + walKind.fileLabel + " `" +
				walFilePath + "`. Expected: " + expectedChecksum + ", actual: " + actualChecksum,
			walKind.corruptedLabel + ": checksum verification failed"
		);
		this.walKind = walKind;
	}

	/**
	 * Creates an exception indicating that the engine WAL contains a transaction header whose matching
	 * engine-mutation body is missing — the next observed record is the header of a *later* transaction.
	 * Every committed transaction header in the engine WAL must be followed by exactly one body, so this
	 * is a structural data-integrity violation. Always carries {@link WalKind#ENGINE}.
	 *
	 * @param version                   the version of the transaction whose body is missing
	 * @param nextObservedHeaderVersion the version of the next observed transaction header
	 */
	@Nonnull
	public static WriteAheadLogCorruptedException headerWithoutBody(long version, long nextObservedHeaderVersion) {
		return new WriteAheadLogCorruptedException(
			WalKind.ENGINE,
			"Engine WAL is malformed at version " + version + ": transaction header is " +
				"present but its engine-mutation body is missing — the next observed record is " +
				"the header of transaction " + nextObservedHeaderVersion + ".",
			"Engine mutation log corrupted: header without matching body."
		);
	}

	/**
	 * Creates an exception indicating that the engine WAL was truncated mid-record: a transaction header
	 * is present but the mutation stream ended before its engine-mutation body was found. Either the WAL
	 * was truncated or the body was never durably written. Always carries {@link WalKind#ENGINE}.
	 *
	 * @param version the version of the transaction whose body is missing
	 */
	@Nonnull
	public static WriteAheadLogCorruptedException truncatedMidRecord(long version) {
		return new WriteAheadLogCorruptedException(
			WalKind.ENGINE,
			"Engine WAL is malformed at version " + version + ": transaction header is present but " +
				"the mutation stream ended before its engine-mutation body was found.",
			"Engine mutation log corrupted: truncated mid-record."
		);
	}

	/**
	 * Identifies which kind of WAL the corruption was detected on. Carried as a tag on every instance
	 * so callers and log messages can disambiguate without needing a separate exception subclass per
	 * flavor.
	 */
	public enum WalKind {
		/**
		 * Per-catalog Write-Ahead Log holding catalog-bound mutations.
		 */
		CATALOG("WAL file", "WAL file corrupted"),

		/**
		 * Engine-level mutation log holding catalog lifecycle and engine-state mutations.
		 */
		ENGINE("engine WAL file", "Engine mutation log corrupted");

		/**
		 * Label used in private (log) messages when referring to the WAL file itself
		 * (e.g. `"... in {fileLabel} \`/path/to/wal\`"`).
		 */
		public final String fileLabel;
		/**
		 * Label used in public-facing messages as the leading "X corrupted: ..." prefix
		 * (e.g. `"WAL file corrupted: checksum verification failed"`).
		 */
		public final String corruptedLabel;

		WalKind(@Nonnull String fileLabel, @Nonnull String corruptedLabel) {
			this.fileLabel = fileLabel;
			this.corruptedLabel = corruptedLabel;
		}
	}
}
