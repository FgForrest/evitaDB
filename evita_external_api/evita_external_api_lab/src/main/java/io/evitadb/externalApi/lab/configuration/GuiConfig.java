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

package io.evitadb.externalApi.lab.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Configuration of lab GUI.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GuiConfig {
	/**
	 * Whether the GUI is enabled.
	 */
	@Getter private final boolean enabled;
	/**
	 * Whether the GUI is in read-only mode.
	 */
	@Getter private final boolean readOnly;
	/**
	 * Whether to prefer using the incoming request's host and port over the resolved expose URL when creating a self-connection.
	 */
	@Getter private final boolean preferIncomingHostAndPort;

	/**
	 * Default constructor with default values.
	 */
	public GuiConfig() {
		this.enabled = true;
		this.readOnly = false;
		this.preferIncomingHostAndPort = true;
	}

	/**
	 * Constructor with custom values.
	 *
	 * @param enabled whether the GUI is enabled
	 * @param readOnly whether the GUI is in read-only mode
	 * @param preferIncomingHostAndPort whether to prefer using the incoming request's host and port
	 */
	@JsonCreator
	public GuiConfig(@Nullable @JsonProperty("enabled") Boolean enabled,
	                 @Nullable @JsonProperty("readOnly") Boolean readOnly,
	                 @Nullable @JsonProperty("preferIncomingHostAndPort") Boolean preferIncomingHostAndPort) {
		this.enabled = Optional.ofNullable(enabled).orElse(true);
		this.readOnly = Optional.ofNullable(readOnly).orElse(false);
		this.preferIncomingHostAndPort = Optional.ofNullable(preferIncomingHostAndPort).orElse(true);
	}

}
