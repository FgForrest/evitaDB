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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;

/**
 * Refuses a backup or recording whose archive cannot fit in the export storage. The storage drops its oldest files
 * whenever it exceeds its configured size limit, so a larger archive would first evict every other export and then
 * be deleted itself. The export therefore stops as soon as it crosses the limit and nothing is published.
 *
 * The message names the setting to raise, because that is the only remedy: the limit is read at server start, so the
 * operator raises it and restarts the server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ExportFileTooLargeException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7895558253175052175L;

	/**
	 * Creates the refusal, with the same message for the client and for the server log.
	 *
	 * @param catalogName catalog being backed up
	 * @param configuredLimitInBytes configured storage limit, before reserving backend metadata
	 * @param configurationProperty property controlling this backend's limit
	 * @param onDiskSizeInBytes current catalog size, or null if it could not be measured
	 */
	public ExportFileTooLargeException(
		@Nonnull String catalogName,
		long configuredLimitInBytes,
		@Nonnull String configurationProperty,
		@Nullable Long onDiskSizeInBytes
	) {
		super(message(
			"The backup of catalog `" + catalogName + "`", "backup", configuredLimitInBytes,
			configurationProperty, onDiskSizeInBytes
		));
	}

	/**
	 * Creates a recording refusal with the backend setting to raise before restarting the server.
	 *
	 * @param recordingSubject recording being stopped, including its catalog when applicable
	 * @param configuredLimitInBytes configured storage limit, before reserving backend metadata
	 * @param configurationProperty property controlling this backend's limit
	 */
	public ExportFileTooLargeException(
		@Nonnull String recordingSubject, long configuredLimitInBytes, @Nonnull String configurationProperty
	) {
		super(message(recordingSubject, "recording", configuredLimitInBytes, configurationProperty, null));
	}

	/** Builds the message; the environment variable follows `EvitaServer#transformEnvironmentVariable`. */
	@Nonnull
	private static String message(
		@Nonnull String subject, @Nonnull String exportKind, long configuredLimitInBytes,
		@Nonnull String configurationProperty, @Nullable Long onDiskSizeInBytes
	) {
		final String environmentVariable =
			"EVITADB_" + configurationProperty.replace('.', '_').toUpperCase(Locale.ROOT);
		final String refusal = subject + " was stopped because it exceeded the export storage limit of " +
			StringUtils.formatByteSize(configuredLimitInBytes) + "; the server would delete a larger file. No " +
			exportKind + " was created. Increase `" + configurationProperty + "` in the server configuration (or `" +
			environmentVariable + "`) and restart the server.";
		return onDiskSizeInBytes == null ?
			refusal :
			refusal + " The catalog currently occupies " + StringUtils.formatByteSize(onDiskSizeInBytes) + " on disk.";
	}
}
