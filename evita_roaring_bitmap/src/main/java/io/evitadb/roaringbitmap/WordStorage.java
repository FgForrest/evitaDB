package io.evitadb.roaringbitmap;

interface WordStorage<T> {

  T add(char value);

  boolean isEmpty();

  T runOptimize();
}
