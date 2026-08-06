/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.utils;

import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Memory sizes (in bytes) of the base Java types, derived from the **layout of the running VM** rather than from
 * constants typed for one particular JVM configuration - see {@link VMLayout}, which reads the effective
 * `UseCompressedOops` / `UseCompressedClassPointers` / `ObjectAlignmentInBytes` settings.
 *
 * **These stay `static final` on purpose.** They are no longer compile-time constants, so javac emits a `getstatic`
 * instead of inlining a literal; HotSpot then constant-folds the load once the interface is initialized, because C2
 * trusts `static final` fields. The values are therefore adaptive at start-up and free at run time. That trust does
 * **not** extend to instance fields, so hot code must read these constants rather than calling
 * {@link VMLayout#current()} per invocation.
 *
 * **Two rules govern every number produced here.**
 *
 * 1. *Never count a shared object.* A value that is a JVM singleton, an interned instance, or a structure owned
 *    elsewhere and merely referenced belongs to its owner and to nobody else. {@link java.util.Locale},
 *    {@link java.util.Currency} and enum constants are already sized as 0 for exactly this reason; the same rule is
 *    what keeps {@link java.time.ZoneOffset} out of an {@link java.time.OffsetDateTime}'s cost.
 * 2. *When several numbers are defensible, report the larger.* A figure that reads low tells a caller a structure is
 *    cheap when it is the expensive one. Applied here to string encoding (assumed UTF-16, see
 *    {@link #computeStringSize(String)}) and to {@link VMLayout}'s fallback layout.
 *
 * Rule 1 outranks rule 2: charging a borrowed object to yourself is not caution, it is a wrong answer, because the
 * parts then stop summing to the whole.
 *
 * **Alignment is modelled.** Every heap object occupies a whole multiple of the VM's allocation granularity, so a raw
 * field sum under-reports - a 12-byte header plus one `int` costs 16 bytes, not 16 exactly by accident. Use
 * {@link #align(long)} when composing a size from parts.
 *
 * All sizes exclude whatever an object's references point at; the caller decides which of those it owns.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see VMLayout
 */
public interface MemoryMeasuringConstants {

	/**
	 * Header of a non-array object: the mark word plus the klass word. 12 bytes when class pointers are compressed
	 * (the default), 16 otherwise.
	 */
	int OBJECT_HEADER_SIZE = VMLayout.current().objectHeaderSize();
	/**
	 * Width of one object reference. 4 bytes under compressed oops (heaps below ~32 GB), 8 above - independently of
	 * whether class pointers are compressed.
	 */
	int REFERENCE_SIZE = VMLayout.current().referenceSize();
	/**
	 * Header of an array: the object header plus the 4-byte length field, padded to the allocation granularity.
	 */
	int ARRAY_BASE_SIZE = VMLayout.current().arrayHeaderSize();
	int BYTE_SIZE = 1;
	int CHAR_SIZE = 2;
	int SMALL_SIZE = 2;
	int INT_SIZE = 4;
	int LONG_SIZE = 8;
	/**
	 * Payload of a {@link java.math.BigDecimal}: `long intCompact`, `int scale`, `int precision` and the
	 * `stringCache` / `intVal` references. Excludes the `BigInteger` an over-`long` magnitude would allocate - that is
	 * a separate object whose size depends on the magnitude, added by the caller when present.
	 */
	int BIG_DECIMAL_SIZE = LONG_SIZE + 2 * INT_SIZE + 2 * REFERENCE_SIZE;
	/**
	 * Payload of a {@link java.math.BigInteger}: `int signum`, the `mag` reference and the four cached `int` fields.
	 * A rough figure for a small magnitude - the backing `int[]` is counted separately.
	 */
	int BIG_INTEGER = 5 * INT_SIZE + REFERENCE_SIZE;
	/**
	 * Payload of a {@link java.time.LocalDate}: `int year`, `byte month`, `byte day`.
	 */
	int LOCAL_DATE_SIZE = INT_SIZE + 2 * BYTE_SIZE;
	/**
	 * Payload of a {@link java.time.LocalTime}: `byte hour`, `byte minute`, `byte second`, `int nano`.
	 */
	int LOCAL_TIME_SIZE = 3 * BYTE_SIZE + INT_SIZE;
	/**
	 * Total footprint of a {@link java.time.LocalDateTime} - its own object holding two references, **plus** the
	 * {@link java.time.LocalDate} and {@link java.time.LocalTime} it owns. Unlike the two above this is a whole size
	 * rather than a payload, because the sub-objects are genuinely owned and must be counted.
	 */
	int LOCAL_DATE_TIME_SIZE = (int) (
		VMLayout.current().sizeOfObject(2L * REFERENCE_SIZE)
			+ VMLayout.current().sizeOfObject(LOCAL_DATE_SIZE)
			+ VMLayout.current().sizeOfObject(LOCAL_TIME_SIZE)
	);

	/**
	 * Rounds a byte count up to the VM's allocation granularity.
	 *
	 * @param bytes the unrounded byte count
	 * @return `bytes` rounded up to a whole number of allocation units
	 */
	static long align(long bytes) {
		return VMLayout.current().align(bytes);
	}

	/**
	 * Total footprint of a {@link String}: the `String` object (a `value` reference, the cached `hash`, the `coder`
	 * byte and the `hashIsZero` flag) plus the `byte[]` it owns.
	 *
	 * **The encoding is assumed to be UTF-16 (two bytes per char).** Since JDK 9 a string whose characters all fit
	 * Latin-1 is stored one byte per char, so this over-reports such strings by roughly a fifth. That is deliberate
	 * under rule 2: detecting the encoding would mean scanning every string on a path that runs per attribute value,
	 * and the alternative error direction is the one that misleads.
	 *
	 * @param string the string to size
	 * @return estimated footprint in bytes
	 */
	static int computeStringSize(@Nonnull String string) {
		final VMLayout layout = VMLayout.current();
		final long stringObject = layout.sizeOfObject(REFERENCE_SIZE + INT_SIZE + 2L * BYTE_SIZE);
		final long valueArray = layout.sizeOfArray(string.length(), CHAR_SIZE);
		return (int) (stringObject + valueArray);
	}

	/**
	 * Total footprint of a {@link java.util.LinkedList} and the elements it owns - the list object, one node per
	 * element (each a header plus three references), and each element's own size.
	 *
	 * @param list the list to size
	 * @return estimated footprint in bytes
	 */
	static int computeLinkedListSize(@Nonnull List<Serializable> list) {
		final VMLayout layout = VMLayout.current();
		// `size` and the `first` / `last` references, plus `modCount` inherited from AbstractList - which is easy to
		// overlook, and does move the empty-list figure from 24 to the measured 32
		final long listObject = layout.sizeOfObject(2L * INT_SIZE + 2L * REFERENCE_SIZE);
		final long nodes = list.size() * layout.sizeOfObject(3L * REFERENCE_SIZE);
		long elements = 0;
		for (final Serializable item : list) {
			elements += EvitaDataTypes.estimateSize(item);
		}
		return (int) (listObject + nodes + elements);
	}

	/**
	 * Total footprint of an object array and the elements it owns - the array (header plus one reference slot per
	 * element) plus each element's own size.
	 *
	 * @param array the array to size
	 * @return estimated footprint in bytes
	 */
	static int computeArraySize(@Nonnull Serializable[] array) {
		long size = VMLayout.current().sizeOfArray(array.length, REFERENCE_SIZE);
		for (final Serializable item : array) {
			size += EvitaDataTypes.estimateSize(item);
		}
		return (int) size;
	}

	/**
	 * Footprint of an `int` array: the array header plus four bytes per element, padded.
	 *
	 * The previous implementation charged a reference **and** an `int` for every element, over-reporting by 3x on an
	 * array type that appears throughout the index layer. A primitive array stores its values inline and holds no
	 * references at all.
	 *
	 * @param array the array to size
	 * @return estimated footprint in bytes
	 */
	static int computeArraySize(@Nonnull int[] array) {
		return (int) VMLayout.current().sizeOfArray(array.length, INT_SIZE);
	}

	/**
	 * Total footprint of a {@link java.util.HashMap} and the entries it owns - the map object, its bucket table, one
	 * `Node` per entry (header, `hash`, and the key / value / next references), and each key and value.
	 *
	 * Note that walking `entrySet()` materializes and caches the map's `EntrySet` view if nothing had asked for it
	 * yet, so measuring a pristine map adds ~16 bytes to it. Negligible and one-off, but it means a size taken
	 * immediately after this call reads slightly higher than the value returned.
	 *
	 * @param map the map to size
	 * @return estimated footprint in bytes
	 */
	static int computeHashMapSize(@Nonnull Map<? extends Serializable, ? extends Serializable> map) {
		final VMLayout layout = VMLayout.current();
		final long mapObject = layout.sizeOfObject(4L * INT_SIZE + 4L * REFERENCE_SIZE);
		// the bucket table is allocated lazily on the first put, so an empty map genuinely has none - counting a
		// phantom 16-slot table would not be caution but a wrong answer, doubling the reported size of an empty map.
		// Once populated the table is the next power of two above size / 0.75, never smaller than the default 16
		final long table = map.isEmpty() ?
			0 :
			layout.sizeOfArray(
				Math.max(16, Integer.highestOneBit(Math.max(1, (int) (map.size() / 0.75f)) - 1) << 1),
				REFERENCE_SIZE
			);
		final long nodes = map.size() * layout.sizeOfObject(INT_SIZE + 3L * REFERENCE_SIZE);
		long entries = 0;
		for (final Map.Entry<? extends Serializable, ? extends Serializable> entry : map.entrySet()) {
			entries += EvitaDataTypes.estimateSize(entry.getKey()) + EvitaDataTypes.estimateSize(entry.getValue());
		}
		return (int) (mapObject + table + nodes + entries);
	}

	/**
	 * Returns the inline width of one element of an array of the passed component type. A primitive component is
	 * stored inline at its own width; any other component is a reference slot.
	 *
	 * @param componentType the array's component type
	 * @return the element width in bytes
	 */
	static int getElementSize(Class<?> componentType) {
		if (byte.class.equals(componentType) || boolean.class.equals(componentType)) {
			return BYTE_SIZE;
		} else if (short.class.equals(componentType)) {
			return SMALL_SIZE;
		} else if (int.class.equals(componentType) || float.class.equals(componentType)) {
			return INT_SIZE;
		} else if (long.class.equals(componentType) || double.class.equals(componentType)) {
			return LONG_SIZE;
		} else if (char.class.equals(componentType)) {
			return CHAR_SIZE;
		} else {
			return REFERENCE_SIZE;
		}
	}

}
