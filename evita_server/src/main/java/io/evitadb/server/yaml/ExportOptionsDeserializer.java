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
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.export.ExportServiceFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Custom Jackson deserializer for {@link ExportOptions} that supports dynamic discovery
 * of export implementation configuration classes via {@link ServiceLoader}.
 *
 * Each export implementation (fileSystem, s3, etc.) is a concrete subclass of {@link ExportOptions}
 * with its own `sizeLimitBytes`, `historyExpirationSeconds`, and implementation-specific settings.
 *
 * This deserializer:
 * 1. Reads each implementation section and deserializes to its concrete class
 * 2. Selects the highest priority enabled implementation
 * 3. Returns that implementation directly (as ExportOptions is abstract)
 *
 * YAML structure:
 * ```yaml
 * export:
 *   fileSystem:
 *     enabled: null
 *     sizeLimitBytes: 1G
 *     historyExpirationSeconds: 7d
 *     directory: "./export"
 *   s3:
 *     enabled: null
 *     sizeLimitBytes: 1G
 *     historyExpirationSeconds: 7d
 *     endpoint: null
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ExportOptionsDeserializer extends StdDeserializer<ExportOptions> {
	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Registry mapping implementation codes to their configuration classes.
	 */
	private final Map<String, Class<? extends ExportOptions>> registry = new HashMap<>(8);

	/**
	 * List of factories sorted by priority (highest first).
	 */
	private final List<ExportServiceFactory> sortedFactories;

	/**
	 * Unknown property handler for path tracking.
	 */
	private final UnknownPropertyProblemHandler unknownPropertyProblemHandler;

	/**
	 * Creates a new instance of the deserializer.
	 *
	 * @param unknownPropertyProblemHandler handler for unknown properties
	 */
	public ExportOptionsDeserializer(@Nullable UnknownPropertyProblemHandler unknownPropertyProblemHandler) {
		super(ExportOptions.class);
		this.unknownPropertyProblemHandler = unknownPropertyProblemHandler;

		// Load all ExportServiceFactory implementations via ServiceLoader
		final ServiceLoader<ExportServiceFactory> factories = ServiceLoader.load(ExportServiceFactory.class);

		// Sort factories by priority (highest first) and register their configuration classes
		this.sortedFactories = factories.stream()
			.map(ServiceLoader.Provider::get)
			.sorted(Comparator.comparingInt(ExportServiceFactory::getPriority).reversed())
			.toList();

		for (ExportServiceFactory factory : this.sortedFactories) {
			this.registry.put(factory.getImplementationCode(), factory.getConfigurationClass());
		}
	}

	@Override
	public ExportOptions deserialize(
		@Nonnull JsonParser parser,
		@Nonnull DeserializationContext context
	) throws IOException {
		final ObjectMapper mapper = (ObjectMapper) parser.getCodec();
		final JsonNode root = mapper.readTree(parser);

		// Deserialize all implementation options
		final Map<String, ExportOptions> implementations = new HashMap<>(8);
		final Iterator<String> fieldNames = root.fieldNames();
		while (fieldNames.hasNext()) {
			final String fieldName = fieldNames.next();

			final Class<? extends ExportOptions> optionsClass = this.registry.get(fieldName);
			if (optionsClass != null) {
				try {
					if (this.unknownPropertyProblemHandler != null) {
						this.unknownPropertyProblemHandler.setPrefix(
							parser.getParsingContext().pathAsPointer().toString() + "/" + fieldName
						);
					}
					final ExportOptions options = mapper.treeToValue(root.get(fieldName), optionsClass);
					implementations.put(fieldName, options);
				} finally {
					if (this.unknownPropertyProblemHandler != null) {
						this.unknownPropertyProblemHandler.clearPrefix();
					}
				}
			}
		}

		// Select and return the active implementation
		return selectImplementation(implementations);
	}

	/**
	 * Selects the active export implementation based on enabled flags and priority.
	 *
	 * @param implementations map of implementation code to options
	 * @return the selected implementation options
	 */
	@Nonnull
	private ExportOptions selectImplementation(@Nonnull Map<String, ExportOptions> implementations) {
		// First, check if exactly one is explicitly enabled
		final List<ExportOptions> explicitlyEnabled = implementations.values().stream()
			.filter(opts -> Boolean.TRUE.equals(opts.getEnabled()))
			.toList();

		if (explicitlyEnabled.size() > 1) {
			throw new EvitaInvalidUsageException(
				"Only one export implementation can be enabled at a time. " +
				"Multiple implementations have enabled=true."
			);
		}

		if (explicitlyEnabled.size() == 1) {
			final ExportOptions selected = explicitlyEnabled.get(0);
			selected.validateWhenEnabled();
			return selected;
		}

		// No explicit enable, find highest priority factory with non-disabled config
		for (ExportServiceFactory factory : this.sortedFactories) {
			final String code = factory.getImplementationCode();
			final ExportOptions opts = implementations.get(code);

			// Accept if config exists and is not explicitly disabled, or use factory defaults
			if (opts != null) {
				if (!Boolean.FALSE.equals(opts.getEnabled())) {
					return opts;
				}
			} else {
				// No config for this factory, use default options
				return factory.createDefaultOptions();
			}
		}

		// Fallback: use the first factory's default options
		final Optional<ExportServiceFactory> firstFactory = this.sortedFactories.stream().findFirst();
		if (firstFactory.isPresent()) {
			return firstFactory.get().createDefaultOptions();
		}

		throw new EvitaInvalidUsageException(
			"No export implementation available. At least one export module must be on the classpath."
		);
	}
}
