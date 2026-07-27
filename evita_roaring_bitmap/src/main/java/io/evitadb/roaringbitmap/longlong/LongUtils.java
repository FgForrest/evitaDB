package io.evitadb.roaringbitmap.longlong;

import javax.annotation.Nonnull;

/**
 * Bit- and byte-level helpers for the 48/16 split used by the ART-backed 64-bit bitmap: every
 * 64-bit value is cut into a 48-bit high key and a 16-bit low position. The high key is materialised
 * as a 6-byte big-endian array so the ART tree's byte-dictionary ordering coincides with the
 * unsigned ordering of the original `long`; the low 16 bits become the position inside a 16-bit
 * {@link io.evitadb.roaringbitmap.Container}.
 */
public class LongUtils {

	/**
	 * Largest unsigned 32-bit value (`2^32 - 1`) widened to a `long`; used to detect 32-bit overflow.
	 */
	public static final long MAX_UNSIGNED_INT = Integer.toUnsignedLong(0xFFFFFFFF);

	/**
	 * Extracts the high 48 bits as a 6-byte big-endian key (index `0` holds the most significant
	 * byte) — the form the ART tree indexes on.
	 *
	 * @param num the long number
	 * @return the high 48 bit
	 */
	@Nonnull
	public static byte[] highPart(long num) {
		return new byte[]{
			(byte) ((num >>> 56) & 0xff),
			(byte) ((num >>> 48) & 0xff),
			(byte) ((num >>> 40) & 0xff),
			(byte) ((num >>> 32) & 0xff),
			(byte) ((num >>> 24) & 0xff),
			(byte) ((num >>> 16) & 0xff)
		};
	}

	/**
	 * Masks `num` down to its high 48 bits (low 16 bits zeroed), yielding the container-aligned base
	 * value of the container `num` falls into.
	 *
	 * @param num the long number
	 * @return `num` with its low 16 bits cleared
	 */
	public static long highPartOnly(long num) {
		return num & 0xFF_FF_FF_FF_FF_FF_00_00L;
	}

	/**
	 * get the low 16 bit parts of the input data
	 *
	 * @param num the long number
	 * @return the low 16 bit
	 */
	public static char lowPart(long num) {
		return (char) num;
	}

	/**
	 * reconstruct the long data
	 *
	 * @param high the high 48 bit
	 * @param low  the low 16 bit
	 * @return the long data
	 */
	public static long toLong(@Nonnull byte[] high, char low) {
		return toLong(high) << 16 | low;
	}

	/**
	 * Reconstruct the long data.
	 *
	 * @param high the high 48 bit
	 * @return the long data
	 */
	public static long toLong(@Nonnull byte[] high) {
		return (high[0] & 0xFFL) << 40
			| (high[1] & 0xFFL) << 32
			| (high[2] & 0xFFL) << 24
			| (high[3] & 0xFFL) << 16
			| (high[4] & 0xFFL) << 8
			| (high[5] & 0xFFL);
	}

	/**
	 * Recombines a right-aligned 48-bit high key with a 16-bit low position into the original value.
	 *
	 * @param high the high 48 bit, right-aligned in the low 48 bits of the long
	 * @param low  the low 16 bit
	 * @return the long data
	 */
	public static long toLong(long high, char low) {
		return high << 16 | low;
	}

	/**
	 * to big endian bytes representation
	 *
	 * @param v a long value
	 * @return the input long value's big endian byte array representation
	 */
	@Nonnull
	public static byte[] toBDBytes(long v) {
		final byte[] work = new byte[8];
		work[7] = (byte) v;
		work[6] = (byte) (v >> 8);
		work[5] = (byte) (v >> 16);
		work[4] = (byte) (v >> 24);
		work[3] = (byte) (v >> 32);
		work[2] = (byte) (v >> 40);
		work[1] = (byte) (v >> 48);
		work[0] = (byte) (v >> 56);
		return work;
	}

	/**
	 * get the long from the big endian representation bytes
	 *
	 * @param work the byte array
	 * @return the long data
	 */
	public static long fromBDBytes(@Nonnull byte[] work) {
		return (long) (work[0]) << 56
			/* long cast needed or shift done modulo 32 */
			| (long) (work[1] & 0xff) << 48
			| (long) (work[2] & 0xff) << 40
			| (long) (work[3] & 0xff) << 32
			| (long) (work[4] & 0xff) << 24
			| (long) (work[5] & 0xff) << 16
			| (long) (work[6] & 0xff) << 8
			| (long) (work[7] & 0xff);
	}

	/**
	 * get the long from the big endian representation bytes
	 *
	 * @param key the byte array. Always at least 6 bytes
	 * @return the long data
	 */
	public static long fromKey(@Nonnull byte[] key) {
		return (long) (key[0]) << 56
			/* long cast needed or shift done modulo 32 */
			| (long) (key[1] & 0xff) << 48
			| (long) (key[2] & 0xff) << 40
			| (long) (key[3] & 0xff) << 32
			| (long) (key[4] & 0xff) << 24
			| (long) (key[5] & 0xff) << 16;
	}

	/**
	 * initialize a long value with the given fist 32 bit
	 *
	 * @param v first 32 bit value
	 * @return a long value
	 */
	public static long initWithFirst4Byte(int v) {
		return ((long) v) << 32;
	}

	/**
	 * shift the long right by the container size amount so we can loop across containers by +1 steps
	 *
	 * @param num long being treated as unsigned long
	 * @return value shifted out of value space into container high part
	 */
	public static long rightShiftHighPart(long num) {
		return num >>> 16;
	}

	/**
	 * shift the long by left the container size amount so we use the value after have done our steps
	 *
	 * @param num uint48 to be shift back into uint64
	 * @return value shifted out of container high part back into value space
	 */
	public static long leftShiftHighPart(long num) {
		return num << 16;
	}

	/**
	 * The largest low-16 position (`0xFFFF`), i.e. the last slot addressable within a single container.
	 *
	 * @return `0xFFFF`
	 */
	public static int maxLowBitAsInteger() {
		return 0xFFFF;
	}

	/**
	 * set the high 48 bit parts of the input number into the given byte array
	 *
	 * @param num    the long number
	 * @param high48 the byte array
	 * @return the high 48 bit
	 */
	@Nonnull
	public static byte[] highPartInPlace(long num, @Nonnull byte[] high48) {
		high48[0] = (byte) ((num >>> 56) & 0xff);
		high48[1] = (byte) ((num >>> 48) & 0xff);
		high48[2] = (byte) ((num >>> 40) & 0xff);
		high48[3] = (byte) ((num >>> 32) & 0xff);
		high48[4] = (byte) ((num >>> 24) & 0xff);
		high48[5] = (byte) ((num >>> 16) & 0xff);
		return high48;
	}

	/**
	 * checks if given high48 is the maximum possible one
	 * (e.g. it is the case for -1L, which is the maximum unsigned long)
	 *
	 * @param key long
	 * @return true if this the maximum high part
	 */
	public static boolean isMaxHigh(long key) {
		return (key & 0xFF_FF_FF_FF_FF_FFL) == 0xFF_FF_FF_FF_FF_FFL;
	}

	/**
	 * get the byte at the specified position
	 *
	 * @param key the long value
	 * @param i   the position of the byte to get, from 0 to 7
	 * @return the byte at the specified position
	 */
	@SuppressWarnings("checkstyle:magicnumber")
	public static byte getByte(long key, int i) {
		return (byte) (key >> ((7 - i) << 3));
	}

}
