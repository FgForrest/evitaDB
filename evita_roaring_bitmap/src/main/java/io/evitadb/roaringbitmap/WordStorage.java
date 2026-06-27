package io.evitadb.roaringbitmap;

public interface WordStorage<T> {

  T add(char value);

  boolean isEmpty();

  T runOptimize();
}
