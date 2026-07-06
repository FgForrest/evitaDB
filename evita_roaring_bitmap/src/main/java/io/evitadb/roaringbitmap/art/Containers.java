package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.ArrayContainer;
import io.evitadb.roaringbitmap.BitmapContainer;
import io.evitadb.roaringbitmap.Container;
import io.evitadb.roaringbitmap.RunContainer;

import javax.annotation.Nonnull;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flat store holding every {@link Container} referenced by an {@link Art} leaf, addressed by a packed
 * `long` container index. Because a single JVM array cannot span the up to 2^48 possible keys, the
 * store is two-level: a list of second-level {@code Container[]} arrays, each capped just below the
 * JVM array-length limit. The high 32 bits of a container index select the second-level array and the
 * low 32 bits the offset within it.
 *
 * Together with {@link Art} this backs {@link io.evitadb.roaringbitmap.longlong.HighLowContainer}.
 */
public class Containers {

	/**
	 * First level: the list of second-level container arrays forming the flat store.
	 */
	@Nonnull private List<Container[]> containerArrays = new ArrayList<>(0);
	/**
	 * Number of non-null containers currently held.
	 */
	private long containerSize = 0;
	/**
	 * Index into {@link #containerArrays} of the array being appended to; -1 before the first insert.
	 */
	private int firstLevelIdx = -1;
	/**
	 * Offset within the current second-level array holding the most recently appended container.
	 */
	private int secondLevelIdx = 0;
	/**
	 * Largest array length the JVM reliably allows; second-level arrays never grow past this.
	 */
	private static final int MAX_JVM_ARRAY_LENGTH = Integer.MAX_VALUE - 8;
	/**
	 * One below {@link #MAX_JVM_ARRAY_LENGTH}; appends roll over to a new array before crossing it.
	 */
	private static final int MAX_JVM_ARRAY_OFFSET = MAX_JVM_ARRAY_LENGTH - 1;
	/**
	 * Serialization tag: the slot held no container.
	 */
	private static final byte NULL_MARK = 0;
	/**
	 * Serialization tag: a serialized container follows.
	 */
	private static final byte NOT_NULL_MARK = 1;
	/**
	 * Serialization tag reserved for a trimmed second-level array (not yet emitted).
	 */
	private static final byte TRIMMED_MARK = -1;
	/**
	 * Serialization tag marking a second-level array that was not trimmed.
	 */
	private static final byte NOT_TRIMMED_MARK = -2;

	/**
	 * Constructor
	 */
	public Containers() {
		reset();
	}

	@Override
	@Nonnull
	@SuppressWarnings("CloneDoesntCallSuperClone") // deep copy; super.clone() would alias containers
	public Containers clone() {
		final Containers containers = new Containers();
		containers.containerArrays = new ArrayList<>(this.containerArrays.size());
		for (final Container[] array : this.containerArrays) {
			final Container[] values = Arrays.copyOf(array, array.length);
			containers.containerArrays.add(values);
			for (int i = 0; i < values.length; i++) {
				if (values[i] != null) {
					values[i] = values[i].clone();
				}
			}
		}
		containers.containerSize = this.containerSize;
		containers.firstLevelIdx = this.firstLevelIdx;
		containers.secondLevelIdx = this.secondLevelIdx;
		return containers;
	}

	private void reset() {
		this.containerSize = 0;
		this.firstLevelIdx = -1;
		this.secondLevelIdx = 0;
	}

	/**
	 * remove the container index Container
	 *
	 * @param containerIdx the container index
	 */
	public void remove(long containerIdx) {
		final int firstDimIdx = (int) (containerIdx >>> 32);
		final int secondDimIdx = (int) containerIdx;
		this.containerArrays.get(firstDimIdx)[secondDimIdx] = null;
		this.containerSize--;
	}

	/**
	 * Resolves a packed container index — first-level array index in the high 32 bits, second-level
	 * offset in the low 32 bits — to the container stored there.
	 *
	 * @param idx a container index produced by {@link #addContainer}
	 * @return the container stored at that index
	 */
	@Nonnull
	public Container getContainer(long idx) {
		// split the idx into two part
		final int firstDimIdx = (int) (idx >>> 32);
		final int secondDimIdx = (int) idx;
		final Container[] containers = this.containerArrays.get(firstDimIdx);
		return containers[secondDimIdx];
	}

	/**
	 * Appends a container to the store, rolling over to a fresh second-level array before the current
	 * one would reach the JVM array-length limit.
	 *
	 * @param container the container to store
	 * @return the packed container index now referencing it (see {@link #getContainer})
	 */
	public long addContainer(@Nonnull Container container) {
		if (this.secondLevelIdx + 1 == MAX_JVM_ARRAY_OFFSET || this.firstLevelIdx == -1) {
			this.containerArrays.add(new Container[1]);
			this.firstLevelIdx++;
			this.secondLevelIdx = 0;
		} else {
			this.secondLevelIdx++;
		}
		final int firstDimIdx = this.firstLevelIdx;
		final int secondDimIdx = this.secondLevelIdx;
		grow(secondDimIdx + 1, this.firstLevelIdx);
		this.containerArrays.get(firstDimIdx)[secondDimIdx] = container;
		this.containerSize++;
		return toContainerIdx(this.firstLevelIdx, this.secondLevelIdx);
	}

	/**
	 * a iterator of the Containers
	 *
	 * @return a iterator
	 */
	@Nonnull
	public ContainerIterator iterator() {
		return new ContainerIterator(this);
	}

	/**
	 * replace the container index one with a fresh Container
	 *
	 * @param containerIdx   the container index to replace
	 * @param freshContainer the fresh one
	 */
	public void replace(long containerIdx, @Nonnull Container freshContainer) {
		final int firstDimIdx = (int) (containerIdx >>> 32);
		final int secondDimIdx = (int) containerIdx;
		this.containerArrays.get(firstDimIdx)[secondDimIdx] = freshContainer;
	}

	/**
	 * replace with a fresh Container
	 *
	 * @param firstLevelIdx  the first level array index
	 * @param secondLevelIdx the second level array index
	 * @param freshContainer a fresh container
	 */
	public void replace(int firstLevelIdx, int secondLevelIdx, @Nonnull Container freshContainer) {
		this.containerArrays.get(firstLevelIdx)[secondLevelIdx] = freshContainer;
	}

	/**
	 * the number of all the holding containers
	 *
	 * @return the container number
	 */
	public long getContainerSize() {
		return this.containerSize;
	}

	/**
	 * @return the backing first-level list of second-level container arrays.
	 */
	@Nonnull
	List<Container[]> getContainerArrays() {
		return this.containerArrays;
	}

	/**
	 * Packs a first-level array index and second-level offset into a single container index.
	 *
	 * @param firstLevelIdx  index of the second-level array within {@link #containerArrays}
	 * @param secondLevelIdx offset within that array
	 * @return the packed container index
	 */
	static long toContainerIdx(int firstLevelIdx, int secondLevelIdx) {
		final long firstLevelIdxL = firstLevelIdx;
		return firstLevelIdxL << 32 | secondLevelIdx;
	}

	/**
	 * increases the capacity to ensure that it can hold at least the number of elements specified by
	 * the minimum capacity argument.
	 *
	 * @param minCapacity the desired minimum capacity
	 */
	private void grow(int minCapacity, int firstLevelIdx) {
		final Container[] elementData = this.containerArrays.get(firstLevelIdx);
		final int oldCapacity = elementData.length;
		if (minCapacity - oldCapacity <= 0) {
			return;
		}
		// overflow-conscious code
		int newCapacity = oldCapacity + (oldCapacity >> 1);
		if (newCapacity - minCapacity < 0) {
			newCapacity = minCapacity;
		}
		if (newCapacity - MAX_JVM_ARRAY_LENGTH > 0) {
			newCapacity = hugeCapacity(minCapacity);
		}
		// minCapacity is usually close to size, so this is a win:
		final Container[] freshElementData = Arrays.copyOf(elementData, newCapacity);
		this.containerArrays.set(firstLevelIdx, freshElementData);
	}

	private static int hugeCapacity(int minCapacity) {
		if (minCapacity < 0) // overflow
		{
			throw new OutOfMemoryError();
		}
		return (minCapacity > MAX_JVM_ARRAY_LENGTH) ? Integer.MAX_VALUE : MAX_JVM_ARRAY_LENGTH;
	}

	/**
	 * Report the number of bytes required for serialization.
	 *
	 * @return The size in bytes
	 */
	public long serializedSizeInBytes() {
		long totalSize = 0L;
		totalSize += 4;
		final int firstLevelSize = this.containerArrays.size();
		for (int i = 0; i < firstLevelSize; i++) {
			final Container[] containers = this.containerArrays.get(i);
			totalSize += 5;
			for (int j = 0; j < containers.length; j++) {
				final Container container = containers[j];
				if (container != null) {
					totalSize += 2;
					totalSize += 4;
					totalSize += container.getArraySizeInBytes();
				} else {
					totalSize += 1;
				}
			}
		}
		totalSize += 16;
		return totalSize;
	}

	/**
	 * Serialize the Containers
	 *
	 * @param dataOutput The destination DataOutput
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void serialize(@Nonnull DataOutput dataOutput) throws IOException {
		final int firstLevelSize = this.containerArrays.size();
		dataOutput.writeInt(Integer.reverseBytes(firstLevelSize));
		for (int i = 0; i < firstLevelSize; i++) {
			final Container[] containers = this.containerArrays.get(i);
			final int secondLevelSize = containers.length;
			dataOutput.writeByte(NOT_TRIMMED_MARK);
			// Trimmed second-level arrays are not persisted yet; every array is written as NOT_TRIMMED_MARK.
			dataOutput.writeInt(Integer.reverseBytes(secondLevelSize));
			for (int j = 0; j < containers.length; j++) {
				final Container container = containers[j];
				if (container != null) {
					dataOutput.writeByte(NOT_NULL_MARK);
					final byte containerType = containerType(container);
					dataOutput.writeByte(containerType);
					dataOutput.writeInt(Integer.reverseBytes(container.getCardinality()));
					container.writeArray(dataOutput);
				} else {
					dataOutput.writeByte(NULL_MARK);
				}
			}
		}
		dataOutput.writeLong(Long.reverseBytes(this.containerSize));
		dataOutput.writeInt(Integer.reverseBytes(this.firstLevelIdx));
		dataOutput.writeInt(Integer.reverseBytes(this.secondLevelIdx));
	}

	/**
	 * Serialize the Containers
	 *
	 * @param byteBuffer The destination ByteBuffer
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void serialize(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final int firstLevelSize = this.containerArrays.size();
		byteBuffer.putInt(firstLevelSize);
		for (int i = 0; i < firstLevelSize; i++) {
			final Container[] containers = this.containerArrays.get(i);
			final int secondLevelSize = containers.length;
			byteBuffer.put(NOT_TRIMMED_MARK);
			// Trimmed second-level arrays are not persisted yet; every array is written as NOT_TRIMMED_MARK.
			byteBuffer.putInt(secondLevelSize);
			for (int j = 0; j < containers.length; j++) {
				final Container container = containers[j];
				if (container != null) {
					byteBuffer.put(NOT_NULL_MARK);
					final byte containerType = containerType(container);
					byteBuffer.put(containerType);
					byteBuffer.putInt(container.getCardinality());
					container.writeArray(byteBuffer);
				} else {
					byteBuffer.put(NULL_MARK);
				}
			}
		}
		byteBuffer.putLong(this.containerSize);
		byteBuffer.putInt(this.firstLevelIdx);
		byteBuffer.putInt(this.secondLevelIdx);
	}

	/**
	 * Deserialize the byte stream to init this Containers
	 *
	 * @param dataInput The DataInput
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void deserialize(@Nonnull DataInput dataInput) throws IOException {
		final int firstLevelSize = Integer.reverseBytes(dataInput.readInt());
		final ArrayList<Container[]> containersArray = new ArrayList<>(firstLevelSize);
		for (int i = 0; i < firstLevelSize; i++) {
			// Trimmed-array metadata is not restored yet; the trim tag is read and ignored.
			final byte trimTag = dataInput.readByte();
			final int secondLevelSize = Integer.reverseBytes(dataInput.readInt());
			final Container[] containers = new Container[secondLevelSize];
			for (int j = 0; j < secondLevelSize; j++) {
				final byte nullTag = dataInput.readByte();
				if (nullTag == NULL_MARK) {
					containers[j] = null;
				} else if (nullTag == NOT_NULL_MARK) {
					final byte containerType = dataInput.readByte();
					final int cardinality = Integer.reverseBytes(dataInput.readInt());
					final Container container = instanceContainer(containerType, cardinality, dataInput);
					containers[j] = container;
				} else {
					throw new RuntimeException("the null tag byte value:" + nullTag + " is not right!");
				}
			}
			containersArray.add(containers);
		}
		this.containerArrays = containersArray;
		this.containerSize = Long.reverseBytes(dataInput.readLong());
		this.firstLevelIdx = Integer.reverseBytes(dataInput.readInt());
		this.secondLevelIdx = Integer.reverseBytes(dataInput.readInt());
	}

	/**
	 * Deserialize the byte stream to init this Containers
	 *
	 * @param byteBuffer The DataInput
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void deserialize(@Nonnull ByteBuffer byteBuffer) throws IOException {
		final int firstLevelSize = byteBuffer.getInt();
		final ArrayList<Container[]> containersArray = new ArrayList<>(firstLevelSize);
		for (int i = 0; i < firstLevelSize; i++) {
			// Trimmed-array metadata is not restored yet; the trim tag is read and ignored.
			final byte trimTag = byteBuffer.get();
			final int secondLevelSize = byteBuffer.getInt();
			final Container[] containers = new Container[secondLevelSize];
			for (int j = 0; j < secondLevelSize; j++) {
				final byte nullTag = byteBuffer.get();
				if (nullTag == NULL_MARK) {
					containers[j] = null;
				} else if (nullTag == NOT_NULL_MARK) {
					final byte containerType = byteBuffer.get();
					final int cardinality = byteBuffer.getInt();
					final Container container = instanceContainer(containerType, cardinality, byteBuffer);
					containers[j] = container;
				} else {
					throw new RuntimeException("the null tag byte value:" + nullTag + " is not right!");
				}
			}
			containersArray.add(containers);
		}
		this.containerArrays = containersArray;
		this.containerSize = byteBuffer.getLong();
		this.firstLevelIdx = byteBuffer.getInt();
		this.secondLevelIdx = byteBuffer.getInt();
	}

	private static byte containerType(@Nonnull Container container) {
		if (container instanceof RunContainer) {
			return 0;
		} else if (container instanceof BitmapContainer) {
			return 1;
		} else if (container instanceof ArrayContainer) {
			return 2;
		} else {
			throw new UnsupportedOperationException("Not supported container type");
		}
	}

	@Nonnull
	private static Container instanceContainer(byte containerType, int cardinality, @Nonnull DataInput dataInput)
		throws IOException {
		if (containerType == 0) {
			final int nbrruns = (Character.reverseBytes(dataInput.readChar()));
			final char[] lengthsAndValues = new char[2 * nbrruns];

			for (int j = 0; j < 2 * nbrruns; ++j) {
				lengthsAndValues[j] = Character.reverseBytes(dataInput.readChar());
			}
			return new RunContainer(lengthsAndValues, nbrruns);
		} else if (containerType == 1) {
			final long[] bitmapArray = new long[BitmapContainer.MAX_CAPACITY / 64];
			// little endian
			for (int l = 0; l < bitmapArray.length; ++l) {
				bitmapArray[l] = Long.reverseBytes(dataInput.readLong());
			}
			return new BitmapContainer(bitmapArray, cardinality);
		} else if (containerType == 2) {
			final char[] charArray = new char[cardinality];
			for (int l = 0; l < charArray.length; ++l) {
				charArray[l] = Character.reverseBytes(dataInput.readChar());
			}
			return new ArrayContainer(charArray);
		} else {
			throw new UnsupportedOperationException("Not supported container type:" + containerType);
		}
	}

	@Nonnull
	private static Container instanceContainer(byte containerType, int cardinality, @Nonnull ByteBuffer byteBuffer)
		throws IOException {
		if (containerType == 0) {
			final int nbrruns = byteBuffer.getChar();
			final char[] lengthsAndValues = new char[2 * nbrruns];
			byteBuffer.asCharBuffer().get(lengthsAndValues);
			byteBuffer.position(byteBuffer.position() + lengthsAndValues.length * 2);
			return new RunContainer(lengthsAndValues, nbrruns);
		} else if (containerType == 1) {
			final long[] bitmapArray = new long[BitmapContainer.MAX_CAPACITY / 64];
			byteBuffer.asLongBuffer().get(bitmapArray);
			byteBuffer.position(byteBuffer.position() + bitmapArray.length * 8);
			return new BitmapContainer(bitmapArray, cardinality);
		} else if (containerType == 2) {
			final char[] charArray = new char[cardinality];
			byteBuffer.asCharBuffer().get(charArray);
			byteBuffer.position(byteBuffer.position() + charArray.length * 2);
			return new ArrayContainer(charArray);
		} else {
			throw new UnsupportedOperationException("Not supported container type:" + containerType);
		}
	}
}
