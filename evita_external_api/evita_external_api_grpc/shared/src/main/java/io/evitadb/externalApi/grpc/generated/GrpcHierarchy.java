/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcExtraResults.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Contains list of statistics for the single level (probably root or whatever is filtered by the query) of
 * the queried hierarchy entity.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcHierarchy}
 */
public final class GrpcHierarchy extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcHierarchy)
    GrpcHierarchyOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcHierarchy.newBuilder() to construct.
  private GrpcHierarchy(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcHierarchy() {
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcHierarchy();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcHierarchy(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    int mutable_bitField0_ = 0;
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 10: {
            if (!((mutable_bitField0_ & 0x00000001) != 0)) {
              hierarchy_ = com.google.protobuf.MapField.newMapField(
                  HierarchyDefaultEntryHolder.defaultEntry);
              mutable_bitField0_ |= 0x00000001;
            }
            com.google.protobuf.MapEntry<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>
            hierarchy__ = input.readMessage(
                HierarchyDefaultEntryHolder.defaultEntry.getParserForType(), extensionRegistry);
            hierarchy_.getMutableMap().put(
                hierarchy__.getKey(), hierarchy__.getValue());
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor;
  }

  @SuppressWarnings({"rawtypes"})
  @java.lang.Override
  protected com.google.protobuf.MapField internalGetMapField(
      int number) {
    switch (number) {
      case 1:
        return internalGetHierarchy();
      default:
        throw new RuntimeException(
            "Invalid map field number: " + number);
    }
  }
  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcHierarchy.class, io.evitadb.externalApi.grpc.generated.GrpcHierarchy.Builder.class);
  }

  public static final int HIERARCHY_FIELD_NUMBER = 1;
  private static final class HierarchyDefaultEntryHolder {
    static final com.google.protobuf.MapEntry<
        java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> defaultEntry =
            com.google.protobuf.MapEntry
            .<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>newDefaultInstance(
                io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_HierarchyEntry_descriptor,
                com.google.protobuf.WireFormat.FieldType.STRING,
                "",
                com.google.protobuf.WireFormat.FieldType.MESSAGE,
                io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.getDefaultInstance());
  }
  private com.google.protobuf.MapField<
      java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> hierarchy_;
  private com.google.protobuf.MapField<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>
  internalGetHierarchy() {
    if (hierarchy_ == null) {
      return com.google.protobuf.MapField.emptyMapField(
          HierarchyDefaultEntryHolder.defaultEntry);
    }
    return hierarchy_;
  }

  public int getHierarchyCount() {
    return internalGetHierarchy().getMap().size();
  }
  /**
   * <pre>
   * Map holds the statistics represented by user-specified output name of requested hierarchy.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
   */

  @java.lang.Override
  public boolean containsHierarchy(
      java.lang.String key) {
    if (key == null) { throw new NullPointerException("map key"); }
    return internalGetHierarchy().getMap().containsKey(key);
  }
  /**
   * Use {@link #getHierarchyMap()} instead.
   */
  @java.lang.Override
  @java.lang.Deprecated
  public java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> getHierarchy() {
    return getHierarchyMap();
  }
  /**
   * <pre>
   * Map holds the statistics represented by user-specified output name of requested hierarchy.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
   */
  @java.lang.Override

  public java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> getHierarchyMap() {
    return internalGetHierarchy().getMap();
  }
  /**
   * <pre>
   * Map holds the statistics represented by user-specified output name of requested hierarchy.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
   */
  @java.lang.Override

  public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getHierarchyOrDefault(
      java.lang.String key,
      io.evitadb.externalApi.grpc.generated.GrpcLevelInfos defaultValue) {
    if (key == null) { throw new NullPointerException("map key"); }
    java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> map =
        internalGetHierarchy().getMap();
    return map.containsKey(key) ? map.get(key) : defaultValue;
  }
  /**
   * <pre>
   * Map holds the statistics represented by user-specified output name of requested hierarchy.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
   */
  @java.lang.Override

  public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getHierarchyOrThrow(
      java.lang.String key) {
    if (key == null) { throw new NullPointerException("map key"); }
    java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> map =
        internalGetHierarchy().getMap();
    if (!map.containsKey(key)) {
      throw new java.lang.IllegalArgumentException();
    }
    return map.get(key);
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    com.google.protobuf.GeneratedMessageV3
      .serializeStringMapTo(
        output,
        internalGetHierarchy(),
        HierarchyDefaultEntryHolder.defaultEntry,
        1);
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    for (java.util.Map.Entry<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> entry
         : internalGetHierarchy().getMap().entrySet()) {
      com.google.protobuf.MapEntry<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>
      hierarchy__ = HierarchyDefaultEntryHolder.defaultEntry.newBuilderForType()
          .setKey(entry.getKey())
          .setValue(entry.getValue())
          .build();
      size += com.google.protobuf.CodedOutputStream
          .computeMessageSize(1, hierarchy__);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcHierarchy)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcHierarchy other = (io.evitadb.externalApi.grpc.generated.GrpcHierarchy) obj;

    if (!internalGetHierarchy().equals(
        other.internalGetHierarchy())) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    if (!internalGetHierarchy().getMap().isEmpty()) {
      hash = (37 * hash) + HIERARCHY_FIELD_NUMBER;
      hash = (53 * hash) + internalGetHierarchy().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcHierarchy prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Contains list of statistics for the single level (probably root or whatever is filtered by the query) of
   * the queried hierarchy entity.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcHierarchy}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcHierarchy)
      io.evitadb.externalApi.grpc.generated.GrpcHierarchyOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor;
    }

    @SuppressWarnings({"rawtypes"})
    protected com.google.protobuf.MapField internalGetMapField(
        int number) {
      switch (number) {
        case 1:
          return internalGetHierarchy();
        default:
          throw new RuntimeException(
              "Invalid map field number: " + number);
      }
    }
    @SuppressWarnings({"rawtypes"})
    protected com.google.protobuf.MapField internalGetMutableMapField(
        int number) {
      switch (number) {
        case 1:
          return internalGetMutableHierarchy();
        default:
          throw new RuntimeException(
              "Invalid map field number: " + number);
      }
    }
    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcHierarchy.class, io.evitadb.externalApi.grpc.generated.GrpcHierarchy.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcHierarchy.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      internalGetMutableHierarchy().clear();
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcHierarchy_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcHierarchy getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcHierarchy.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcHierarchy build() {
      io.evitadb.externalApi.grpc.generated.GrpcHierarchy result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcHierarchy buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcHierarchy result = new io.evitadb.externalApi.grpc.generated.GrpcHierarchy(this);
      int from_bitField0_ = bitField0_;
      result.hierarchy_ = internalGetHierarchy();
      result.hierarchy_.makeImmutable();
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcHierarchy) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcHierarchy)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcHierarchy other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcHierarchy.getDefaultInstance()) return this;
      internalGetMutableHierarchy().mergeFrom(
          other.internalGetHierarchy());
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcHierarchy parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcHierarchy) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private com.google.protobuf.MapField<
        java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> hierarchy_;
    private com.google.protobuf.MapField<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>
    internalGetHierarchy() {
      if (hierarchy_ == null) {
        return com.google.protobuf.MapField.emptyMapField(
            HierarchyDefaultEntryHolder.defaultEntry);
      }
      return hierarchy_;
    }
    private com.google.protobuf.MapField<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>
    internalGetMutableHierarchy() {
      onChanged();;
      if (hierarchy_ == null) {
        hierarchy_ = com.google.protobuf.MapField.newMapField(
            HierarchyDefaultEntryHolder.defaultEntry);
      }
      if (!hierarchy_.isMutable()) {
        hierarchy_ = hierarchy_.copy();
      }
      return hierarchy_;
    }

    public int getHierarchyCount() {
      return internalGetHierarchy().getMap().size();
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */

    @java.lang.Override
    public boolean containsHierarchy(
        java.lang.String key) {
      if (key == null) { throw new NullPointerException("map key"); }
      return internalGetHierarchy().getMap().containsKey(key);
    }
    /**
     * Use {@link #getHierarchyMap()} instead.
     */
    @java.lang.Override
    @java.lang.Deprecated
    public java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> getHierarchy() {
      return getHierarchyMap();
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */
    @java.lang.Override

    public java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> getHierarchyMap() {
      return internalGetHierarchy().getMap();
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */
    @java.lang.Override

    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getHierarchyOrDefault(
        java.lang.String key,
        io.evitadb.externalApi.grpc.generated.GrpcLevelInfos defaultValue) {
      if (key == null) { throw new NullPointerException("map key"); }
      java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> map =
          internalGetHierarchy().getMap();
      return map.containsKey(key) ? map.get(key) : defaultValue;
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */
    @java.lang.Override

    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getHierarchyOrThrow(
        java.lang.String key) {
      if (key == null) { throw new NullPointerException("map key"); }
      java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> map =
          internalGetHierarchy().getMap();
      if (!map.containsKey(key)) {
        throw new java.lang.IllegalArgumentException();
      }
      return map.get(key);
    }

    public Builder clearHierarchy() {
      internalGetMutableHierarchy().getMutableMap()
          .clear();
      return this;
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */

    public Builder removeHierarchy(
        java.lang.String key) {
      if (key == null) { throw new NullPointerException("map key"); }
      internalGetMutableHierarchy().getMutableMap()
          .remove(key);
      return this;
    }
    /**
     * Use alternate mutation accessors instead.
     */
    @java.lang.Deprecated
    public java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos>
    getMutableHierarchy() {
      return internalGetMutableHierarchy().getMutableMap();
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */
    public Builder putHierarchy(
        java.lang.String key,
        io.evitadb.externalApi.grpc.generated.GrpcLevelInfos value) {
      if (key == null) { throw new NullPointerException("map key"); }
      if (value == null) {
  throw new NullPointerException("map value");
}

      internalGetMutableHierarchy().getMutableMap()
          .put(key, value);
      return this;
    }
    /**
     * <pre>
     * Map holds the statistics represented by user-specified output name of requested hierarchy.
     * </pre>
     *
     * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcLevelInfos&gt; hierarchy = 1;</code>
     */

    public Builder putAllHierarchy(
        java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos> values) {
      internalGetMutableHierarchy().getMutableMap()
          .putAll(values);
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcHierarchy)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcHierarchy)
  private static final io.evitadb.externalApi.grpc.generated.GrpcHierarchy DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcHierarchy();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcHierarchy getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcHierarchy>
      PARSER = new com.google.protobuf.AbstractParser<GrpcHierarchy>() {
    @java.lang.Override
    public GrpcHierarchy parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcHierarchy(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcHierarchy> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcHierarchy> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcHierarchy getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

