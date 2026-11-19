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

package io.evitadb.spike.vector;

import io.evitadb.spike.footprint.FootprintSpikeSupport;

import javax.annotation.Nonnull;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Memory probes and output formatting for the vector spike. Every figure falls into one of three places, and the
 * probes are chosen so each place is measured by something that can actually see it:
 *
 * - **heap** — post-GC used heap deltas, the libraries' own `ramBytesUsed()` estimates, and JOL owned sizes
 *   through {@link FootprintSpikeSupport#ownedSize(Object, Object...)},
 * - **direct / mapped buffers** — the JVM's `direct` and `mapped` buffer pools; on JDK 21 both `FileChannel#map`
 *   buffers and the `MemorySegment` mappings of Lucene's `MMapDirectory` are counted in `mapped`,
 * - **mmapped files** — `/proc/self/smaps`, which reports both the mapped size and the resident (page-cache) part
 *   of every mapping regardless of which Java API created it.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class VectorMemoryProbe {
	/** Bytes in one mebibyte. */
	private static final double MB = 1024.0 * 1024.0;

	private VectorMemoryProbe() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Runs several GC cycles and reads the used heap, so that deltas between two calls approximate retained size.
	 *
	 * @return used heap in bytes after garbage collection
	 */
	public static long heapUsedAfterGc() {
		for (int i = 0; i < 4; i++) {
			System.gc();
			try {
				Thread.sleep(150);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	/**
	 * Resets the peak usage of every heap memory pool.
	 */
	public static void resetHeapPeak() {
		for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.HEAP) {
				pool.resetPeakUsage();
			}
		}
	}

	/**
	 * @return the sum of heap pool peaks since the last {@link #resetHeapPeak()} — an upper bound of the peak heap
	 *         including garbage not yet collected, not a retained size
	 */
	public static long heapPeak() {
		long sum = 0;
		for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.HEAP) {
				sum += pool.getPeakUsage().getUsed();
			}
		}
		return sum;
	}

	/**
	 * @return count and capacity of the JVM's `direct` and `mapped` buffer pools on one line
	 */
	@Nonnull
	public static String bufferPools() {
		final StringBuilder sb = new StringBuilder(128);
		for (final BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
			sb.append(pool.getName()).append("[count=").append(pool.getCount())
				.append(", capacity=").append(mb(pool.getTotalCapacity())).append("] ");
		}
		return sb.toString().trim();
	}

	/**
	 * Sums the `Size` (mapped) and `Rss` (resident) lines of every `/proc/self/smaps` mapping per file.
	 *
	 * @param directory only mappings of files under this directory are reported
	 * @return file path to a two-element array of mapped and resident bytes
	 * @throws IOException when smaps cannot be read (non-Linux systems)
	 */
	@Nonnull
	public static Map<String, long[]> smaps(@Nonnull Path directory) throws IOException {
		final Map<String, long[]> result = new TreeMap<>();
		final String prefix = directory.toAbsolutePath().normalize().toString();
		final List<String> lines = Files.readAllLines(Path.of("/proc/self/smaps"));
		String current = null;
		for (final String line : lines) {
			final int space = line.indexOf(' ');
			if (space > 0 && line.substring(0, space).matches("[0-9a-f]+-[0-9a-f]+")) {
				// mapping header: "start-end perms offset dev inode [path]"
				final String[] parts = line.trim().split("\\s+", 6);
				current = parts.length == 6 && parts[5].startsWith(prefix) ? parts[5] : null;
			} else if (current != null && (line.startsWith("Size:") || line.startsWith("Rss:"))) {
				final long kb = Long.parseLong(line.replaceAll("[^0-9]", ""));
				final long[] accumulator = result.computeIfAbsent(current, k -> new long[2]);
				accumulator[line.startsWith("Size:") ? 0 : 1] += kb * 1024L;
			}
		}
		return result;
	}

	/**
	 * Prints the mappings of files under `directory` with their mapped and resident sizes.
	 *
	 * @param directory only mappings of files under this directory are printed
	 * @throws IOException when smaps cannot be read
	 */
	public static void printMappings(@Nonnull Path directory) throws IOException {
		final Map<String, long[]> mappings = smaps(directory);
		if (mappings.isEmpty()) {
			line("mmap: no mapping of a file under " + directory);
		}
		for (final Map.Entry<String, long[]> entry : mappings.entrySet()) {
			line(
				"mmap " + Path.of(entry.getKey()).getFileName() + ": mapped=" + mb(entry.getValue()[0])
					+ ", resident=" + mb(entry.getValue()[1])
			);
		}
	}

	/**
	 * Prints the `Total`, `Java Heap` and `Other` lines of the native memory tracking summary — `Other` carries
	 * direct `ByteBuffer` allocations. Prints a note when the JVM runs without `-XX:NativeMemoryTracking`.
	 */
	public static void printNativeMemory() {
		try {
			final Object out = ManagementFactory.getPlatformMBeanServer().invoke(
				new ObjectName("com.sun.management:type=DiagnosticCommand"),
				"vmNativeMemory",
				new Object[]{new String[]{"summary", "scale=MB"}},
				new String[]{String[].class.getName()}
			);
			for (final String nmtLine : out.toString().split("\n")) {
				final String trimmed = nmtLine.trim();
				if (trimmed.startsWith("Total:")
					|| trimmed.startsWith("- ") && (trimmed.contains("Java Heap") || trimmed.contains("Other"))) {
					line("nmt " + trimmed.replaceAll("\\s+", " "));
				}
			}
		} catch (Exception e) {
			line("nmt: unavailable (" + e.getMessage() + ")");
		}
	}

	/**
	 * @return the process resident set size from `/proc/self/status`, or -1 when it cannot be found
	 * @throws IOException when the status file cannot be read
	 */
	public static long processRss() throws IOException {
		for (final String statusLine : Files.readAllLines(Path.of("/proc/self/status"))) {
			if (statusLine.startsWith("VmRSS:")) {
				return Long.parseLong(statusLine.replaceAll("[^0-9]", "")) * 1024L;
			}
		}
		return -1;
	}

	/**
	 * Measures what `instance` owns on the heap, excluding everything reachable from `sharedRoots`; falls back to
	 * -1 when JOL cannot walk the graph (e.g. a capturing lambda without `-Djol.magicFieldOffset=true`).
	 *
	 * @param instance    the structure to measure
	 * @param sharedRoots objects owned elsewhere, excluded from the result
	 * @return owned heap bytes, or -1 when the walk failed
	 */
	public static long ownedSize(@Nonnull Object instance, @Nonnull Object... sharedRoots) {
		try {
			return FootprintSpikeSupport.ownedSize(instance, sharedRoots);
		} catch (RuntimeException e) {
			line("JOL walk failed: " + e);
			return -1;
		}
	}

	/**
	 * Prints a section heading.
	 *
	 * @param title the heading text
	 */
	public static void section(@Nonnull String title) {
		System.out.println();
		System.out.println("=== " + title);
	}

	/**
	 * Prints one indented free-form line.
	 *
	 * @param text the line content
	 */
	public static void line(@Nonnull String text) {
		System.out.println("  " + text);
	}

	/**
	 * Prints a measured size in MB together with its per-vector share.
	 *
	 * @param label   what was measured
	 * @param bytes   measured bytes
	 * @param vectors number of indexed vectors the size is divided by
	 */
	public static void size(@Nonnull String label, long bytes, int vectors) {
		line(label + ": " + mb(bytes) + String.format(" (%.1f B/vec)", bytes / (double) vectors));
	}

	/**
	 * @param bytes a byte count
	 * @return the count in MB with two decimals
	 */
	@Nonnull
	public static String mb(long bytes) {
		return String.format("%.2f MB", bytes / MB);
	}
}
