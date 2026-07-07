/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

/**
 * Base container class.
 */
public abstract class Container
	implements Iterable<Character>, Cloneable, Externalizable, WordStorage<Container> {

	/**
	 * Create a container initialized with a range of consecutive values
	 *
	 * @param start first index
	 * @param last  last index (range is exclusive)
	 * @return a new container initialized with the specified values
	 */
	@Nonnull
	public static Container rangeOfOnes(final int start, final int last) {
		final int arrayContainerOverRunThreshold = 2;
		final int cardinality = last - start;

		if (cardinality <= arrayContainerOverRunThreshold) {
			return new ArrayContainer(start, last);
		}
		return new RunContainer(start, last);
	}

	/**
	 * Return a new container with all shorts in [begin,end) added using an unsigned interpretation.
	 *
	 * @param begin start of range (inclusive)
	 * @param end   end of range (exclusive)
	 * @return the new container
	 */
	@Nonnull
	public abstract Container add(int begin, int end);

	/**
	 * Add a short to the container. May generate a new container.
	 *
	 * @param x short to be added
	 * @return the new container
	 */
	@Nonnull
	public abstract Container add(char x);

	/**
	 * Computes the bitwise AND of this container with another (intersection). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container and(@Nonnull ArrayContainer x);

	/**
	 * Computes the bitwise AND of this container with another (intersection). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container and(@Nonnull BitmapContainer x);

	/**
	 * Computes the bitwise AND of this container with another (intersection). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container and(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return and((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return and((BitmapContainer) x);
		}
		return and((RunContainer) x);
	}

	/**
	 * Computes the bitwise AND of this container with another (intersection). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container and(@Nonnull RunContainer x);

	protected abstract int andCardinality(@Nonnull ArrayContainer x);

	protected abstract int andCardinality(@Nonnull BitmapContainer x);

	protected abstract int andCardinality(@Nonnull RunContainer x);

	/**
	 * Computes the bitwise AND of this container with another (intersection). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	public int andCardinality(@Nonnull final Container x) {
		if (this.isEmpty()) {
			return 0;
		} else if (x.isEmpty()) {
			return 0;
		} else {
			if (x instanceof ArrayContainer) {
				return andCardinality((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return andCardinality((BitmapContainer) x);
			}
			return andCardinality((RunContainer) x);
		}
	}

	/**
	 * Returns the cardinality of the XOR between the passed container and this
	 * container without materialising a temporary container.
	 *
	 * @param other other container
	 * @return the cardinality of the symmetric difference of the two containers
	 */
	public int xorCardinality(@Nonnull final Container other) {
		return getCardinality() + other.getCardinality() - 2 * andCardinality(other);
	}

	/**
	 * Computes the bitwise ANDNOT of this container with another (difference). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container andNot(@Nonnull ArrayContainer x);

	/**
	 * Computes the bitwise ANDNOT of this container with another (difference). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container andNot(@Nonnull BitmapContainer x);

	/**
	 * Computes the bitwise ANDNOT of this container with another (difference). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container andNot(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return andNot((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return andNot((BitmapContainer) x);
		}
		return andNot((RunContainer) x);
	}

	/**
	 * Computes the bitwise ANDNOT of this container with another (difference). This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container andNot(@Nonnull RunContainer x);

	/**
	 * Computes the bitwise ORNOT of this container with another. This container as well
	 * as the provided container are left unaffected.
	 *
	 * @param x          other container
	 * @param endOfRange end of range (size of the universe)
	 * @return aggregated container
	 */
	@Nonnull
	public Container orNot(@Nonnull final Container x, final int endOfRange) {
		if (endOfRange < 0x10000) {
			return or(x.not(0, endOfRange).iremove(endOfRange, 0x10000));
		}
		return or(x.not(0, 0x10000));
	}

	/**
	 * Empties the container
	 */
	public abstract void clear();

	@Nonnull
	@Override
	public abstract Container clone();

	/**
	 * Checks whether the container is empty or not.
	 *
	 * @return true if the container is empty.
	 */
	public abstract boolean isEmpty();

	/**
	 * Checks whether the container is full or not.
	 *
	 * @return true if the container is full.
	 */
	public abstract boolean isFull();

	/**
	 * Checks whether the contain contains the provided value
	 *
	 * @param x value to check
	 * @return whether the value is in the container
	 */
	public abstract boolean contains(char x);

	/**
	 * Checks whether the container contains the entire range
	 *
	 * @param minimum  the inclusive lower bound of the range
	 * @param supremum the exclusive upper bound of the range
	 * @return true if the container contains the range
	 */
	public abstract boolean contains(int minimum, int supremum);

	/**
	 * Checks whether the container is a subset of this container or not
	 *
	 * @param subset the container to be tested
	 * @return true if the parameter is a subset of this container
	 */
	public boolean contains(@Nonnull final Container subset) {
		if (subset instanceof RunContainer) {
			return contains((RunContainer) subset);
		} else if (subset instanceof ArrayContainer) {
			return contains((ArrayContainer) subset);
		} else if (subset instanceof BitmapContainer) {
			return contains((BitmapContainer) subset);
		}
		return false;
	}

	protected abstract boolean contains(@Nonnull RunContainer runContainer);

	protected abstract boolean contains(@Nonnull ArrayContainer arrayContainer);

	protected abstract boolean contains(@Nonnull BitmapContainer bitmapContainer);

	/**
	 * Deserialize (recover) the container.
	 *
	 * @param in the DataInput stream
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public abstract void deserialize(@Nonnull DataInput in) throws IOException;

	/**
	 * Fill the least significant 16 bits of the integer array, starting at index i, with the short
	 * values from this container. The caller is responsible to allocate enough room. The most
	 * significant 16 bits of each integer are given by the most significant bits of the provided
	 * mask.
	 *
	 * @param x    provided array
	 * @param i    starting index
	 * @param mask indicates most significant bits
	 */
	public abstract void fillLeastSignificant16bits(@Nonnull int[] x, int i, int mask);

	/**
	 * Add a short to the container if it is not present, otherwise remove it. May generate a new
	 * container.
	 *
	 * @param x short to be added
	 * @return the new container
	 */
	@Nonnull
	public abstract Container flip(char x);

	/**
	 * Size of the underlying array
	 *
	 * @return size in bytes
	 */
	public abstract int getArraySizeInBytes();

	/**
	 * Computes the distinct number of char values in the container. Can be expected to run in
	 * constant time.
	 *
	 * @return the cardinality
	 */
	public abstract int getCardinality();

	/**
	 * Get the name of this container.
	 *
	 * @return name of the container
	 */
	@Nonnull
	public String getContainerName() {
		if (this instanceof BitmapContainer) {
			return ContainerNames[0];
		} else if (this instanceof ArrayContainer) {
			return ContainerNames[1];
		} else {
			return ContainerNames[2];
		}
	}

	/**
	 * Name of the various possible containers
	 */
	public static final String[] ContainerNames = {"bitmap", "array", "run"};

	/**
	 * Iterate through the values of this container and pass them
	 * along to the IntConsumer, using msb as the 16 most significant bits.
	 *
	 * @param msb 16 most significant bits
	 * @param ic  consumer
	 */
	public abstract void forEach(char msb, @Nonnull IntConsumer ic);

	/**
	 * Consume presence information for all values in this container.
	 *
	 * @param offset First value in this container corresponds to this
	 *               relativePos in the range consumer.
	 * @param rrc    consumer
	 */
	public abstract void forAll(int offset, @Nonnull final RelativeRangeConsumer rrc);

	/**
	 * Consume presence information for all values greater or equal to
	 * startValue in this container.
	 *
	 * @param startValue First value to consume. Corresponds to relativePos=0 in the range consumer.
	 * @param rrc        consumer
	 */
	public abstract void forAllFrom(char startValue, @Nonnull final RelativeRangeConsumer rrc);

	/*
	 * Design Note
	 * It might seem like a bad idea to make `endValue` exclusive, since it gives no way for this
	 * method to read the last value in a `Container` if it's `Character.MAX_VALUE`,
	 * but that is what the `forAll` method is for.
	 * These methods are separate on purpose, so that the logic of each becomes simpler to follow.
	 */

	/**
	 * Consume presence information for all values smaller than endValue
	 * in this container.
	 *
	 * @param offset   First value in this container corresponds to this
	 *                 relativePos in the range consumer.
	 * @param endValue First value greater than last value to consume.
	 * @param rrc      consumer
	 */
	public abstract void forAllUntil(
		int offset, char endValue, @Nonnull final RelativeRangeConsumer rrc);

	/**
	 * Consumer presence information for all values in the
	 * range [startValue, endValue) in this container.
	 *
	 * @param startValue First value to consume. Corresponds to relativePos=0 in the range consumer.
	 * @param endValue   First value greater than last value to consume.
	 * @param rrc        consumer
	 */
	public abstract void forAllInRange(
		char startValue, char endValue, @Nonnull final RelativeRangeConsumer rrc);

	/**
	 * Iterator to visit the char values in the container in descending order.
	 *
	 * @return iterator
	 */
	@Nonnull
	public abstract PeekableCharIterator getReverseCharIterator();

	/**
	 * Iterator to visit the char values in the container in ascending order.
	 *
	 * @return iterator
	 */
	@Nonnull
	public abstract PeekableCharIterator getCharIterator();

	/**
	 * Iterator to visit the short values in container and pre-compute ranks
	 *
	 * @return iterator
	 */
	@Nonnull
	public abstract PeekableCharRankIterator getCharRankIterator();

	/**
	 * Gets an iterator to visit the contents of the container in batches
	 *
	 * @return iterator
	 */
	@Nonnull
	public abstract ContainerBatchIterator getBatchIterator();

	/**
	 * Computes an estimate of the memory usage of this container. The estimate is not meant to be
	 * exact.
	 *
	 * @return estimated memory usage in bytes
	 */
	public abstract int getSizeInBytes();

	/**
	 * Add all shorts in [begin,end) using an unsigned interpretation. May generate a new container.
	 *
	 * @param begin start of range (inclusive)
	 * @param end   end of range (exclusive)
	 * @return the new container
	 */
	@Nonnull
	public abstract Container iadd(int begin, int end);

	/**
	 * Computes the in-place bitwise AND of this container with another (intersection). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container iand(@Nonnull ArrayContainer x);

	/**
	 * Computes the in-place bitwise AND of this container with another (intersection). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container iand(@Nonnull BitmapContainer x);

	/**
	 * Computes the in-place bitwise AND of this container with another (intersection). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container iand(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return iand((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return iand((BitmapContainer) x);
		}
		return iand((RunContainer) x);
	}

	/**
	 * Computes the in-place bitwise AND of this container with another (intersection). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container iand(@Nonnull RunContainer x);

	/**
	 * Computes the in-place bitwise ANDNOT of this container with another (difference). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container iandNot(@Nonnull ArrayContainer x);

	/**
	 * Computes the in-place bitwise ANDNOT of this container with another (difference). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container iandNot(@Nonnull BitmapContainer x);

	/**
	 * Computes the in-place bitwise ANDNOT of this container with another (difference). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container iandNot(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return iandNot((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return iandNot((BitmapContainer) x);
		}
		return iandNot((RunContainer) x);
	}

	/**
	 * Computes the in-place bitwise ANDNOT of this container with another (difference). The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container iandNot(@Nonnull RunContainer x);

	/**
	 * Computes the in-place bitwise ORNOT of this container with another. The current
	 * container is generally modified, whereas the provided container (x) is unaffected. May generate
	 * a new container.
	 *
	 * @param x          other container
	 * @param endOfRange the exclusive end
	 * @return aggregated container
	 */
	@Nonnull
	public Container iorNot(@Nonnull final Container x, final int endOfRange) {
		if (endOfRange < 0x10000) {
			return ior(x.not(0, endOfRange).iremove(endOfRange, 0x10000));
		}
		return ior(x.not(0, 0x10000));
	}

	/**
	 * Computes the in-place bitwise NOT of this container (complement). Only those bits within the
	 * range are affected. The current container is generally modified. May generate a new container.
	 *
	 * @param rangeStart beginning of range (inclusive); 0 is beginning of this container.
	 * @param rangeEnd   ending of range (exclusive)
	 * @return (partially) complemented container
	 */
	@Nonnull
	public abstract Container inot(int rangeStart, int rangeEnd);

	/**
	 * Returns true if the current container intersects the other container.
	 *
	 * @param x other container
	 * @return whether they intersect
	 */
	public abstract boolean intersects(@Nonnull ArrayContainer x);

	/**
	 * Returns true if the current container intersects the other container.
	 *
	 * @param x other container
	 * @return whether they intersect
	 */
	public abstract boolean intersects(@Nonnull BitmapContainer x);

	/**
	 * Returns true if the current container intersects the other container.
	 *
	 * @param x other container
	 * @return whether they intersect
	 */
	public boolean intersects(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return intersects((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return intersects((BitmapContainer) x);
		}
		return intersects((RunContainer) x);
	}

	/**
	 * Returns true if the current container intersects the other container.
	 *
	 * @param x other container
	 * @return whether they intersect
	 */
	public abstract boolean intersects(@Nonnull RunContainer x);

	/**
	 * Checks if the container intersects with a range
	 *
	 * @param minimum  the inclusive unsigned lower bound of the range
	 * @param supremum the exclusive unsigned upper bound of the range
	 * @return true if the container intersects the range
	 */
	public abstract boolean intersects(int minimum, int supremum);

	/**
	 * Computes the in-place bitwise OR of this container with another (union). The current container
	 * is generally modified, whereas the provided container (x) is unaffected. May generate a new
	 * container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container ior(@Nonnull ArrayContainer x);

	/**
	 * Computes the in-place bitwise OR of this container with another (union). The current container
	 * is generally modified, whereas the provided container (x) is unaffected. May generate a new
	 * container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container ior(@Nonnull BitmapContainer x);

	/**
	 * Computes the in-place bitwise OR of this container with another (union). The current container
	 * is generally modified, whereas the provided container (x) is unaffected. May generate a new
	 * container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container ior(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return ior((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return ior((BitmapContainer) x);
		}
		return ior((RunContainer) x);
	}

	/**
	 * Computes the in-place bitwise OR of this container with another (union). The current container
	 * is generally modified, whereas the provided container (x) is unaffected. May generate a new
	 * container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container ior(@Nonnull RunContainer x);

	/**
	 * Remove shorts in [begin,end) using an unsigned interpretation. May generate a new container.
	 *
	 * @param begin start of range (inclusive)
	 * @param end   end of range (exclusive)
	 * @return the new container
	 */
	@Nonnull
	public abstract Container iremove(int begin, int end);

	/**
	 * Computes the in-place bitwise XOR of this container with another (symmetric difference). The
	 * current container is generally modified, whereas the provided container (x) is unaffected. May
	 * generate a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container ixor(@Nonnull ArrayContainer x);

	/**
	 * Computes the in-place bitwise XOR of this container with another (symmetric difference). The
	 * current container is generally modified, whereas the provided container (x) is unaffected. May
	 * generate a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container ixor(@Nonnull BitmapContainer x);

	/**
	 * Computes the in-place bitwise OR of this container with another (union). The current container
	 * is generally modified, whereas the provided container (x) is unaffected. May generate a new
	 * container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container ixor(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return ixor((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return ixor((BitmapContainer) x);
		}
		return ixor((RunContainer) x);
	}

	/**
	 * Computes the in-place bitwise XOR of this container with another (symmetric difference). The
	 * current container is generally modified, whereas the provided container (x) is unaffected. May
	 * generate a new container.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container ixor(@Nonnull RunContainer x);

	/**
	 * Computes the in-place bitwise OR of this container with another (union). The current container
	 * is generally modified, whereas the provided container (x) is unaffected. May generate a new
	 * container. The resulting container may not track its cardinality correctly. The resulting
	 * container may not track its cardinality correctly. This can be fixed as follows:
	 * if(c.getCardinality()&lt;0) ((BitmapContainer)c).computeCardinality();
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container lazyIOR(@Nonnull final Container x) {
		if (this instanceof ArrayContainer) {
			if (x instanceof ArrayContainer) {
				return ((ArrayContainer) this).lazyor((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return ior((BitmapContainer) x);
			}
			return ((RunContainer) x).lazyor((ArrayContainer) this);
		} else if (this instanceof RunContainer) {
			if (x instanceof ArrayContainer) {
				return ((RunContainer) this).ilazyor((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return ior((BitmapContainer) x);
			}
			return ior((RunContainer) x);
		} else {
			if (x instanceof ArrayContainer) {
				return ((BitmapContainer) this).ilazyor((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return ((BitmapContainer) this).ilazyor((BitmapContainer) x);
			}
			return ((BitmapContainer) this).ilazyor((RunContainer) x);
		}
	}

	/**
	 * Computes the bitwise OR of this container with another (union). This container as well as the
	 * provided container are left unaffected. The resulting container may not track its cardinality
	 * correctly. This can be fixed as follows: if(c.getCardinality()&lt;0)
	 * ((BitmapContainer)c).computeCardinality();
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container lazyOR(@Nonnull final Container x) {
		if (this instanceof ArrayContainer) {
			if (x instanceof ArrayContainer) {
				return ((ArrayContainer) this).lazyor((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return ((BitmapContainer) x).lazyor((ArrayContainer) this);
			}
			return ((RunContainer) x).lazyor((ArrayContainer) this);
		} else if (this instanceof RunContainer) {
			if (x instanceof ArrayContainer) {
				return ((RunContainer) this).lazyor((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return ((BitmapContainer) x).lazyor((RunContainer) this);
			}
			return or((RunContainer) x);
		} else {
			if (x instanceof ArrayContainer) {
				return ((BitmapContainer) this).lazyor((ArrayContainer) x);
			} else if (x instanceof BitmapContainer) {
				return ((BitmapContainer) this).lazyor((BitmapContainer) x);
			}
			return ((BitmapContainer) this).lazyor((RunContainer) x);
		}
	}

	/**
	 * Create a new Container containing at most maxcardinality integers.
	 *
	 * @param maxcardinality maximal cardinality
	 * @return a new bitmap with cardinality no more than maxcardinality
	 */
	@Nonnull
	public abstract Container limit(int maxcardinality);

	/**
	 * Computes the bitwise NOT of this container (complement). Only those bits within the range are
	 * affected. The current container is left unaffected.
	 *
	 * @param rangeStart beginning of range (inclusive); 0 is beginning of this container.
	 * @param rangeEnd   ending of range (exclusive)
	 * @return (partially) complemented container
	 */
	@Nonnull
	public abstract Container not(int rangeStart, int rangeEnd);

	abstract int numberOfRuns(); // exact

	/**
	 * Computes the bitwise OR of this container with another (union). This container as well as the
	 * provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container or(@Nonnull ArrayContainer x);

	/**
	 * Computes the bitwise OR of this container with another (union). This container as well as the
	 * provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container or(@Nonnull BitmapContainer x);

	/**
	 * Computes the bitwise OR of this container with another (union). This container as well as the
	 * provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public Container or(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return or((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return or((BitmapContainer) x);
		}
		return or((RunContainer) x);
	}

	/**
	 * Computes the bitwise OR of this container with another (union). This container as well as the
	 * provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container or(@Nonnull RunContainer x);

	/**
	 * Rank returns the number of integers that are smaller or equal to x (Rank(infinity) would be
	 * GetCardinality()).
	 *
	 * @param lowbits upper limit
	 * @return the rank
	 */
	public abstract int rank(char lowbits);

	/**
	 * Return a new container with all chars in [begin,end) remove using an unsigned interpretation.
	 *
	 * @param begin start of range (inclusive)
	 * @param end   end of range (exclusive)
	 * @return the new container
	 */
	@Nonnull
	public abstract Container remove(int begin, int end);

	/**
	 * Remove the char from this container. May create a new container.
	 *
	 * @param x to be removed
	 * @return New container
	 */
	@Nonnull
	public abstract Container remove(char x);

	/**
	 * The output of a lazyOR or lazyIOR might be an invalid container, this should be called on it.
	 *
	 * @return a new valid container
	 */
	@Nonnull
	public abstract Container repairAfterLazy();

	/**
	 * Convert to RunContainers, when the result is smaller. Overridden by RunContainer to possibility
	 * switch from RunContainer to a smaller alternative. Overridden by BitmapContainer with a more
	 * efficient approach.
	 *
	 * @return the new container
	 */
	@Nonnull
	public abstract Container runOptimize();

	/**
	 * Return the jth value
	 *
	 * @param j index of the value
	 * @return the value
	 */
	public abstract char select(int j);

	/**
	 * Serialize the container.
	 *
	 * @param out the DataOutput stream
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public abstract void serialize(@Nonnull DataOutput out) throws IOException;

	/**
	 * Report the number of bytes required to serialize this container.
	 *
	 * @return the size in bytes
	 */
	public abstract int serializedSizeInBytes();

	/**
	 * If possible, recover wasted memory.
	 */
	public abstract void trim();

	/**
	 * Write just the underlying array.
	 *
	 * @param out output stream
	 * @throws IOException in case of failure
	 */
	public abstract void writeArray(@Nonnull DataOutput out) throws IOException;

	/**
	 * Write just the underlying array.
	 *
	 * @param buffer ByteBuffer to write to
	 */
	public abstract void writeArray(@Nonnull ByteBuffer buffer);

	/**
	 * Computes the bitwise XOR of this container with another (symmetric difference). This container
	 * as well as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container xor(@Nonnull ArrayContainer x);

	/**
	 * Computes the bitwise XOR of this container with another (symmetric difference). This container
	 * as well as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container xor(@Nonnull BitmapContainer x);

	/**
	 * Computes the bitwise XOR of this container with another (symmetric difference). This container
	 * as well as the provided container are left unaffected.
	 *
	 * @param x other parameter
	 * @return aggregated container
	 */
	@Nonnull
	public Container xor(@Nonnull final Container x) {
		if (x instanceof ArrayContainer) {
			return xor((ArrayContainer) x);
		} else if (x instanceof BitmapContainer) {
			return xor((BitmapContainer) x);
		}
		return xor((RunContainer) x);
	}

	/**
	 * Computes the bitwise XOR of this container with another (symmetric difference). This container
	 * as well as the provided container are left unaffected.
	 *
	 * @param x other container
	 * @return aggregated container
	 */
	@Nonnull
	public abstract Container xor(@Nonnull RunContainer x);

	/**
	 * Convert the current container to a BitmapContainer, if a conversion is needed.
	 * If the container is already a bitmap, the container is returned unchanged.
	 *
	 * @return a bitmap container
	 */
	@Nonnull
	public abstract BitmapContainer toBitmapContainer();

	/**
	 * Copy the current container to a destination {@code long[]}. Equivalent to calling
	 * {@link #toBitmapContainer()} and copying the result to the given position. The destination
	 * array should be sized to accomodate the maximum number of words required to represent
	 * the container bitmap.
	 *
	 * @param dest     the destination array
	 * @param position the position to copy to
	 */
	public abstract void copyBitmapTo(@Nonnull long[] dest, int position);

	/**
	 * Gets the first value greater than or equal to the lower bound, or -1 if no such value exists.
	 *
	 * @param fromValue the lower bound (inclusive)
	 * @return the next value
	 */
	public abstract int nextValue(char fromValue);

	/**
	 * Gets the last value less than or equal to the upper bound, or -1 if no such value exists.
	 *
	 * @param fromValue the upper bound (inclusive)
	 * @return the previous value
	 */
	public abstract int previousValue(char fromValue);

	/**
	 * Gets the first absent value greater than or equal to the lower bound.
	 *
	 * @param fromValue the lower bound (inclusive)
	 * @return the next absent value
	 */
	public abstract int nextAbsentValue(char fromValue);

	/**
	 * Gets the last value less than or equal to the upper bound.
	 *
	 * @param fromValue the upper bound (inclusive)
	 * @return the previous absent value
	 */
	public abstract int previousAbsentValue(char fromValue);

	/**
	 * Get the first integer held in the container
	 *
	 * @return the first integer in the container
	 * @throws NoSuchElementException if empty
	 */
	public abstract int first();

	/**
	 * Get the last integer held in the container
	 *
	 * @return the last integer in the container
	 * @throws NoSuchElementException if empty
	 */
	public abstract int last();

	/**
	 * Throw if the container is empty
	 *
	 * @param condition a boolean expression
	 * @throws NoSuchElementException if empty
	 */
	protected void assertNonEmpty(final boolean condition) {
		if (condition) {
			throw new NoSuchElementException("Empty " + getContainerName());
		}
	}

	/**
	 * Validate the content of the container. Useful after deserialization.
	 *
	 * @return true if the container is valid.
	 */
	@Nonnull
	public abstract Boolean validate();
}
