package io.evitadb.roaringbitmap;

/**
 * The running JVM's object layout, as needed to price a bitmap's heap footprint.
 *
 * This module deliberately depends on nothing but `jsr305`, so it cannot reach evitaDB's own layout
 * detection. The four numbers are therefore handed in by the caller rather than probed here — evitaDB
 * builds this from `io.evitadb.utils.VMLayout`, which reads the effective `UseCompressedOops`,
 * `UseCompressedClassPointers` and `ObjectAlignmentInBytes` flags. Keeping the detection on that side and
 * the arithmetic on this one is what lets {@link Container} price its backing array by **capacity**, which
 * is the only place capacity is visible, without dragging a dependency into a vendored module.
 *
 * The three axes are independent: a large heap widens references to 8 bytes while leaving class pointers
 * compressed, so the object header stays 12. Do not derive one from another.
 *
 * @param referenceSize    bytes an object reference occupies (4 compressed, 8 otherwise)
 * @param objectHeaderSize bytes of header on a non-array object (12 with compressed class pointers, else 16)
 * @param arrayHeaderSize  bytes of header on an array, including its length word and any padding
 * @param objectAlignment  boundary every object's total size is rounded up to (normally 8)
 */
public record HeapLayout(int referenceSize, int objectHeaderSize, int arrayHeaderSize, int objectAlignment) {

	/**
	 * Rounds a byte count up to the VM's object alignment boundary.
	 *
	 * @param bytes the unaligned byte count
	 * @return the same count rounded up to the next multiple of {@link #objectAlignment()}
	 */
	public long align(long bytes) {
		return (bytes + this.objectAlignment - 1) & -(long) this.objectAlignment;
	}

	/**
	 * Sizes a plain object: its header plus its own fields, aligned.
	 *
	 * @param payloadBytes total bytes of the object's own fields, references counted as
	 *                     {@link #referenceSize()} each
	 * @return the object's heap size in bytes
	 */
	public long sizeOfObject(long payloadBytes) {
		return align(this.objectHeaderSize + payloadBytes);
	}

	/**
	 * Sizes an array at its **allocated length**, not at how much of it is in use.
	 *
	 * @param length      the array's `length`, i.e. its capacity
	 * @param elementSize bytes per element
	 * @return the array's heap size in bytes
	 */
	public long sizeOfArray(int length, int elementSize) {
		return align((long) this.arrayHeaderSize + (long) length * elementSize);
	}
}
