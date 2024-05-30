/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.dataType;

import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.dataType.data.DataItemVisitor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;

import static java.util.Optional.ofNullable;

/**
 * This class is used for (de)serialization of complex custom POJO classes in some generic form that doesn't change in
 * time. Client code will use {@link ComplexDataObjectConverter} to serialize and deserialize objects of this type to their
 * respective POJO instances.
 *
 * @param root Contains the root data item of the object - may be {@link DataItemArray} or {@link DataItemMap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@Immutable
@Slf4j
public record ComplexDataObject(
	@Nonnull DataItem root
) implements Serializable {
	@Serial private static final long serialVersionUID = -8087905744932894477L;

	public ComplexDataObject(@Nonnull DataItem root) {
		Assert.isTrue(!(root instanceof DataItemValue), "Root item cannot be a value item!");
		this.root = root;
	}

	/**
	 * Returns the root item of the object.
	 */
	public DataItem root() {
		return root;
	}

	/**
	 * Returns true if no data are present in root container.
	 */
	public boolean isEmpty() {
		return root.isEmpty() && !(root instanceof DataItemArray);
	}

	/**
	 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
	 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
	 */
	public int estimateSize() {
		final SizeEstimatingDataItemVisitor visitor = new SizeEstimatingDataItemVisitor(MemoryMeasuringConstants.OBJECT_HEADER_SIZE);
		accept(visitor);
		return visitor.getEstimatedSize();
	}

	/**
	 * Method traverses entire tree using passed visitor.
	 */
	public void accept(@Nonnull DataItemVisitor visitor) {
		root.accept(visitor);
	}

	@Override
	public int hashCode() {
		return root.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ComplexDataObject that = (ComplexDataObject) o;

		return root.equals(that.root);
	}

	@Override
	public String toString() {
		final ToStringDataItemVisitor visitor = new ToStringDataItemVisitor(3);
		accept(visitor);
		return visitor.getAsString();
	}

	/**
	 * This visitor traverses entire data item tree and collect information about aggregate memory consumption estimate.
	 */
	@AllArgsConstructor
	private static class SizeEstimatingDataItemVisitor implements DataItemVisitor {
		/**
		 * Contains the aggregated memory consumption estimate.
		 */
		@Getter private int estimatedSize;

		@Override
		public void visit(@Nonnull DataItemArray arrayItem) {
			estimatedSize += arrayItem.estimateSize();
			arrayItem.forEach((dataItem, hasNext) -> ofNullable(dataItem).ifPresent(it -> it.accept(this)));
		}

		@Override
		public void visit(@Nonnull DataItemMap mapItem) {
			estimatedSize += mapItem.estimateSize();
			mapItem.forEach((propertyName, dataItem, hasNext) -> ofNullable(dataItem).ifPresent(it -> it.accept(this)));
		}

		@Override
		public void visit(@Nonnull DataItemValue valueItem) {
			estimatedSize += valueItem.estimateSize();
		}
	}

	/**
	 * This visitor traverses entire data item tree and creates descriptive String with all contents of the data tree.
	 */
	@RequiredArgsConstructor
	private static class ToStringDataItemVisitor implements DataItemVisitor {
		/**
		 * Contains the collected String.
		 */
		private final StringBuilder asString = new StringBuilder();
		/**
		 * Number of spaces used for indentation on each hierarchy level.
		 */
		private final int indentation;
		/**
		 * Contains current number of spaces used for indentation (internal data-structure).
		 */
		private int current;

		public String getAsString() {
			return asString.toString();
		}

		@Override
		public void visit(@Nonnull DataItemArray arrayItem) {
			if (arrayItem.isEmpty()) {
				asString.append("[]");
			} else {
				asString.append("[\n");
				current += indentation;
				arrayItem.forEach((dataItem, hasNext) -> {
					asString.append(" ".repeat(current));
					if (dataItem == null) {
						asString.append("<NULL>");
					} else {
						dataItem.accept(this);
					}
					if (hasNext) {
						asString.append(",");
					}
					asString.append("\n");
				});
				current -= indentation;
				asString.append(" ".repeat(current)).append("]");
			}
		}

		@Override
		public void visit(@Nonnull DataItemMap mapItem) {
			if (mapItem.isEmpty()) {
				asString.append("{}");
			} else {
				asString.append("{\n");
				current += indentation;
				mapItem.forEachSorted((propertyName, dataItem, hasNext) -> {
					asString.append(" ".repeat(current)).append(EvitaDataTypes.formatValue(propertyName)).append(": ");
					if (dataItem == null) {
						asString.append("<NULL>");
					} else {
						dataItem.accept(this);
					}
					if (hasNext) {
						asString.append(",");
					}
					asString.append("\n");
				});
				current -= indentation;
				asString.append(" ".repeat(current)).append("}");
			}
		}

		@Override
		public void visit(@Nonnull DataItemValue valueItem) {
			final Serializable value = valueItem.value();
			asString.append(value == null ? "<NULL>" : EvitaDataTypes.formatValue(value));
		}
	}
}
