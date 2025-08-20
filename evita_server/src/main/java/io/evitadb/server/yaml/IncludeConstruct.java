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

package io.evitadb.server.yaml;

import io.evitadb.server.exception.ConfigurationParseException;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.io.StringSubstitutorReader;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link org.yaml.snakeyaml.constructor.Construct} for parsing {@code !include} YAML tags that add support for
 * including partial YAML configs into the main evitaDB configuration file.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class IncludeConstruct extends AbstractConstruct {

	/**
	 * Instance of YAML parser yet to be created. The instance should be the same as used with parent constructor.
	 */
	@Nonnull private final AtomicReference<Yaml> yamlParser;
	/**
	 * Instance of string substitutor for resolving variables in included YAML files.
	 */
	@Nonnull private final StringSubstitutor stringSubstitutor;
	/**
	 * Location the parent directory of the main configuration file to be used as a base for relative paths in included YAML files.
	 */
	@Nonnull private final Path mainConfigDirectoryLocation;

	/**
	 * Creates new instance of {@link IncludeConstruct}.
	 *
	 * @param yamlParser instance of YAML parser yet to be created. The instance should be the same as used with parent constructor
	 * @param stringSubstitutor instance of string substitutor for resolving variables in included YAML files
	 * @param configDirLocation location of main configuration file to be used as a base for relative paths in included YAML files
	 */
	public IncludeConstruct(@Nonnull AtomicReference<Yaml> yamlParser,
	                        @Nonnull StringSubstitutor stringSubstitutor,
	                        @Nonnull Path configDirLocation) {
		this.mainConfigDirectoryLocation = configDirLocation;
		this.yamlParser = yamlParser;
		this.stringSubstitutor = stringSubstitutor;
	}

	@Nullable
	@Override
	public Object construct(Node node) {
		final String includedFilename = ((ScalarNode) node).getValue();
		if (includedFilename.isBlank() || includedFilename.equals("null")) {
			return null;
		}

		final Path includedFile = this.mainConfigDirectoryLocation.resolve(includedFilename);
		try (
			final Reader reader = new StringSubstitutorReader(
				new InputStreamReader(
					new BufferedInputStream(
						new FileInputStream(
							includedFile.toFile()
						)
					), StandardCharsets.UTF_8
				),
				this.stringSubstitutor
			)
		) {
			return this.yamlParser.get().load(reader);
		} catch (IOException e) {
			throw new ConfigurationParseException(
				"Failed to parse included configuration file `" + includedFilename + "` due to: " + e.getMessage() + ".",
				"Failed to parse included configuration file.", e
			);
		}
	}
}
