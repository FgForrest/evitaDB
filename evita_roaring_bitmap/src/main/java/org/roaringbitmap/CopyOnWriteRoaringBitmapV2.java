/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import java.io.DataInput;
import java.io.IOException;
import java.io.ObjectInput;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A mutable RoaringBitmap that supports structural sharing of containers in static binary
 * operations, combined with copy-on-write semantics for mutations.
 *
 * Static binary operations ({@link #or(CopyOnWriteRoaringBitmapV2, CopyOnWriteRoaringBitmapV2)
 * or}, {@link #and and}, {@link #xor xor}, {@link #andNot andNot}) produce new bitmaps that
 * **share unchanged container references** with the inputs, dramatically reducing GC pressure
 * when bitmaps have low key overlap.
 *
 * Single-element mutations ({@link #add(int)}, {@link #remove(int)}, {@link #flip(int)})
 * use copy-on-write: shared containers are cloned on first write, so the original bitmap
 * (and any other bitmaps sharing that container) remains unaffected.
 *
 * Bulk mutations (range add/remove/flip, in-place or/and/xor/andNot, lazy or,
 * addN, orNot) use precise per-container COW: only the containers actually touched
 * are cloned, preserving structural sharing for untouched containers.
 *
 * Not thread-safe. Static binary ops mutate the inputs' internal shared-tracking arrays.
 */
public class CopyOnWriteRoaringBitmapV2 extends RoaringBitmap {

  private static final long serialVersionUID = 1L;

  /**
   * Parallel array to `highLowContainer.values[]`.
   * `shared[i] == true` means the container at index i is a borrowed reference
   * from another bitmap and MUST be cloned before any in-place mutation.
   */
  boolean[] shared;

  // -----------------------------------------------------------------------
  // Construction
  // -----------------------------------------------------------------------

  /**
   * Creates an empty bitmap. No containers are shared.
   */
  public CopyOnWriteRoaringBitmapV2() {
    super();
    this.shared = new boolean[RoaringArray.INITIAL_CAPACITY];
  }

  /**
   * Package-private constructor: wraps an existing RoaringArray and shared flags.
   * Used by static binary ops to construct results directly.
   */
  CopyOnWriteRoaringBitmapV2(RoaringArray highLowContainer, boolean[] shared) {
    super(highLowContainer);
    this.shared = shared;
  }

  /**
   * Creates a CopyOnWriteRoaringBitmapV2 from an existing RoaringBitmap.
   * All containers are cloned to decouple from the source.
   *
   * @param source the bitmap to copy from
   * @return a new independent CopyOnWriteRoaringBitmapV2
   */
  public static CopyOnWriteRoaringBitmapV2 fromBitmap(RoaringBitmap source) {
    final RoaringArray src = source.highLowContainer;
    final RoaringArray dst = new RoaringArray(src.size);
    for (int i = 0; i < src.size(); i++) {
      dst.append(src.getKeyAtIndex(i), src.getContainerAtIndex(i).clone());
    }
    return new CopyOnWriteRoaringBitmapV2(dst, new boolean[dst.size]);
  }

  // -----------------------------------------------------------------------
  // Shared array management (private helpers)
  // -----------------------------------------------------------------------

  /**
   * Grows `shared[]` to at least `minCapacity`. New slots default to false.
   */
  private void ensureSharedCapacity(int minCapacity) {
    if (this.shared.length < minCapacity) {
      this.shared = Arrays.copyOf(this.shared, Math.max(minCapacity, this.shared.length * 2));
    }
  }

  /**
   * If the container at index i is shared, clones it and marks it unshared.
   * This is the COW guard -- must be called BEFORE any in-place mutation.
   */
  private void copyIfShared(int i) {
    if (i < this.shared.length && this.shared[i]) {
      highLowContainer.setContainerAtIndex(i,
          highLowContainer.getContainerAtIndex(i).clone());
      this.shared[i] = false;
    }
  }

  /**
   * After `highLowContainer.insertNewKeyValueAt(i, ...)` shifted arrays right,
   * shifts the `shared[]` array right too. The newly inserted slot is marked unshared.
   */
  private void sharedInsertAt(int i) {
    sharedInsertAt(i, false);
  }

  private void sharedInsertAt(int i, boolean isShared) {
    final int size = highLowContainer.size(); // already incremented
    ensureSharedCapacity(size);
    System.arraycopy(this.shared, i, this.shared, i + 1, size - i - 1);
    this.shared[i] = isShared;
  }

  /**
   * After `highLowContainer.removeAtIndex(i)` shifted arrays left,
   * shifts the `shared[]` array left too.
   */
  private void sharedRemoveAt(int i) {
    final int size = highLowContainer.size(); // already decremented
    System.arraycopy(this.shared, i + 1, this.shared, i, size - i);
    this.shared[size] = false;
  }

  /**
   * After `highLowContainer.removeIndexRange(begin, end)` shifted arrays left,
   * shifts the `shared[]` array left too.
   */
  private void sharedRemoveRange(int begin, int end) {
    if (end <= begin) {
      return;
    }
    final int newSize = highLowContainer.size(); // already decremented
    System.arraycopy(this.shared, end, this.shared, begin, newSize - begin);
    Arrays.fill(this.shared, newSize, newSize + (end - begin), false);
  }

  /**
   * Marks ALL containers in a bitmap as shared.
   */
  static void markAllShared(CopyOnWriteRoaringBitmapV2 bitmap) {
    final int size = bitmap.highLowContainer.size();
    bitmap.ensureSharedCapacity(size);
    Arrays.fill(bitmap.shared, 0, size, true);
  }

  private static void rangeValidation(final long rangeStart, final long rangeEnd) {
    if (rangeStart < 0 || rangeStart > (1L << 32) - 1) {
      throw new IllegalArgumentException(
          "rangeStart=" + rangeStart + " should be in [0, 0xffffffff]");
    }
    if (rangeEnd > (1L << 32) || rangeEnd < 0) {
      throw new IllegalArgumentException(
          "rangeEnd=" + rangeEnd + " should be in [0, 0xffffffff + 1]");
    }
  }

  /**
   * Borrows a container from x2 at pos2 using structural sharing if x2 is a COW bitmap,
   * or clones it otherwise. Inserts the container into this bitmap at pos1 and updates
   * the shared[] tracking array.
   *
   * @param pos1 insertion index in this bitmap
   * @param s2 the key to insert
   * @param x2 the source bitmap
   * @param pos2 the container index in x2
   * @param cowX2 x2 cast to CopyOnWriteRoaringBitmapV2 (null if x2 is a plain RoaringBitmap)
   * @param length2 the size of x2's container array (used for ensureSharedCapacity)
   */
  private void borrowAndInsert(
      int pos1, char s2,
      RoaringBitmap x2, int pos2,
      CopyOnWriteRoaringBitmapV2 cowX2, int length2) {
    final Container c;
    final boolean isShared;
    if (cowX2 != null) {
      c = cowX2.highLowContainer.getContainerAtIndex(pos2);
      cowX2.ensureSharedCapacity(length2);
      cowX2.shared[pos2] = true;
      isShared = true;
    } else {
      c = x2.highLowContainer.getContainerAtIndex(pos2).clone();
      isShared = false;
    }
    highLowContainer.insertNewKeyValueAt(pos1, s2, c);
    sharedInsertAt(pos1, isShared);
  }

  /**
   * Appends all remaining containers from x2 (from pos2 to length2) to this bitmap,
   * using structural sharing if x2 is a COW bitmap, or cloning otherwise.
   * Called after the main merge loop when this bitmap's keys are exhausted.
   *
   * @param x2 the source bitmap
   * @param pos2 starting index in x2
   * @param length2 ending index (exclusive) in x2
   * @param cowX2 x2 cast to CopyOnWriteRoaringBitmapV2 (null if x2 is a plain RoaringBitmap)
   */
  private void appendTailWithSharing(
      RoaringBitmap x2, int pos2, int length2,
      CopyOnWriteRoaringBitmapV2 cowX2) {
    if (cowX2 != null) {
      final int startSize = highLowContainer.size();
      highLowContainer.append(x2.highLowContainer, pos2, length2);
      final int newSize = highLowContainer.size();
      ensureSharedCapacity(newSize);
      Arrays.fill(this.shared, startSize, newSize, true);
      cowX2.ensureSharedCapacity(length2);
      Arrays.fill(cowX2.shared, pos2, length2, true);
    } else {
      final int startSize = highLowContainer.size();
      highLowContainer.appendCopy(x2.highLowContainer, pos2, length2);
      final int newSize = highLowContainer.size();
      ensureSharedCapacity(newSize);
      Arrays.fill(this.shared, startSize, newSize, false);
    }
  }

  // -----------------------------------------------------------------------
  // Single-element mutation overrides (precise COW)
  // -----------------------------------------------------------------------

  @Override
  public void add(final int x) {
    final char hb = Util.highbits(x);
    final int i = highLowContainer.getIndex(hb);
    if (i >= 0) {
      copyIfShared(i);
      highLowContainer.setContainerAtIndex(
          i, highLowContainer.getContainerAtIndex(i).add(Util.lowbits(x)));
    } else {
      final ArrayContainer newac = new ArrayContainer();
      highLowContainer.insertNewKeyValueAt(-i - 1, hb, newac.add(Util.lowbits(x)));
      sharedInsertAt(-i - 1);
    }
  }

  @Override
  public void remove(final int x) {
    final char hb = Util.highbits(x);
    final int i = highLowContainer.getIndex(hb);
    if (i < 0) {
      return;
    }
    copyIfShared(i);
    highLowContainer.setContainerAtIndex(
        i, highLowContainer.getContainerAtIndex(i).remove(Util.lowbits(x)));
    if (highLowContainer.getContainerAtIndex(i).isEmpty()) {
      highLowContainer.removeAtIndex(i);
      sharedRemoveAt(i);
    }
  }

  @Override
  public void flip(final int x) {
    final char hb = Util.highbits(x);
    final int i = highLowContainer.getIndex(hb);
    if (i >= 0) {
      copyIfShared(i);
      final Container c = highLowContainer.getContainerAtIndex(i).flip(Util.lowbits(x));
      if (!c.isEmpty()) {
        highLowContainer.setContainerAtIndex(i, c);
      } else {
        highLowContainer.removeAtIndex(i);
        sharedRemoveAt(i);
      }
    } else {
      final ArrayContainer newac = new ArrayContainer();
      highLowContainer.insertNewKeyValueAt(-i - 1, hb, newac.add(Util.lowbits(x)));
      sharedInsertAt(-i - 1);
    }
  }

  @Override
  public boolean checkedAdd(final int x) {
    final char hb = Util.highbits(x);
    final int i = highLowContainer.getIndex(hb);
    if (i >= 0) {
      copyIfShared(i);
      final Container c = highLowContainer.getContainerAtIndex(i);
      if (c instanceof RunContainer) {
        if (!c.contains(Util.lowbits(x))) {
          highLowContainer.setContainerAtIndex(i, c.add(Util.lowbits(x)));
          return true;
        }
      } else {
        final int oldCard = c.getCardinality();
        final Container newCont = c.add(Util.lowbits(x));
        highLowContainer.setContainerAtIndex(i, newCont);
        if (newCont.getCardinality() > oldCard) {
          return true;
        }
      }
    } else {
      final ArrayContainer newac = new ArrayContainer();
      highLowContainer.insertNewKeyValueAt(-i - 1, hb, newac.add(Util.lowbits(x)));
      sharedInsertAt(-i - 1);
      return true;
    }
    return false;
  }

  @Override
  public boolean checkedRemove(final int x) {
    final char hb = Util.highbits(x);
    final char lb = Util.lowbits(x);
    final int i = highLowContainer.getIndex(hb);
    if (i < 0) {
      return false;
    }
    copyIfShared(i);
    Container container = highLowContainer.getContainerAtIndex(i);
    final boolean containerNotEmpty;
    if (container instanceof RunContainer) {
      if (container.contains(lb)) {
        container = container.remove(lb);
        containerNotEmpty = !container.isEmpty();
      } else {
        return false;
      }
    } else {
      final int oldCard = container.getCardinality();
      container = container.remove(lb);
      final int newCard = container.getCardinality();
      if (newCard == oldCard) {
        return false;
      }
      containerNotEmpty = newCard > 0;
    }
    if (containerNotEmpty) {
      highLowContainer.setContainerAtIndex(i, container);
    } else {
      highLowContainer.removeAtIndex(i);
      sharedRemoveAt(i);
    }
    return true;
  }

  // -----------------------------------------------------------------------
  // Range/bulk mutation overrides (precise COW)
  // -----------------------------------------------------------------------

  @Override
  public void add(final long rangeStart, final long rangeEnd) {
    rangeValidation(rangeStart, rangeEnd);
    if (rangeStart >= rangeEnd) {
      return;
    }

    final int hbStart = Util.highbits(rangeStart);
    final int lbStart = Util.lowbits(rangeStart);
    final int hbLast = Util.highbits(rangeEnd - 1);
    final int lbLast = Util.lowbits(rangeEnd - 1);
    for (int hb = hbStart; hb <= hbLast; ++hb) {
      final int containerStart = (hb == hbStart) ? lbStart : 0;
      final int containerLast = (hb == hbLast) ? lbLast : Util.maxLowBitAsInteger();
      final int i = highLowContainer.getIndex((char) hb);

      if (i >= 0) {
        copyIfShared(i);
        final Container c =
            highLowContainer.getContainerAtIndex(i).iadd(containerStart, containerLast + 1);
        highLowContainer.setContainerAtIndex(i, c);
      } else {
        highLowContainer.insertNewKeyValueAt(
            -i - 1, (char) hb, Container.rangeOfOnes(containerStart, containerLast + 1));
        sharedInsertAt(-i - 1);
      }
    }
  }

  @Override
  public void addN(final int[] dat, final int offset, final int n) {
    if ((n < 0) || (offset < 0)) {
      throw new IllegalArgumentException("Negative values do not make sense.");
    }
    if (n == 0) {
      return;
    }
    if (offset + n > dat.length) {
      throw new IllegalArgumentException("Data source is too small.");
    }
    Container currentcont = null;
    int j = 0;
    int val = dat[j + offset];
    char currenthb = Util.highbits(val);
    int currentcontainerindex = highLowContainer.getIndex(currenthb);
    if (currentcontainerindex >= 0) {
      copyIfShared(currentcontainerindex);
      currentcont = highLowContainer.getContainerAtIndex(currentcontainerindex);
      final Container newcont = currentcont.add(Util.lowbits(val));
      if (newcont != currentcont) {
        highLowContainer.setContainerAtIndex(currentcontainerindex, newcont);
        currentcont = newcont;
      }
    } else {
      currentcontainerindex = -currentcontainerindex - 1;
      final ArrayContainer newac = new ArrayContainer();
      currentcont = newac.add(Util.lowbits(val));
      highLowContainer.insertNewKeyValueAt(currentcontainerindex, currenthb, currentcont);
      sharedInsertAt(currentcontainerindex);
    }
    j++;
    for (; j < n; ++j) {
      val = dat[j + offset];
      final char newhb = Util.highbits(val);
      if (currenthb == newhb) {
        final Container newcont = currentcont.add(Util.lowbits(val));
        if (newcont != currentcont) {
          highLowContainer.setContainerAtIndex(currentcontainerindex, newcont);
          currentcont = newcont;
        }
      } else {
        currenthb = newhb;
        currentcontainerindex = highLowContainer.getIndex(currenthb);
        if (currentcontainerindex >= 0) {
          copyIfShared(currentcontainerindex);
          currentcont = highLowContainer.getContainerAtIndex(currentcontainerindex);
          final Container newcont = currentcont.add(Util.lowbits(val));
          if (newcont != currentcont) {
            highLowContainer.setContainerAtIndex(currentcontainerindex, newcont);
            currentcont = newcont;
          }
        } else {
          currentcontainerindex = -currentcontainerindex - 1;
          final ArrayContainer newac = new ArrayContainer();
          currentcont = newac.add(Util.lowbits(val));
          highLowContainer.insertNewKeyValueAt(currentcontainerindex, currenthb, currentcont);
          sharedInsertAt(currentcontainerindex);
        }
      }
    }
  }

  @Override
  public void remove(final long rangeStart, final long rangeEnd) {
    rangeValidation(rangeStart, rangeEnd);
    if (rangeStart >= rangeEnd) {
      return;
    }

    final int hbStart = Util.highbits(rangeStart);
    final int lbStart = Util.lowbits(rangeStart);
    final int hbLast = Util.highbits(rangeEnd - 1);
    final int lbLast = Util.lowbits(rangeEnd - 1);
    if (hbStart == hbLast) {
      final int i = highLowContainer.getIndex((char) hbStart);
      if (i < 0) {
        return;
      }
      copyIfShared(i);
      final Container c = highLowContainer.getContainerAtIndex(i).iremove(lbStart, lbLast + 1);
      if (!c.isEmpty()) {
        highLowContainer.setContainerAtIndex(i, c);
      } else {
        highLowContainer.removeAtIndex(i);
        sharedRemoveAt(i);
      }
      return;
    }
    int ifirst = highLowContainer.getIndex((char) hbStart);
    int ilast = highLowContainer.getIndex((char) hbLast);
    if (ifirst >= 0) {
      if (lbStart != 0) {
        copyIfShared(ifirst);
        final Container c =
            highLowContainer
                .getContainerAtIndex(ifirst)
                .iremove(lbStart, Util.maxLowBitAsInteger() + 1);
        if (!c.isEmpty()) {
          highLowContainer.setContainerAtIndex(ifirst, c);
          ifirst++;
        }
      }
    } else {
      ifirst = -ifirst - 1;
    }
    if (ilast >= 0) {
      if (lbLast != Util.maxLowBitAsInteger()) {
        copyIfShared(ilast);
        final Container c = highLowContainer.getContainerAtIndex(ilast).iremove(0, lbLast + 1);
        if (!c.isEmpty()) {
          highLowContainer.setContainerAtIndex(ilast, c);
        } else {
          ilast++;
        }
      } else {
        ilast++;
      }
    } else {
      ilast = -ilast - 1;
    }
    highLowContainer.removeIndexRange(ifirst, ilast);
    sharedRemoveRange(ifirst, ilast);
  }

  @Override
  public void flip(final long rangeStart, final long rangeEnd) {
    rangeValidation(rangeStart, rangeEnd);
    if (rangeStart >= rangeEnd) {
      return;
    }

    final int hbStart = Util.highbits(rangeStart);
    final int lbStart = Util.lowbits(rangeStart);
    final int hbLast = Util.highbits(rangeEnd - 1);
    final int lbLast = Util.lowbits(rangeEnd - 1);

    for (int hb = hbStart; hb <= hbLast; ++hb) {
      final int containerStart = (hb == hbStart) ? lbStart : 0;
      final int containerLast = (hb == hbLast) ? lbLast : Util.maxLowBitAsInteger();
      final int i = highLowContainer.getIndex((char) hb);

      if (i >= 0) {
        copyIfShared(i);
        final Container c =
            highLowContainer.getContainerAtIndex(i).inot(containerStart, containerLast + 1);
        if (!c.isEmpty()) {
          highLowContainer.setContainerAtIndex(i, c);
        } else {
          highLowContainer.removeAtIndex(i);
          sharedRemoveAt(i);
        }
      } else {
        highLowContainer.insertNewKeyValueAt(
            -i - 1, (char) hb, Container.rangeOfOnes(containerStart, containerLast + 1));
        sharedInsertAt(-i - 1);
      }
    }
  }

  @Override
  public void or(final RoaringBitmap x2) {
    if (this == x2) {
      return;
    }
    final CopyOnWriteRoaringBitmapV2 cowX2 = asCow(x2);

    int pos1 = 0, pos2 = 0;
    int length1 = highLowContainer.size();
    final int length2 = x2.highLowContainer.size();
    main:
    if (pos1 < length1 && pos2 < length2) {
      char s1 = highLowContainer.getKeyAtIndex(pos1);
      char s2 = x2.highLowContainer.getKeyAtIndex(pos2);

      while (true) {
        if (s1 == s2) {
          copyIfShared(pos1);
          highLowContainer.setContainerAtIndex(
              pos1,
              highLowContainer
                  .getContainerAtIndex(pos1)
                  .ior(x2.highLowContainer.getContainerAtIndex(pos2)));
          pos1++;
          pos2++;
          if ((pos1 == length1) || (pos2 == length2)) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        } else if (s1 < s2) {
          pos1++;
          if (pos1 == length1) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
        } else {
          borrowAndInsert(pos1, s2, x2, pos2, cowX2, length2);
          pos1++;
          length1++;
          pos2++;
          if (pos2 == length2) {
            break main;
          }
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        }
      }
    }
    if (pos1 == length1) {
      appendTailWithSharing(x2, pos2, length2, cowX2);
    }
  }

  @Override
  public void and(final RoaringBitmap x2) {
    if (x2 == this) {
      return;
    }
    int pos1 = 0, pos2 = 0, intersectionSize = 0;
    final int length1 = highLowContainer.size(), length2 = x2.highLowContainer.size();
    final boolean[] newShared = new boolean[length1];

    while (pos1 < length1 && pos2 < length2) {
      final char s1 = highLowContainer.getKeyAtIndex(pos1);
      final char s2 = x2.highLowContainer.getKeyAtIndex(pos2);
      if (s1 == s2) {
        copyIfShared(pos1);
        final Container c1 = highLowContainer.getContainerAtIndex(pos1);
        final Container c2 = x2.highLowContainer.getContainerAtIndex(pos2);
        final Container c = c1.iand(c2);
        if (!c.isEmpty()) {
          highLowContainer.replaceKeyAndContainerAtIndex(intersectionSize++, s1, c);
        }
        ++pos1;
        ++pos2;
      } else if (s1 < s2) {
        pos1 = highLowContainer.advanceUntil(s2, pos1);
      } else {
        pos2 = x2.highLowContainer.advanceUntil(s1, pos2);
      }
    }
    highLowContainer.resize(intersectionSize);
    this.shared = newShared;
  }

  @Override
  public void xor(final RoaringBitmap x2) {
    if (x2 == this) {
      clear();
      return;
    }
    final CopyOnWriteRoaringBitmapV2 cowX2 = asCow(x2);

    int pos1 = 0, pos2 = 0;
    int length1 = highLowContainer.size();
    final int length2 = x2.highLowContainer.size();

    main:
    if (pos1 < length1 && pos2 < length2) {
      char s1 = highLowContainer.getKeyAtIndex(pos1);
      char s2 = x2.highLowContainer.getKeyAtIndex(pos2);

      while (true) {
        if (s1 == s2) {
          copyIfShared(pos1);
          final Container c =
              highLowContainer
                  .getContainerAtIndex(pos1)
                  .ixor(x2.highLowContainer.getContainerAtIndex(pos2));
          if (!c.isEmpty()) {
            highLowContainer.setContainerAtIndex(pos1, c);
            pos1++;
          } else {
            highLowContainer.removeAtIndex(pos1);
            sharedRemoveAt(pos1);
            --length1;
          }
          pos2++;
          if ((pos1 == length1) || (pos2 == length2)) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        } else if (s1 < s2) {
          pos1++;
          if (pos1 == length1) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
        } else {
          borrowAndInsert(pos1, s2, x2, pos2, cowX2, length2);
          pos1++;
          length1++;
          pos2++;
          if (pos2 == length2) {
            break main;
          }
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        }
      }
    }
    if (pos1 == length1) {
      appendTailWithSharing(x2, pos2, length2, cowX2);
    }
  }

  @Override
  public void andNot(final RoaringBitmap x2) {
    if (x2 == this) {
      clear();
      return;
    }
    int pos1 = 0, pos2 = 0, intersectionSize = 0;
    final int length1 = highLowContainer.size(), length2 = x2.highLowContainer.size();
    final boolean[] newShared = new boolean[length1];

    while (pos1 < length1 && pos2 < length2) {
      final char s1 = highLowContainer.getKeyAtIndex(pos1);
      final char s2 = x2.highLowContainer.getKeyAtIndex(pos2);
      if (s1 == s2) {
        copyIfShared(pos1);
        final Container c1 = highLowContainer.getContainerAtIndex(pos1);
        final Container c2 = x2.highLowContainer.getContainerAtIndex(pos2);
        final Container c = c1.iandNot(c2);
        if (!c.isEmpty()) {
          highLowContainer.replaceKeyAndContainerAtIndex(intersectionSize++, s1, c);
        }
        ++pos1;
        ++pos2;
      } else if (s1 < s2) {
        if (pos1 != intersectionSize) {
          final Container c1 = highLowContainer.getContainerAtIndex(pos1);
          highLowContainer.replaceKeyAndContainerAtIndex(intersectionSize, s1, c1);
        }
        newShared[intersectionSize] = this.shared[pos1];
        ++intersectionSize;
        ++pos1;
      } else {
        pos2 = x2.highLowContainer.advanceUntil(s1, pos2);
      }
    }
    if (pos1 < length1) {
      highLowContainer.copyRange(pos1, length1, intersectionSize);
      System.arraycopy(this.shared, pos1, newShared, intersectionSize, length1 - pos1);
      intersectionSize += length1 - pos1;
    }
    highLowContainer.resize(intersectionSize);
    this.shared = newShared;
  }

  @Override
  public void orNot(final RoaringBitmap other, long rangeEnd) {
    if (other == this) {
      throw new UnsupportedOperationException("orNot between a bitmap and itself?");
    }
    rangeValidation(0, rangeEnd);
    final int maxKey = (int) ((rangeEnd - 1) >>> 16);
    final int lastRun = (rangeEnd & 0xFFFF) == 0 ? 0x10000 : (int) (rangeEnd & 0xFFFF);
    int size = 0;
    int pos1 = 0, pos2 = 0;
    final int length1 = highLowContainer.size(), length2 = other.highLowContainer.size();
    int s1 = length1 > 0 ? highLowContainer.getKeyAtIndex(pos1) : maxKey + 1;
    int s2 = length2 > 0 ? other.highLowContainer.getKeyAtIndex(pos2) : maxKey + 1;
    int remainder = 0;
    for (int i = highLowContainer.size - 1;
        i >= 0 && highLowContainer.keys[i] > maxKey; --i) {
      ++remainder;
    }
    int correction = 0;
    for (int i = 0; i < other.highLowContainer.size - remainder; ++i) {
      correction += other.highLowContainer.getContainerAtIndex(i).isFull() ? 1 : 0;
      if (other.highLowContainer.getKeyAtIndex(i) >= maxKey) {
        break;
      }
    }
    final int maxSize =
        Math.min(maxKey + 1 + remainder - correction + highLowContainer.size, 0x10000);
    if (maxSize == 0) {
      return;
    }
    final char[] newKeys = new char[maxSize];
    final Container[] newValues = new Container[maxSize];
    final boolean[] newShared = new boolean[maxSize];
    for (int key = 0; key <= maxKey && size < maxSize; ++key) {
      if (key == s1 && key == s2) {
        copyIfShared(pos1);
        newValues[size] =
            highLowContainer
                .getContainerAtIndex(pos1)
                .iorNot(
                    other.highLowContainer.getContainerAtIndex(pos2),
                    key == maxKey ? lastRun : 0x10000);
        ++pos1;
        ++pos2;
        s1 = pos1 < length1 ? highLowContainer.getKeyAtIndex(pos1) : maxKey + 1;
        s2 = pos2 < length2 ? other.highLowContainer.getKeyAtIndex(pos2) : maxKey + 1;
      } else if (key == s1) {
        if (key == maxKey) {
          copyIfShared(pos1);
          newValues[size] =
              highLowContainer
                  .getContainerAtIndex(pos1)
                  .ior(RunContainer.rangeOfOnes(0, lastRun));
        } else {
          newValues[size] = RunContainer.full();
        }
        ++pos1;
        s1 = pos1 < length1 ? highLowContainer.getKeyAtIndex(pos1) : maxKey + 1;
      } else if (key == s2) {
        newValues[size] =
            other
                .highLowContainer
                .getContainerAtIndex(pos2)
                .not(0, key == maxKey ? lastRun : 0x10000);
        ++pos2;
        s2 = pos2 < length2 ? other.highLowContainer.getKeyAtIndex(pos2) : maxKey + 1;
      } else {
        newValues[size] =
            key == maxKey ? RunContainer.rangeOfOnes(0, lastRun) : RunContainer.full();
      }
      if (newValues[size].isEmpty()) {
        newValues[size] = null;
      } else {
        newKeys[size] = (char) key;
        ++size;
      }
    }
    if (remainder > 0) {
      final int srcOffset = highLowContainer.size - remainder;
      System.arraycopy(highLowContainer.keys, srcOffset, newKeys, size, remainder);
      System.arraycopy(highLowContainer.values, srcOffset, newValues, size, remainder);
      System.arraycopy(this.shared, srcOffset, newShared, size, remainder);
    }
    highLowContainer.keys = newKeys;
    highLowContainer.values = newValues;
    highLowContainer.size = size + remainder;
    this.shared = newShared;
  }

  // -----------------------------------------------------------------------
  // Static binary operations with structural sharing
  // -----------------------------------------------------------------------

  /**
   * Bitwise OR (union). Non-overlapping containers are shared by reference.
   *
   * @param x1 first bitmap
   * @param x2 second bitmap
   * @return a new bitmap containing the union
   */
  public static CopyOnWriteRoaringBitmapV2 or(
      final CopyOnWriteRoaringBitmapV2 x1,
      final CopyOnWriteRoaringBitmapV2 x2) {
    markAllShared(x1);
    markAllShared(x2);

    final RoaringArray answer = new RoaringArray();
    int pos1 = 0, pos2 = 0;
    final int length1 = x1.highLowContainer.size(), length2 = x2.highLowContainer.size();

    main:
    if (pos1 < length1 && pos2 < length2) {
      char s1 = x1.highLowContainer.getKeyAtIndex(pos1);
      char s2 = x2.highLowContainer.getKeyAtIndex(pos2);

      while (true) {
        if (s1 == s2) {
          answer.append(s1,
              x1.highLowContainer.getContainerAtIndex(pos1)
                  .or(x2.highLowContainer.getContainerAtIndex(pos2)));
          pos1++;
          pos2++;
          if ((pos1 == length1) || (pos2 == length2)) {
            break main;
          }
          s1 = x1.highLowContainer.getKeyAtIndex(pos1);
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        } else if (s1 < s2) {
          answer.append(s1, x1.highLowContainer.getContainerAtIndex(pos1));
          pos1++;
          if (pos1 == length1) {
            break main;
          }
          s1 = x1.highLowContainer.getKeyAtIndex(pos1);
        } else {
          answer.append(s2, x2.highLowContainer.getContainerAtIndex(pos2));
          pos2++;
          if (pos2 == length2) {
            break main;
          }
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        }
      }
    }
    if (pos1 == length1) {
      answer.append(x2.highLowContainer, pos2, length2);
    } else if (pos2 == length2) {
      answer.append(x1.highLowContainer, pos1, length1);
    }

    return newAllSharedResult(answer);
  }

  /**
   * Bitwise AND (intersection). Only overlapping keys produce results,
   * so no structural sharing is possible -- all containers are freshly computed.
   *
   * @param x1 first bitmap
   * @param x2 second bitmap
   * @return a new bitmap containing the intersection
   */
  public static CopyOnWriteRoaringBitmapV2 and(
      final CopyOnWriteRoaringBitmapV2 x1,
      final CopyOnWriteRoaringBitmapV2 x2) {
    final RoaringArray answer = new RoaringArray();
    final int length1 = x1.highLowContainer.size(), length2 = x2.highLowContainer.size();
    int pos1 = 0, pos2 = 0;

    while (pos1 < length1 && pos2 < length2) {
      final char s1 = x1.highLowContainer.getKeyAtIndex(pos1);
      final char s2 = x2.highLowContainer.getKeyAtIndex(pos2);
      if (s1 == s2) {
        final Container c = x1.highLowContainer.getContainerAtIndex(pos1)
            .and(x2.highLowContainer.getContainerAtIndex(pos2));
        if (!c.isEmpty()) {
          answer.append(s1, c);
        }
        ++pos1;
        ++pos2;
      } else if (s1 < s2) {
        pos1 = x1.highLowContainer.advanceUntil(s2, pos1);
      } else {
        pos2 = x2.highLowContainer.advanceUntil(s1, pos2);
      }
    }
    return new CopyOnWriteRoaringBitmapV2(answer, new boolean[answer.size()]);
  }

  /**
   * Bitwise ANDNOT (difference). Non-overlapping containers from x1
   * are shared by reference.
   *
   * @param x1 first bitmap
   * @param x2 second bitmap
   * @return a new bitmap containing x1 minus x2
   */
  public static CopyOnWriteRoaringBitmapV2 andNot(
      final CopyOnWriteRoaringBitmapV2 x1,
      final CopyOnWriteRoaringBitmapV2 x2) {
    markAllShared(x1);

    final RoaringArray answer = new RoaringArray();
    int pos1 = 0, pos2 = 0;
    final int length1 = x1.highLowContainer.size(), length2 = x2.highLowContainer.size();

    while (pos1 < length1 && pos2 < length2) {
      final char s1 = x1.highLowContainer.getKeyAtIndex(pos1);
      final char s2 = x2.highLowContainer.getKeyAtIndex(pos2);
      if (s1 == s2) {
        final Container c = x1.highLowContainer.getContainerAtIndex(pos1)
            .andNot(x2.highLowContainer.getContainerAtIndex(pos2));
        if (!c.isEmpty()) {
          answer.append(s1, c);
        }
        ++pos1;
        ++pos2;
      } else if (s1 < s2) {
        final int nextPos1 = x1.highLowContainer.advanceUntil(s2, pos1);
        answer.append(x1.highLowContainer, pos1, nextPos1);
        pos1 = nextPos1;
      } else {
        pos2 = x2.highLowContainer.advanceUntil(s1, pos2);
      }
    }
    if (pos2 == length2) {
      answer.append(x1.highLowContainer, pos1, length1);
    }

    return newAllSharedResult(answer);
  }

  /**
   * Bitwise XOR (symmetric difference). Non-overlapping containers from both
   * inputs are shared by reference.
   *
   * @param x1 first bitmap
   * @param x2 second bitmap
   * @return a new bitmap containing the symmetric difference
   */
  public static CopyOnWriteRoaringBitmapV2 xor(
      final CopyOnWriteRoaringBitmapV2 x1,
      final CopyOnWriteRoaringBitmapV2 x2) {
    markAllShared(x1);
    markAllShared(x2);

    final RoaringArray answer = new RoaringArray();
    int pos1 = 0, pos2 = 0;
    final int length1 = x1.highLowContainer.size(), length2 = x2.highLowContainer.size();

    main:
    if (pos1 < length1 && pos2 < length2) {
      char s1 = x1.highLowContainer.getKeyAtIndex(pos1);
      char s2 = x2.highLowContainer.getKeyAtIndex(pos2);

      while (true) {
        if (s1 == s2) {
          final Container c =
              x1.highLowContainer.getContainerAtIndex(pos1)
                  .xor(x2.highLowContainer.getContainerAtIndex(pos2));
          if (!c.isEmpty()) {
            answer.append(s1, c);
          }
          pos1++;
          pos2++;
          if ((pos1 == length1) || (pos2 == length2)) {
            break main;
          }
          s1 = x1.highLowContainer.getKeyAtIndex(pos1);
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        } else if (s1 < s2) {
          answer.append(s1, x1.highLowContainer.getContainerAtIndex(pos1));
          pos1++;
          if (pos1 == length1) {
            break main;
          }
          s1 = x1.highLowContainer.getKeyAtIndex(pos1);
        } else {
          answer.append(s2, x2.highLowContainer.getContainerAtIndex(pos2));
          pos2++;
          if (pos2 == length2) {
            break main;
          }
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        }
      }
    }
    if (pos1 == length1) {
      answer.append(x2.highLowContainer, pos2, length2);
    } else if (pos2 == length2) {
      answer.append(x1.highLowContainer, pos1, length1);
    }

    return newAllSharedResult(answer);
  }

  // -----------------------------------------------------------------------
  // Lazy aggregation overrides (precise COW)
  // -----------------------------------------------------------------------

  @Override
  protected void lazyor(final RoaringBitmap x2) {
    if (this == x2) {
      return;
    }
    final CopyOnWriteRoaringBitmapV2 cowX2 = asCow(x2);

    int pos1 = 0, pos2 = 0;
    int length1 = highLowContainer.size();
    final int length2 = x2.highLowContainer.size();
    main:
    if (pos1 < length1 && pos2 < length2) {
      char s1 = highLowContainer.getKeyAtIndex(pos1);
      char s2 = x2.highLowContainer.getKeyAtIndex(pos2);

      while (true) {
        if (s1 == s2) {
          copyIfShared(pos1);
          highLowContainer.setContainerAtIndex(
              pos1,
              highLowContainer
                  .getContainerAtIndex(pos1)
                  .lazyIOR(x2.highLowContainer.getContainerAtIndex(pos2)));
          pos1++;
          pos2++;
          if ((pos1 == length1) || (pos2 == length2)) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        } else if (s1 < s2) {
          pos1++;
          if (pos1 == length1) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
        } else {
          borrowAndInsert(pos1, s2, x2, pos2, cowX2, length2);
          pos1++;
          length1++;
          pos2++;
          if (pos2 == length2) {
            break main;
          }
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        }
      }
    }
    if (pos1 == length1) {
      appendTailWithSharing(x2, pos2, length2, cowX2);
    }
  }

  @Override
  protected void naivelazyor(RoaringBitmap x2) {
    if (this == x2) {
      return;
    }
    final CopyOnWriteRoaringBitmapV2 cowX2 = asCow(x2);

    int pos1 = 0, pos2 = 0;
    int length1 = highLowContainer.size();
    final int length2 = x2.highLowContainer.size();
    main:
    if (pos1 < length1 && pos2 < length2) {
      char s1 = highLowContainer.getKeyAtIndex(pos1);
      char s2 = x2.highLowContainer.getKeyAtIndex(pos2);

      while (true) {
        if (s1 == s2) {
          copyIfShared(pos1);
          final BitmapContainer c1 =
              highLowContainer.getContainerAtIndex(pos1).toBitmapContainer();
          highLowContainer.setContainerAtIndex(
              pos1, c1.lazyIOR(x2.highLowContainer.getContainerAtIndex(pos2)));
          pos1++;
          pos2++;
          if ((pos1 == length1) || (pos2 == length2)) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        } else if (s1 < s2) {
          pos1++;
          if (pos1 == length1) {
            break main;
          }
          s1 = highLowContainer.getKeyAtIndex(pos1);
        } else {
          borrowAndInsert(pos1, s2, x2, pos2, cowX2, length2);
          pos1++;
          length1++;
          pos2++;
          if (pos2 == length2) {
            break main;
          }
          s2 = x2.highLowContainer.getKeyAtIndex(pos2);
        }
      }
    }
    if (pos1 == length1) {
      appendTailWithSharing(x2, pos2, length2, cowX2);
    }
  }

  @Override
  protected void repairAfterLazy() {
    for (int k = 0; k < highLowContainer.size(); ++k) {
      copyIfShared(k);
      final Container c = highLowContainer.getContainerAtIndex(k);
      highLowContainer.setContainerAtIndex(k, c.repairAfterLazy());
    }
  }

  // -----------------------------------------------------------------------
  // Deserialization and append overrides
  // -----------------------------------------------------------------------

  @Override
  public void deserialize(DataInput in) throws IOException {
    super.deserialize(in);
    this.shared = new boolean[highLowContainer.size()];
  }

  @Override
  public void deserialize(DataInput in, byte[] buffer) throws IOException {
    super.deserialize(in, buffer);
    this.shared = new boolean[highLowContainer.size()];
  }

  @Override
  public void deserialize(ByteBuffer bbf) throws IOException {
    super.deserialize(bbf);
    this.shared = new boolean[highLowContainer.size()];
  }

  @Override
  public void append(char key, Container container) {
    super.append(key, container);
    ensureSharedCapacity(highLowContainer.size());
  }

  // -----------------------------------------------------------------------
  // clone / clear / trim / serialization
  // -----------------------------------------------------------------------

  /**
   * Shallow clone: shares all containers between this and the clone via COW.
   * Both sides mark everything as shared, so the first mutator on either side
   * will clone only the affected container.
   */
  @Override
  public CopyOnWriteRoaringBitmapV2 clone() {
    final int size = highLowContainer.size();
    final char[] newKeys = Arrays.copyOf(highLowContainer.keys, size);
    final Container[] newValues = Arrays.copyOf(highLowContainer.values, size);
    final RoaringArray clonedArray = new RoaringArray(newKeys, newValues, size);

    // Mark everything shared in both this and the clone
    ensureSharedCapacity(size);
    Arrays.fill(this.shared, 0, size, true);
    final boolean[] cloneShared = new boolean[size];
    Arrays.fill(cloneShared, true);

    return new CopyOnWriteRoaringBitmapV2(clonedArray, cloneShared);
  }

  @Override
  public void clear() {
    super.clear();
    this.shared = new boolean[RoaringArray.INITIAL_CAPACITY];
  }

  @Override
  public void trim() {
    super.trim();
    this.shared = Arrays.copyOf(this.shared, highLowContainer.size());
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException {
    super.readExternal(in);
    this.shared = new boolean[highLowContainer.size()];
  }

  // -----------------------------------------------------------------------
  // Private utility methods
  // -----------------------------------------------------------------------

  /**
   * Casts a RoaringBitmap to CopyOnWriteRoaringBitmapV2 if it is one, otherwise returns null.
   */
  private static CopyOnWriteRoaringBitmapV2 asCow(RoaringBitmap bitmap) {
    return (bitmap instanceof CopyOnWriteRoaringBitmapV2)
        ? (CopyOnWriteRoaringBitmapV2) bitmap
        : null;
  }

  /**
   * Creates a new CopyOnWriteRoaringBitmapV2 from a RoaringArray with all containers
   * marked as shared. Used by static binary operations that produce shared results.
   */
  private static CopyOnWriteRoaringBitmapV2 newAllSharedResult(RoaringArray answer) {
    final boolean[] resultShared = new boolean[answer.size()];
    Arrays.fill(resultShared, true);
    return new CopyOnWriteRoaringBitmapV2(answer, resultShared);
  }

  // -----------------------------------------------------------------------
  // Package-private access for tests
  // -----------------------------------------------------------------------

  /**
   * Returns the container at the given index. Package-private for testing structural sharing.
   */
  Container getContainerAtIndex(int i) {
    return highLowContainer.getContainerAtIndex(i);
  }

  /**
   * Returns the key at the given index. Package-private for testing.
   */
  char getKeyAtIndex(int i) {
    return highLowContainer.getKeyAtIndex(i);
  }

  /**
   * Returns whether the container at the given index is marked as shared.
   * Package-private for testing.
   */
  boolean isShared(int i) {
    return i < this.shared.length && this.shared[i];
  }
}
