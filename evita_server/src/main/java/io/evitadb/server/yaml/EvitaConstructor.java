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

import org.apache.commons.text.StringSubstitutor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom YAML constructor with extensions specific to evitaDB configuration. For example, it supports including
 * other YAML files.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EvitaConstructor extends SafeConstructor {

	/**
	 * Creates new instance of {@link EvitaConstructor}.
	 *
	 * @param yamlParser instance of YAML parser yet to be created. The instance should be the same as used with this constructor
	 * @param stringSubstitutor instance of string substitutor for resolving variables in included YAML files
	 * @param configDirLocation location of main configuration file to be used as a base for relative paths in included YAML files
	 */
	public EvitaConstructor(@Nonnull AtomicReference<Yaml> yamlParser,
	                        @Nonnull StringSubstitutor stringSubstitutor,
	                        @Nonnull Path configDirLocation) {
		super(new LoaderOptions());
		this.yamlConstructors.put(new Tag("!include"), new IncludeConstruct(yamlParser, stringSubstitutor, configDirLocation));
	}
}
