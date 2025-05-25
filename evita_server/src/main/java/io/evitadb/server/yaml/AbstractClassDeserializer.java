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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This implementation of Jackson {@link JsonDeserializer} allows instantiation and deserialization of abstract class
 * concrete implementations by the key used configuration map.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
public class AbstractClassDeserializer<T> extends StdDeserializer<T> {
	@Serial private static final long serialVersionUID = -5553513445246812598L;
	/**
	 * Simple registry that keeps key string mapping to concrete classes.
	 */
	private final Map<String, Class<? extends T>> registry = new HashMap<>(16);
	/**
	 * Unknown property handler to initialize path.
	 */
	private final UnknownPropertyProblemHandler unknownPropertyProblemHandler;

	/**
	 * Initializes deserializer with abstract class information.
	 */
	public AbstractClassDeserializer(
		@Nonnull Class<T> abstractClass,
		@Nullable UnknownPropertyProblemHandler unknownPropertyProblemHandler
	) {
		super(abstractClass);
		this.unknownPropertyProblemHandler = unknownPropertyProblemHandler;
	}

	/**
	 * Registers new concrete class for the specific `keyValue`.
	 */
	public void registerConcreteClass(String keyValue, Class<? extends T> concreteClass) {
		Assert.isTrue(this._valueClass.isAssignableFrom(concreteClass), "The class `" + concreteClass + "` must implement `" + this._valueClass + "`.");
		this.registry.put(keyValue, concreteClass);
	}

	@Nullable
	@Override
	public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		final ObjectMapper mapper = (ObjectMapper) p.getCodec();
		final ObjectNode root = mapper.readTree(p);

		Class<? extends T> concreteClass = null;
		for (Entry<String, Class<? extends T>> entry : this.registry.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(p.getParsingContext().getCurrentName())) {
				concreteClass = entry.getValue();
				break;
			}
		}

		if (concreteClass == null) {
			return null;
		}

		try {
			if (this.unknownPropertyProblemHandler != null) {
				this.unknownPropertyProblemHandler.setPrefix(
					ctxt.getParser().getParsingContext().pathAsPointer().toString()
				);
			}
			return mapper.treeToValue(root, concreteClass);
		} finally {
			if (this.unknownPropertyProblemHandler != null) {
				this.unknownPropertyProblemHandler.clearPrefix();
			}
		}
	}

}
