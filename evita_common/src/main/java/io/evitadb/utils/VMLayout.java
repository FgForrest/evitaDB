/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import com.sun.management.HotSpotDiagnosticMXBean;

import javax.annotation.Nonnull;
import java.lang.management.ManagementFactory;

/**
 * Describes the object layout of the running HotSpot VM, so heap estimates can be arithmetic on the *actual* layout
 * instead of the one that happened to be true when a constant was typed.
 *
 * **Three independent axes, not one flag.** The common shorthand "compressed oops on/off" is wrong: pointer
 * compression and class-pointer compression are separate switches, and they genuinely disagree in ordinary
 * deployments. Measured on OpenJDK 21:
 *
 * | `-Xmx`  | `UseCompressedOops` | `UseCompressedClassPointers` | resulting header |
 * |---------|---------------------|------------------------------|------------------|
 * | 14 GB   | `true` (ergonomic)  | `true`                       | 12 B             |
 * | 40 GB   | `false`             | `true`                       | 12 B             |
 *
 * Above the ~32 GB threshold references widen to 8 bytes while the klass word stays compressed at 4 - so the header
 * remains 12 bytes and only {@link #referenceSize()} changes. For an in-memory database a 40 GB heap is an ordinary
 * configuration, so this asymmetric case is the normal one to get right rather than a curiosity.
 *
 * **Detection.** `UseCompressedOops` is set by VM *ergonomics*, not by the command line, so
 * {@link java.lang.management.RuntimeMXBean#getInputArguments()} cannot see it - it reports only what the user typed.
 * {@link HotSpotDiagnosticMXBean} reads the effective value and is the only supported way to obtain it.
 *
 * **When detection fails the layout falls back to {@link #UNCOMPRESSED}, deliberately.** That is the larger of the two
 * on every axis (8-byte references, 16-byte header), so an unknown VM over-reports rather than under-reports. An
 * estimate that reads low tells an operator a structure is fine when it is the one eating the heap; one that reads
 * high costs at most some unnecessary caution.
 *
 * **This class never accounts for shared objects** - it only sizes the *shape* of an individual object. Deciding which
 * reachable objects an owner may charge to itself is the caller's job, and the rule there is that a borrowed instance
 * (a JVM singleton, an interned value, a structure maintained elsewhere) belongs to its owner and to nobody else.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see MemoryMeasuringConstants
 */
public final class VMLayout {

	/**
	 * System property forcing {@link #current()} to a fixed layout, bypassing detection entirely. Accepts
	 * `compressed` and `uncompressed`. Intended for reproducing a production layout locally - **not** for tests,
	 * which should pass an explicit {@link VMLayout} instead of mutating global state.
	 */
	public static final String LAYOUT_PROPERTY = "evitadb.memory.layout";
	/**
	 * The layout of a VM using compressed references and compressed class pointers - the default below ~32 GB of heap,
	 * and by far the most common one in production.
	 */
	public static final VMLayout COMPRESSED = new VMLayout(4, 12, 16, 8);
	/**
	 * The layout of a VM using neither compressed references nor compressed class pointers. Larger on every axis than
	 * {@link #COMPRESSED}, which is why it doubles as the fallback when detection fails.
	 */
	public static final VMLayout UNCOMPRESSED = new VMLayout(8, 16, 24, 8);

	private final int referenceSize;
	private final int objectHeaderSize;
	private final int arrayHeaderSize;
	private final int objectAlignment;

	/**
	 * Holder deferring detection until the layout is first needed, so merely loading this class never touches the
	 * management beans.
	 */
	private static final class Holder {
		private static final VMLayout DETECTED = resolve();
	}

	private VMLayout(int referenceSize, int objectHeaderSize, int arrayHeaderSize, int objectAlignment) {
		this.referenceSize = referenceSize;
		this.objectHeaderSize = objectHeaderSize;
		this.arrayHeaderSize = arrayHeaderSize;
		this.objectAlignment = objectAlignment;
	}

	/**
	 * Returns the layout of the running VM, detected once on first use.
	 *
	 * Production code that has no particular layout in mind should use this. **Tests must not** - they should pass one
	 * of the explicit layouts ({@link #COMPRESSED}, {@link #UNCOMPRESSED}, {@link #of}) so their assertions do not
	 * depend on the heap size of whichever fork happens to run them.
	 *
	 * @return the detected (or overridden) layout of this VM
	 */
	@Nonnull
	public static VMLayout current() {
		return Holder.DETECTED;
	}

	/**
	 * Builds an arbitrary layout, for tests and for modelling a VM other than the one running.
	 *
	 * @param referenceSize    width of an object reference in bytes (4 when references are compressed, else 8)
	 * @param objectHeaderSize header of a non-array object in bytes (mark word plus the klass word)
	 * @param arrayHeaderSize  header of an array in bytes (object header plus the 4-byte length, padded)
	 * @param objectAlignment  allocation granularity in bytes, `-XX:ObjectAlignmentInBytes`
	 * @return the described layout
	 */
	@Nonnull
	public static VMLayout of(int referenceSize, int objectHeaderSize, int arrayHeaderSize, int objectAlignment) {
		Assert.isTrue(
			referenceSize == 4 || referenceSize == 8,
			"Reference size must be 4 or 8, but was " + referenceSize + "!"
		);
		Assert.isTrue(objectHeaderSize > 0, "Object header size must be positive, but was " + objectHeaderSize + "!");
		Assert.isTrue(arrayHeaderSize >= objectHeaderSize, "Array header cannot be smaller than the object header!");
		Assert.isTrue(
			objectAlignment > 0 && Integer.bitCount(objectAlignment) == 1,
			"Object alignment must be a positive power of two, but was " + objectAlignment + "!"
		);
		return new VMLayout(referenceSize, objectHeaderSize, arrayHeaderSize, objectAlignment);
	}

	/**
	 * @return width of an object reference in bytes - 4 when `UseCompressedOops` is in effect, 8 otherwise
	 */
	public int referenceSize() {
		return this.referenceSize;
	}

	/**
	 * @return header of a non-array object in bytes - 12 when `UseCompressedClassPointers` is in effect, 16 otherwise
	 */
	public int objectHeaderSize() {
		return this.objectHeaderSize;
	}

	/**
	 * @return header of an array in bytes, the object header plus the 4-byte length field, padded to the alignment
	 */
	public int arrayHeaderSize() {
		return this.arrayHeaderSize;
	}

	/**
	 * @return allocation granularity in bytes; every object occupies a multiple of it
	 */
	public int objectAlignment() {
		return this.objectAlignment;
	}

	/**
	 * Rounds a byte count up to the VM's allocation granularity. Every heap object occupies a whole number of
	 * alignment units, so an unrounded field-sum systematically under-reports - a 12-byte header plus a single `int`
	 * is 16 bytes of heap, not 20 minus nothing.
	 *
	 * @param bytes the unrounded byte count
	 * @return `bytes` rounded up to the next multiple of {@link #objectAlignment()}
	 */
	public long align(long bytes) {
		final int alignment = this.objectAlignment;
		return (bytes + alignment - 1) & -(long) alignment;
	}

	/**
	 * Size of a non-array object carrying `payloadBytes` of fields, header and alignment padding included.
	 *
	 * @param payloadBytes summed width of the object's own fields, references counted as {@link #referenceSize()}
	 * @return the object's heap footprint in bytes, excluding anything its references point at
	 */
	public long sizeOfObject(long payloadBytes) {
		return align(this.objectHeaderSize + payloadBytes);
	}

	/**
	 * Size of an array of `length` elements of `elementSize` bytes each, header and alignment padding included.
	 * For an array of references pass {@link #referenceSize()} as the element size; this counts the reference slots
	 * only, never the objects they point at.
	 *
	 * @param length      number of elements
	 * @param elementSize width of a single element in bytes
	 * @return the array's heap footprint in bytes, excluding anything its elements point at
	 */
	public long sizeOfArray(int length, int elementSize) {
		return align((long) this.arrayHeaderSize + (long) length * elementSize);
	}

	/**
	 * Two layouts are equal when they describe the same object shape. Value-based rather than identity-based on
	 * purpose: {@link #detect()} builds a fresh instance from the VM's flags, so a detected layout must compare equal
	 * to the {@link #COMPRESSED} / {@link #UNCOMPRESSED} constant describing the same shape.
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof final VMLayout that)) {
			return false;
		}
		return this.referenceSize == that.referenceSize &&
			this.objectHeaderSize == that.objectHeaderSize &&
			this.arrayHeaderSize == that.arrayHeaderSize &&
			this.objectAlignment == that.objectAlignment;
	}

	@Override
	public int hashCode() {
		// computed by hand rather than through Objects.hash, which would box all four primitives into an array
		int result = Integer.hashCode(this.referenceSize);
		result = 31 * result + Integer.hashCode(this.objectHeaderSize);
		result = 31 * result + Integer.hashCode(this.arrayHeaderSize);
		result = 31 * result + Integer.hashCode(this.objectAlignment);
		return result;
	}

	@Override
	public String toString() {
		final StringBuilder result = new StringBuilder(96);
		return result
			.append("VMLayout{reference=").append(this.referenceSize)
			.append("B, objectHeader=").append(this.objectHeaderSize)
			.append("B, arrayHeader=").append(this.arrayHeaderSize)
			.append("B, alignment=").append(this.objectAlignment)
			.append("B}")
			.toString();
	}

	/**
	 * Resolves the layout once: an explicit {@link #LAYOUT_PROPERTY} wins, otherwise the VM is interrogated, and any
	 * failure falls back to {@link #UNCOMPRESSED} for the reason given in the class javadoc.
	 */
	@Nonnull
	private static VMLayout resolve() {
		final String forced = System.getProperty(LAYOUT_PROPERTY);
		if (forced != null) {
			if ("compressed".equalsIgnoreCase(forced)) {
				return COMPRESSED;
			} else if ("uncompressed".equalsIgnoreCase(forced)) {
				return UNCOMPRESSED;
			}
			throw new IllegalArgumentException(
				"System property `" + LAYOUT_PROPERTY + "` must be `compressed` or `uncompressed`, but was `" +
					forced + "`!"
			);
		}
		return detect();
	}

	/**
	 * Interrogates the running VM through {@link HotSpotDiagnosticMXBean}.
	 *
	 * `Throwable` is caught rather than a narrower type on purpose: the bean is absent on a non-HotSpot VM
	 * (`IllegalArgumentException` / `null`) and its very class is absent from a `jlink` image built without
	 * `jdk.management` (`NoClassDefFoundError`, an `Error`). Neither is a condition worth failing a database
	 * start-up over - both mean "layout unknown", which the fallback already answers safely.
	 */
	@Nonnull
	private static VMLayout detect() {
		try {
			final HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
			if (bean == null) {
				return UNCOMPRESSED;
			}
			final boolean compressedOops = readFlag(bean, "UseCompressedOops");
			final boolean compressedKlass = readFlag(bean, "UseCompressedClassPointers");
			final int alignment = readAlignment(bean);
			// the two axes are independent - a 40 GB heap has 8-byte references and a still-compressed klass word
			final int referenceSize = compressedOops ? 4 : 8;
			final int objectHeaderSize = compressedKlass ? 12 : 16;
			// the length field is 4 bytes on top of the header, then the whole thing is padded to the alignment
			final int arrayHeaderSize = (int) (((objectHeaderSize + 4L) + alignment - 1) & -(long) alignment);
			return new VMLayout(referenceSize, objectHeaderSize, arrayHeaderSize, alignment);
		} catch (Throwable ex) {
			return UNCOMPRESSED;
		}
	}

	/**
	 * Reads a boolean VM flag, treating an absent flag as `false` - i.e. as the larger, uncompressed layout.
	 */
	private static boolean readFlag(@Nonnull HotSpotDiagnosticMXBean bean, @Nonnull String flag) {
		try {
			return Boolean.parseBoolean(bean.getVMOption(flag).getValue());
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/**
	 * Reads `-XX:ObjectAlignmentInBytes`, defaulting to the universal 8 when the flag is unavailable.
	 */
	private static int readAlignment(@Nonnull HotSpotDiagnosticMXBean bean) {
		try {
			// NumberFormatException needs no separate arm - it is an IllegalArgumentException, as is the bean's own
			// "no such flag" signal, and both mean the same thing here: the alignment could not be read
			return Integer.parseInt(bean.getVMOption("ObjectAlignmentInBytes").getValue());
		} catch (IllegalArgumentException ex) {
			return 8;
		}
	}

}
