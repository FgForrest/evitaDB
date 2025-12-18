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

package io.evitadb.server.yaml;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.evitadb.api.configuration.ClusterOptions;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.cluster.EnvironmentServiceFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Custom Jackson deserializer for {@link ClusterOptions} that supports dynamic discovery
 * of cluster implementation configuration classes via {@link ServiceLoader}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ClusterOptionsDeserializer extends StdDeserializer<ClusterOptions> {
	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Registry mapping implementation codes to their configuration classes.
	 */
	private final Map<String, Class<? extends ClusterOptions>> registry = new HashMap<>(8);

	/**
	 * Unknown property handler for path tracking.
	 */
	private final UnknownPropertyProblemHandler unknownPropertyProblemHandler;

	/**
	 * Creates a new instance of the deserializer.
	 *
	 * @param unknownPropertyProblemHandler handler for unknown properties
	 */
	public ClusterOptionsDeserializer(@Nullable UnknownPropertyProblemHandler unknownPropertyProblemHandler) {
		super(ClusterOptions.class);
		this.unknownPropertyProblemHandler = unknownPropertyProblemHandler;

		// Load all EnvironmentServiceFactory implementations via ServiceLoader
		final ServiceLoader<EnvironmentServiceFactory> factories = ServiceLoader.load(EnvironmentServiceFactory.class);

		for (EnvironmentServiceFactory factory : factories) {
			this.registry.put(factory.getImplementationCode(), factory.getConfigurationClass());
		}
	}

	@Nullable
	@Override
	public ClusterOptions deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		final ObjectMapper mapper = (ObjectMapper) p.getCodec();
		final JsonNode node = mapper.readTree(p);

		// Map to store deserialized configurations
		final Map<String, ClusterOptions> deserializedOptions = new HashMap<>();

		// Iterate over fields in the YAML object (e.g., "mock", "k8s")
		final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
		while (fields.hasNext()) {
			final Map.Entry<String, JsonNode> field = fields.next();
			final String implementationCode = field.getKey();
			final JsonNode implementationNode = field.getValue();

			// Find corresponding configuration class
			final Class<? extends ClusterOptions> configClass = this.registry.get(implementationCode);

			if (configClass != null) {
				// We need to set the path prefix for unknown property reporting
				if (this.unknownPropertyProblemHandler != null) {
					this.unknownPropertyProblemHandler.setPrefix(
						p.getParsingContext().pathAsPointer().toString() + "/" + implementationCode
					);
				}
				try {
					// Deserialize to the specific configuration class
					final ClusterOptions options = mapper.treeToValue(implementationNode, configClass);
					deserializedOptions.put(implementationCode, options);
				} finally {
					if (this.unknownPropertyProblemHandler != null) {
						this.unknownPropertyProblemHandler.clearPrefix();
					}
				}
			} else if (this.unknownPropertyProblemHandler != null) {
				// Report unknown implementation
				this.unknownPropertyProblemHandler.handleUnknownProperty(
					ctxt,
					p,
					this,
					ClusterOptions.class,
					implementationCode
				);
			}
		}

		return selectImplementation(deserializedOptions);
	}

	/**
	 * Selects the active implementation based on enabled flags.
	 *
	 * @param optionsMap map of implementation code to deserialized options
	 * @return selected options instance or null if none selected
	 */
	@Nullable
	private ClusterOptions selectImplementation(Map<String, ClusterOptions> optionsMap) {
		// 1. Find implementations that are explicitly enabled (enabled=true)
		final List<ClusterOptions> explicitlyEnabled = optionsMap.values().stream()
			.filter(opt -> Boolean.TRUE.equals(opt.getEnabled()))
			.toList();

		if (explicitlyEnabled.size() > 1) {
			throw new EvitaInvalidUsageException(
				"Multiple cluster implementations are enabled: " +
					explicitlyEnabled.stream().map(ClusterOptions::getImplementationCode).toList() +
					". Only one cluster implementation can be active at a time."
			);
		}

		if (explicitlyEnabled.size() == 1) {
			final ClusterOptions selected = explicitlyEnabled.get(0);
			selected.validateWhenEnabled();
			return selected;
		}

		// 2. If none explicitly enabled, check for implementations that are NOT explicitly disabled (enabled!=false)
		final List<ClusterOptions> candidates = optionsMap.values().stream()
			.filter(opt -> !Boolean.FALSE.equals(opt.getEnabled()))
			.toList();

		if (candidates.size() > 1) {
			throw new EvitaInvalidUsageException(
				"Multiple cluster implementations are configured but none is explicitly enabled: " +
					candidates.stream().map(ClusterOptions::getImplementationCode).toList() +
					". Please explicitly enable one of them."
			);
		}

		if (candidates.size() == 1) {
			final ClusterOptions selected = candidates.get(0);
			selected.validateWhenEnabled();
			return selected;
		}

		return null;
	}
}
