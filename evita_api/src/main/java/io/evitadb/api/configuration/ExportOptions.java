/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.configuration;

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract base class for all export service configuration options.
 * Each implementation (filesystem, S3, etc.) extends this class and provides
 * its own implementation-specific settings along with the common export settings.
 *
 * Common settings include:
 * - `enabled`: determines which export implementation is active
 * - `sizeLimitBytes`: maximum overall size of the export storage
 * - `historyExpirationSeconds`: maximal age of exported files
 *
 * The selection logic is:
 * - If all implementations have `enabled=null`, fileSystem is used as default (highest priority)
 * - If exactly one implementation has `enabled=true`, that implementation is used
 * - If more than one implementation has `enabled=true`, a configuration error is thrown
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class ExportOptions {

	/**
	 * Default maximum size of the export storage (1GB).
	 */
	public static final long DEFAULT_SIZE_LIMIT_BYTES = 1_073_741_824L;

	/**
	 * Default maximum age of exported files (7 days in seconds).
	 */
	public static final long DEFAULT_HISTORY_EXPIRATION_SECONDS = 604_800L;

	/**
	 * Indicates whether this export implementation is enabled.
	 * When `null`, the implementation may be used as a default if no other implementation is explicitly enabled.
	 * When `true`, this implementation is explicitly enabled.
	 * When `false`, this implementation is explicitly disabled.
	 */
	@Getter
	@Nullable
	private final Boolean enabled;

	/**
	 * Maximum overall size of the export storage in bytes.
	 * When this threshold is exceeded, the oldest files will automatically be removed
	 * until the size drops below the limit.
	 */
	@Getter
	private final long sizeLimitBytes;

	/**
	 * Maximal age of exported file in seconds.
	 * When age is exceeded the file will be automatically removed.
	 */
	@Getter
	private final long historyExpirationSeconds;

	/**
	 * Default constructor with default values.
	 */
	protected ExportOptions() {
		this.enabled = null;
		this.sizeLimitBytes = DEFAULT_SIZE_LIMIT_BYTES;
		this.historyExpirationSeconds = DEFAULT_HISTORY_EXPIRATION_SECONDS;
	}

	/**
	 * Constructor with all common parameters.
	 *
	 * @param enabled                  indicates whether this export implementation is enabled
	 * @param sizeLimitBytes           maximum overall size of the export storage
	 * @param historyExpirationSeconds maximal age of exported file in seconds
	 */
	protected ExportOptions(
		@Nullable Boolean enabled,
		long sizeLimitBytes,
		long historyExpirationSeconds
	) {
		this.enabled = enabled;
		this.sizeLimitBytes = sizeLimitBytes;
		this.historyExpirationSeconds = historyExpirationSeconds;
	}

	/**
	 * Returns unique implementation code that identifies this export service type.
	 * This code is used to match the configuration with the corresponding factory implementation.
	 *
	 * @return implementation code (e.g., "fileSystem" or "s3")
	 */
	@Nonnull
	public abstract String getImplementationCode();

	/**
	 * Validates configuration when this implementation is about to be used.
	 * Override in subclasses to add implementation-specific validation.
	 * Default implementation does nothing.
	 */
	public void validateWhenEnabled() {
		// Default: no validation needed
	}

}
