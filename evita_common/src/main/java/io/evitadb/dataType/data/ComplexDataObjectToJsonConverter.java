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

package io.evitadb.dataType.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.TreeMap;

import static java.util.Optional.ofNullable;

/**
 * This visitor is a visitor that convert {@link ComplexDataObject} to a JSON using Jackson library.
 *
 * The type {@link Long} and {@link BigDecimal} are converted to strings. In ECMAScript the Number type is IEEE 754
 * Standard for double-precision floating-point format with max integer value of
 * Number.MAX_SAFE_INTEGER = 9007199254740991 min integer value of Number.MIN_SAFE_INTEGER = -9007199254740991. Passing
 * larger values that are possible to have in Java would make these corrupted in JavaScript.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ComplexDataObjectToJsonConverter implements DataItemVisitor {

	/**
	 * Mapper is used to build JSON tree.
	 */
	private final ObjectMapper objectMapper;
	/**
	 * Builds a stack that holds property names visited in {@link ComplexDataObject} {@link DataItem} tree.
	 */
	private final Deque<String> propertyNameStack = new ArrayDeque<>(16);
	/**
	 * Builds a stack of output JSON nodes - these nodes are used as parents for newly created nodes within the tree.
	 */
	private final Deque<JsonNode> stack = new ArrayDeque<>(16);
	/**
	 * Contains reference to the tree node.
	 */
	@Getter
	private JsonNode rootNode;

	@Override
	public void visit(@Nonnull DataItemArray arrayItem) {
		final ArrayNode newArrayNode = this.objectMapper.getNodeFactory().arrayNode(arrayItem.children().length);

		if (this.rootNode == null) {
			// if we have no root create it
			this.rootNode = newArrayNode;
			this.stack.push(this.rootNode);
		} else {
			final JsonNode stackNode = this.stack.peek();
			// if its "map" node
			// create appropriate node type as a children in it
			if (stackNode instanceof ObjectNode objectNode) {
				objectNode.putIfAbsent(this.propertyNameStack.peek(), newArrayNode);
				this.stack.push(newArrayNode);
			} else if (stackNode instanceof ArrayNode arrayNode) {
				// if it's "array" node
				// create appropriate node type as a children in it
				arrayNode.add(newArrayNode);
				this.stack.push(newArrayNode);
			} else {
				// otherwise throw exception (this should never occur)
				throw new IllegalStateException("Unexpected node on stack: " + stackNode);
			}
		}

		arrayItem.forEach(
			// visit each child in the array
			(dataItem, hasNext) -> {
				if (dataItem == null) {
					writeNull();
				} else {
					dataItem.accept(this);
				}
			}
		);

		// pop current node from the stack on leave
		this.stack.pop();
	}

	@Override
	public void visit(@Nonnull DataItemMap mapItem) {
		if (this.rootNode == null) {
			// if we have no root create it
			this.rootNode = this.objectMapper.createObjectNode();
			this.stack.push(this.rootNode);
		} else {
			// otherwise pick up relative parent in the stack
			final JsonNode stackNode = this.stack.peek();
			if (stackNode instanceof ObjectNode objectNode) {
				// if its "map" node
				// create appropriate node type as a children in it
				this.stack.push(objectNode.putObject(this.propertyNameStack.peek()));
			} else if (stackNode instanceof ArrayNode arrayNode) {
				// if it's "array" node
				// create appropriate node type as a children in it
				this.stack.push(arrayNode.addObject());
			} else {
				// otherwise throw exception (this should never occur)
				throw new IllegalStateException("Unexpected node on stack: " + stackNode);
			}
		}

		// now iterate all properties in the map
		mapItem.forEach((propertyName, dataItem, hasNext) -> {
			// push property name to the stack to be used by children
			this.propertyNameStack.push(propertyName);
			// visit the children node
			if (dataItem == null) {
				writeNull();
			} else {
				dataItem.accept(this);
			}
			// pop the property name from the stack
			this.propertyNameStack.pop();
		});

		// pop current node from the stack on leave
		this.stack.pop();
	}

	@Override
	public void visit(@Nonnull DataItemValue valueItem) {
		if (this.rootNode == null) {
			throw new IllegalStateException("Value item is not allowed as the root item!");
		}
		final JsonNode theNode = this.stack.peek();
		if (theNode instanceof ObjectNode objectNode) {
			// if its "map" node
			// create appropriate node type as a children in it
			final String propertyName = this.propertyNameStack.peek();
			final Serializable object = valueItem.value();
			if (object instanceof Short s) {
				objectNode.put(propertyName, s);
			} else if (object instanceof Byte b) {
				objectNode.put(propertyName, b);
			} else if (object instanceof Integer i) {
				objectNode.put(propertyName, i);
			} else if (object instanceof Long l) {
				objectNode.put(propertyName, l.toString());
			} else if (object instanceof String s) {
				objectNode.put(propertyName, s);
			} else if (object instanceof BigDecimal bd) {
				objectNode.put(propertyName, bd.toPlainString());
			} else if (object instanceof Boolean b) {
				objectNode.put(propertyName, b);
			} else if (object instanceof Character c) {
				objectNode.put(propertyName, c.toString());
			} else if (object instanceof Locale locale) {
				objectNode.put(propertyName, locale.toLanguageTag());
			} else if (object == null) {
				objectNode.putNull(propertyName);
			} else {
				objectNode.put(propertyName, EvitaDataTypes.formatValue(object));
			}
		} else if (theNode instanceof ArrayNode arrayNode) {
			// if it's "array" node
			// create appropriate node type as a children in it
			final Serializable object = valueItem.value();
			if (object instanceof Short s) {
				arrayNode.add(s);
			} else if (object instanceof Byte b) {
				arrayNode.add(b);
			} else if (object instanceof Integer i) {
				arrayNode.add(i);
			} else if (object instanceof Long l) {
				arrayNode.add(l.toString());
			} else if (object instanceof String s) {
				arrayNode.add(s);
			} else if (object instanceof BigDecimal bd) {
				arrayNode.add(bd.toPlainString());
			} else if (object instanceof Boolean b) {
				arrayNode.add(b);
			} else if (object instanceof Character c) {
				arrayNode.add(c.toString());
			} else if (object instanceof Locale locale) {
				arrayNode.add(locale.toLanguageTag());
			} else if (object == null) {
				arrayNode.addNull();
			} else {
				arrayNode.add(EvitaDataTypes.formatValue(object));
			}
		} else {
			// otherwise throw exception (this should never occur)
			throw new IllegalStateException("Unexpected type of node on stack: " + ofNullable(theNode).map(JsonNode::getClass).orElse(null));
		}
	}

	/**
	 * Returns output JSON as string in a reproducible format (all fields are sorted).
	 * Method is expected to be used in tests only.
	 */
	public String getJsonAsString() throws JsonProcessingException {
		return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.rootNode);
	}

	private void writeNull() {
		if (this.rootNode == null) {
			// if we have no root create it
			this.rootNode = this.objectMapper.createObjectNode();
			this.stack.push(this.rootNode);
		}
		final JsonNode theNode = this.stack.peek();
		if (theNode instanceof ObjectNode objectNode) {
			// if its "map" node
			// create appropriate node type as a children in it
			final String propertyName = this.propertyNameStack.peek();
			objectNode.putNull(propertyName);
		} else if (theNode instanceof ArrayNode arrayNode) {
			// if it's "array" node
			arrayNode.addNull();
		} else {
			// otherwise throw exception (this should never occur)
			throw new IllegalStateException("Unexpected type of node on stack: " + ofNullable(theNode).map(JsonNode::getClass).orElse(null));
		}
	}

	/**
	 * This factory is used in tests to get always the same output for comparison.
	 */
	public static class SortingNodeFactory extends JsonNodeFactory {
		@Serial private static final long serialVersionUID = -840940331957056894L;

		@Override
		public ObjectNode objectNode() {
			return new ObjectNode(this, new TreeMap<>());
		}

	}

}
