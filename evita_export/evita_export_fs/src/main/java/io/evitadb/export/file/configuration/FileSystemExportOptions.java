/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.export.file.configuration;

import io.evitadb.api.configuration.ExportOptions;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Optional.ofNullable;

/**
 * Configuration options for the local filesystem export service implementation.
 * This class extends {@link ExportOptions} and adds filesystem-specific settings.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ToString
public class FileSystemExportOptions extends ExportOptions {

	/**
	 * Implementation code used to identify this export service type.
	 */
	public static final String IMPLEMENTATION_CODE = "fileSystem";

	/**
	 * Default export directory path.
	 */
	public static final Path DEFAULT_EXPORT_DIRECTORY = Paths.get("").resolve("export");

	/**
	 * Directory on local disk where Evita files are exported - for example, backups,
	 * JFR recordings, query recordings etc.
	 */
	private Path directory;

	/**
	 * Default constructor with default values.
	 */
	public FileSystemExportOptions() {
		super();
		this.directory = DEFAULT_EXPORT_DIRECTORY;
	}

	/**
	 * Constructor with all parameters.
	 *
	 * @param enabled                  indicates whether this export implementation is enabled
	 * @param sizeLimitBytes           maximum overall size of the export storage
	 * @param historyExpirationSeconds maximal age of exported file in seconds
	 * @param directory                directory on local disk where Evita files are exported
	 */
	public FileSystemExportOptions(
		@Nullable Boolean enabled,
		long sizeLimitBytes,
		long historyExpirationSeconds,
		@Nullable Path directory
	) {
		super(enabled, sizeLimitBytes, historyExpirationSeconds);
		this.directory = ofNullable(directory).orElse(DEFAULT_EXPORT_DIRECTORY);
	}

	@Nonnull
	@Override
	public String getImplementationCode() {
		return IMPLEMENTATION_CODE;
	}

	/**
	 * Returns the directory on the local disk where Evita files are exported.
	 * If the directory is not explicitly set, the method will return the default export directory.
	 *
	 * @return the path to the export directory, never null
	 */
	@Nonnull
	public Path getDirectory() {
		return this.directory == null ? DEFAULT_EXPORT_DIRECTORY : this.directory;
	}

	/**
	 * Builder for the filesystem export options.
	 * Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the filesystem export options.
	 * Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static Builder builder(@Nonnull FileSystemExportOptions options) {
		return new Builder(options);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		// when builder is used, the file system export is perceived as enabled by default
		private boolean enabled = true;
		private long sizeLimitBytes = DEFAULT_SIZE_LIMIT_BYTES;
		private long historyExpirationSeconds = DEFAULT_HISTORY_EXPIRATION_SECONDS;
		private Path directory = DEFAULT_EXPORT_DIRECTORY;

		Builder() {
		}

		Builder(@Nonnull FileSystemExportOptions options) {
			this.sizeLimitBytes = options.getSizeLimitBytes();
			this.historyExpirationSeconds = options.getHistoryExpirationSeconds();
			this.directory = options.getDirectory();
		}

		@Nonnull
		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		@Nonnull
		public Builder sizeLimitBytes(long sizeLimitBytes) {
			this.sizeLimitBytes = sizeLimitBytes;
			return this;
		}

		@Nonnull
		public Builder historyExpirationSeconds(long historyExpirationSeconds) {
			this.historyExpirationSeconds = historyExpirationSeconds;
			return this;
		}

		@Nonnull
		public Builder directory(@Nullable Path directory) {
			this.directory = directory == null ? DEFAULT_EXPORT_DIRECTORY : directory;
			return this;
		}

		@Nonnull
		public FileSystemExportOptions build() {
			return new FileSystemExportOptions(
				this.enabled,
				this.sizeLimitBytes,
				this.historyExpirationSeconds,
				this.directory
			);
		}
	}

}
