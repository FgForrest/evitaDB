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
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * This class is used to handle unknown properties in YAML configuration files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class UnknownPropertyProblemHandler extends DeserializationProblemHandler implements Serializable {
	@Serial private static final long serialVersionUID = -8541510916893219751L;
	private static final Pattern REPLACE_PATTERN = Pattern.compile("/");
	@Nullable @Setter private String prefix;

	@Override
	public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser jp, JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws IOException {
		final String propertyPath = ctxt.getParser().getParsingContext().pathAsPointer().toString();
		final String path = REPLACE_PATTERN.matcher((this.prefix == null ? "" : this.prefix) + propertyPath + "/" + propertyName).replaceAll(".");
		final boolean startsWithDot = path.charAt(0) == '.';
		final String finalPath = startsWithDot ? path.substring(1) : path;
		log.warn("Unsupported property '{}' encountered in YAML configuration.", finalPath);
		return true; // indicate that the problem is handled
	}

	 /**
	 * Clears the current prefix used for constructing property paths.
	 *
	 * This method sets the prefix field to null, effectively removing
	 * any previously set path prefix that may be used for handling unknown
	 * properties in a YAML configuration file.
	 */
	public void clearPrefix() {
		this.prefix = null;
	}
}
