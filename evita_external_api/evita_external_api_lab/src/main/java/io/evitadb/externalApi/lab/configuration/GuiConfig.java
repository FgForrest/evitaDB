/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.lab.gui.dto.EvitaDBConnection;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Configuration of lab GUI.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GuiConfig {

	@Getter private final boolean enabled;
	@Getter private final boolean readOnly;
	@Getter @Nullable private final List<EvitaDBConnection> preconfiguredConnections;

	public GuiConfig() {
		this.enabled = true;
		this.readOnly = false;
		this.preconfiguredConnections = null;
	}

	@JsonCreator
	public GuiConfig(@Nullable @JsonProperty("enabled") Boolean enabled,
	                 @Nullable @JsonProperty("readOnly") Boolean readOnly,
	                 @Nullable @JsonProperty("preconfiguredConnections") List<EvitaDBConnection> preconfiguredConnections) {
		this.enabled = Optional.ofNullable(enabled).orElse(true);
		this.readOnly = Optional.ofNullable(readOnly).orElse(false);
		validatePreconfiguredConnections(preconfiguredConnections);
		this.preconfiguredConnections = preconfiguredConnections;
	}

	private static void validatePreconfiguredConnections(@Nullable List<EvitaDBConnection> preconfiguredConnections) {
		if (preconfiguredConnections == null) {
			return;
		}
		preconfiguredConnections.stream()
			.collect(Collectors.groupingBy(EvitaDBConnection::id, Collectors.counting()))
			.entrySet()
			.stream()
			.filter(it -> it.getValue() > 1)
			.findFirst()
			.ifPresent(it -> {
				throw new EvitaInvalidUsageException("Duplicate evitaDB connection id: " + it.getKey());
			});
		preconfiguredConnections.stream()
			.collect(Collectors.groupingBy(EvitaDBConnection::name, Collectors.counting()))
			.entrySet()
			.stream()
			.filter(it -> it.getValue() > 1)
			.findFirst()
			.ifPresent(it -> {
				throw new EvitaInvalidUsageException("Duplicate evitaDB connection name: " + it.getKey());
			});
	}
}
