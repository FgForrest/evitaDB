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

package io.evitadb.api.configuration;

import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract base class for all cluster service configuration options.
 * Each implementation (mock, k8s, etc.) extends this class and provides
 * its own implementation-specific settings along with the common cluster settings.
 *
 * Common settings include:
 * - `enabled`: determines which cluster implementation is active
 *
 * The selection logic is:
 * - If exactly one implementation has `enabled=true`, that implementation is used
 * - If more than one implementation has `enabled=true`, a configuration error is thrown
 * - If no implementation is explicitly enabled, checking for implementations that are NOT explicitly disabled (enabled!=false)
 * - If exactly one such implementation exists, it is used
 * - If multiple such implementations exist, a configuration error is thrown
 * - If no such implementation exists, no cluster is configured (standalone mode)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class ClusterOptions {

	/**
	 * Indicates whether this cluster implementation is enabled.
	 * When `null`, the implementation may be used as a default if no other implementation is explicitly enabled.
	 * When `true`, this implementation is explicitly enabled.
	 * When `false`, this implementation is explicitly disabled.
	 */
	@Getter
	@Setter
	@Nullable
	private Boolean enabled;

	/**
	 * Default constructor with default values.
	 */
	protected ClusterOptions() {
		this.enabled = null;
	}

	/**
	 * Constructor with enabled parameter.
	 *
	 * @param enabled indicates whether this cluster implementation is enabled
	 */
	protected ClusterOptions(@Nullable Boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Returns unique implementation code that identifies this cluster service type.
	 * This code is used to match the configuration with the corresponding factory implementation.
	 *
	 * @return implementation code (e.g., "mock" or "k8s")
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
