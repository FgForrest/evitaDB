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
// source: GrpcSortableAttributeCompoundSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Mutation is responsible for setting set of scopes for indexing value in a `SortableAttributeCompoundSchema` in `EntitySchema`.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation}
 */
public final class GrpcSetSortableAttributeCompoundIndexedMutation extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation)
    GrpcSetSortableAttributeCompoundIndexedMutationOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcSetSortableAttributeCompoundIndexedMutation.newBuilder() to construct.
  private GrpcSetSortableAttributeCompoundIndexedMutation(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcSetSortableAttributeCompoundIndexedMutation() {
    name_ = "";
    indexedInScopes_ = java.util.Collections.emptyList();
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcSetSortableAttributeCompoundIndexedMutation();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcSetSortableAttributeCompoundIndexedMutation(
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
            java.lang.String s = input.readStringRequireUtf8();

            name_ = s;
            break;
          }
          case 16: {
            int rawValue = input.readEnum();
            if (!((mutable_bitField0_ & 0x00000001) != 0)) {
              indexedInScopes_ = new java.util.ArrayList<java.lang.Integer>();
              mutable_bitField0_ |= 0x00000001;
            }
            indexedInScopes_.add(rawValue);
            break;
          }
          case 18: {
            int length = input.readRawVarint32();
            int oldLimit = input.pushLimit(length);
            while(input.getBytesUntilLimit() > 0) {
              int rawValue = input.readEnum();
              if (!((mutable_bitField0_ & 0x00000001) != 0)) {
                indexedInScopes_ = new java.util.ArrayList<java.lang.Integer>();
                mutable_bitField0_ |= 0x00000001;
              }
              indexedInScopes_.add(rawValue);
            }
            input.popLimit(oldLimit);
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
      if (((mutable_bitField0_ & 0x00000001) != 0)) {
        indexedInScopes_ = java.util.Collections.unmodifiableList(indexedInScopes_);
      }
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.class, io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.Builder.class);
  }

  public static final int NAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object name_;
  /**
   * <pre>
   * Name of the sortable attribute compound the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  @java.lang.Override
  public java.lang.String getName() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      name_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * Name of the sortable attribute compound the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getNameBytes() {
    java.lang.Object ref = name_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      name_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int INDEXEDINSCOPES_FIELD_NUMBER = 2;
  private java.util.List<java.lang.Integer> indexedInScopes_;
  private static final com.google.protobuf.Internal.ListAdapter.Converter<
      java.lang.Integer, io.evitadb.externalApi.grpc.generated.GrpcEntityScope> indexedInScopes_converter_ =
          new com.google.protobuf.Internal.ListAdapter.Converter<
              java.lang.Integer, io.evitadb.externalApi.grpc.generated.GrpcEntityScope>() {
            public io.evitadb.externalApi.grpc.generated.GrpcEntityScope convert(java.lang.Integer from) {
              @SuppressWarnings("deprecation")
              io.evitadb.externalApi.grpc.generated.GrpcEntityScope result = io.evitadb.externalApi.grpc.generated.GrpcEntityScope.valueOf(from);
              return result == null ? io.evitadb.externalApi.grpc.generated.GrpcEntityScope.UNRECOGNIZED : result;
            }
          };
  /**
   * <pre>
   * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
   * This property contains set of all scopes this attribute compound is indexed in.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
   * @return A list containing the indexedInScopes.
   */
  @java.lang.Override
  public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityScope> getIndexedInScopesList() {
    return new com.google.protobuf.Internal.ListAdapter<
        java.lang.Integer, io.evitadb.externalApi.grpc.generated.GrpcEntityScope>(indexedInScopes_, indexedInScopes_converter_);
  }
  /**
   * <pre>
   * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
   * This property contains set of all scopes this attribute compound is indexed in.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
   * @return The count of indexedInScopes.
   */
  @java.lang.Override
  public int getIndexedInScopesCount() {
    return indexedInScopes_.size();
  }
  /**
   * <pre>
   * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
   * This property contains set of all scopes this attribute compound is indexed in.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
   * @param index The index of the element to return.
   * @return The indexedInScopes at the given index.
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcEntityScope getIndexedInScopes(int index) {
    return indexedInScopes_converter_.convert(indexedInScopes_.get(index));
  }
  /**
   * <pre>
   * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
   * This property contains set of all scopes this attribute compound is indexed in.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
   * @return A list containing the enum numeric values on the wire for indexedInScopes.
   */
  @java.lang.Override
  public java.util.List<java.lang.Integer>
  getIndexedInScopesValueList() {
    return indexedInScopes_;
  }
  /**
   * <pre>
   * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
   * This property contains set of all scopes this attribute compound is indexed in.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of indexedInScopes at the given index.
   */
  @java.lang.Override
  public int getIndexedInScopesValue(int index) {
    return indexedInScopes_.get(index);
  }
  private int indexedInScopesMemoizedSerializedSize;

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
    getSerializedSize();
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, name_);
    }
    if (getIndexedInScopesList().size() > 0) {
      output.writeUInt32NoTag(18);
      output.writeUInt32NoTag(indexedInScopesMemoizedSerializedSize);
    }
    for (int i = 0; i < indexedInScopes_.size(); i++) {
      output.writeEnumNoTag(indexedInScopes_.get(i));
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, name_);
    }
    {
      int dataSize = 0;
      for (int i = 0; i < indexedInScopes_.size(); i++) {
        dataSize += com.google.protobuf.CodedOutputStream
          .computeEnumSizeNoTag(indexedInScopes_.get(i));
      }
      size += dataSize;
      if (!getIndexedInScopesList().isEmpty()) {  size += 1;
        size += com.google.protobuf.CodedOutputStream
          .computeUInt32SizeNoTag(dataSize);
      }indexedInScopesMemoizedSerializedSize = dataSize;
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation other = (io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation) obj;

    if (!getName()
        .equals(other.getName())) return false;
    if (!indexedInScopes_.equals(other.indexedInScopes_)) return false;
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
    hash = (37 * hash) + NAME_FIELD_NUMBER;
    hash = (53 * hash) + getName().hashCode();
    if (getIndexedInScopesCount() > 0) {
      hash = (37 * hash) + INDEXEDINSCOPES_FIELD_NUMBER;
      hash = (53 * hash) + indexedInScopes_.hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation prototype) {
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
   * Mutation is responsible for setting set of scopes for indexing value in a `SortableAttributeCompoundSchema` in `EntitySchema`.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation)
      io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutationOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.class, io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.newBuilder()
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
      name_ = "";

      indexedInScopes_ = java.util.Collections.emptyList();
      bitField0_ = (bitField0_ & ~0x00000001);
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation build() {
      io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation result = new io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation(this);
      int from_bitField0_ = bitField0_;
      result.name_ = name_;
      if (((bitField0_ & 0x00000001) != 0)) {
        indexedInScopes_ = java.util.Collections.unmodifiableList(indexedInScopes_);
        bitField0_ = (bitField0_ & ~0x00000001);
      }
      result.indexedInScopes_ = indexedInScopes_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation.getDefaultInstance()) return this;
      if (!other.getName().isEmpty()) {
        name_ = other.name_;
        onChanged();
      }
      if (!other.indexedInScopes_.isEmpty()) {
        if (indexedInScopes_.isEmpty()) {
          indexedInScopes_ = other.indexedInScopes_;
          bitField0_ = (bitField0_ & ~0x00000001);
        } else {
          ensureIndexedInScopesIsMutable();
          indexedInScopes_.addAll(other.indexedInScopes_);
        }
        onChanged();
      }
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
      io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private java.lang.Object name_ = "";
    /**
     * <pre>
     * Name of the sortable attribute compound the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @return The name.
     */
    public java.lang.String getName() {
      java.lang.Object ref = name_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        name_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * Name of the sortable attribute compound the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @return The bytes for name.
     */
    public com.google.protobuf.ByteString
        getNameBytes() {
      java.lang.Object ref = name_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        name_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * Name of the sortable attribute compound the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @param value The name to set.
     * @return This builder for chaining.
     */
    public Builder setName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      name_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the sortable attribute compound the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearName() {

      name_ = getDefaultInstance().getName();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the sortable attribute compound the mutation is targeting.
     * </pre>
     *
     * <code>string name = 1;</code>
     * @param value The bytes for name to set.
     * @return This builder for chaining.
     */
    public Builder setNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      name_ = value;
      onChanged();
      return this;
    }

    private java.util.List<java.lang.Integer> indexedInScopes_ =
      java.util.Collections.emptyList();
    private void ensureIndexedInScopesIsMutable() {
      if (!((bitField0_ & 0x00000001) != 0)) {
        indexedInScopes_ = new java.util.ArrayList<java.lang.Integer>(indexedInScopes_);
        bitField0_ |= 0x00000001;
      }
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @return A list containing the indexedInScopes.
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityScope> getIndexedInScopesList() {
      return new com.google.protobuf.Internal.ListAdapter<
          java.lang.Integer, io.evitadb.externalApi.grpc.generated.GrpcEntityScope>(indexedInScopes_, indexedInScopes_converter_);
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @return The count of indexedInScopes.
     */
    public int getIndexedInScopesCount() {
      return indexedInScopes_.size();
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param index The index of the element to return.
     * @return The indexedInScopes at the given index.
     */
    public io.evitadb.externalApi.grpc.generated.GrpcEntityScope getIndexedInScopes(int index) {
      return indexedInScopes_converter_.convert(indexedInScopes_.get(index));
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param index The index to set the value at.
     * @param value The indexedInScopes to set.
     * @return This builder for chaining.
     */
    public Builder setIndexedInScopes(
        int index, io.evitadb.externalApi.grpc.generated.GrpcEntityScope value) {
      if (value == null) {
        throw new NullPointerException();
      }
      ensureIndexedInScopesIsMutable();
      indexedInScopes_.set(index, value.getNumber());
      onChanged();
      return this;
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param value The indexedInScopes to add.
     * @return This builder for chaining.
     */
    public Builder addIndexedInScopes(io.evitadb.externalApi.grpc.generated.GrpcEntityScope value) {
      if (value == null) {
        throw new NullPointerException();
      }
      ensureIndexedInScopesIsMutable();
      indexedInScopes_.add(value.getNumber());
      onChanged();
      return this;
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param values The indexedInScopes to add.
     * @return This builder for chaining.
     */
    public Builder addAllIndexedInScopes(
        java.lang.Iterable<? extends io.evitadb.externalApi.grpc.generated.GrpcEntityScope> values) {
      ensureIndexedInScopesIsMutable();
      for (io.evitadb.externalApi.grpc.generated.GrpcEntityScope value : values) {
        indexedInScopes_.add(value.getNumber());
      }
      onChanged();
      return this;
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearIndexedInScopes() {
      indexedInScopes_ = java.util.Collections.emptyList();
      bitField0_ = (bitField0_ & ~0x00000001);
      onChanged();
      return this;
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @return A list containing the enum numeric values on the wire for indexedInScopes.
     */
    public java.util.List<java.lang.Integer>
    getIndexedInScopesValueList() {
      return java.util.Collections.unmodifiableList(indexedInScopes_);
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param index The index of the value to return.
     * @return The enum numeric value on the wire of indexedInScopes at the given index.
     */
    public int getIndexedInScopesValue(int index) {
      return indexedInScopes_.get(index);
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param index The index of the value to return.
     * @return The enum numeric value on the wire of indexedInScopes at the given index.
     * @return This builder for chaining.
     */
    public Builder setIndexedInScopesValue(
        int index, int value) {
      ensureIndexedInScopesIsMutable();
      indexedInScopes_.set(index, value);
      onChanged();
      return this;
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param value The enum numeric value on the wire for indexedInScopes to add.
     * @return This builder for chaining.
     */
    public Builder addIndexedInScopesValue(int value) {
      ensureIndexedInScopesIsMutable();
      indexedInScopes_.add(value);
      onChanged();
      return this;
    }
    /**
     * <pre>
     * When attribute sortable compound is indexed, it is possible to sort entities by this calculated attribute compound.
     * This property contains set of all scopes this attribute compound is indexed in.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 2;</code>
     * @param values The enum numeric values on the wire for indexedInScopes to add.
     * @return This builder for chaining.
     */
    public Builder addAllIndexedInScopesValue(
        java.lang.Iterable<java.lang.Integer> values) {
      ensureIndexedInScopesIsMutable();
      for (int value : values) {
        indexedInScopes_.add(value);
      }
      onChanged();
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation)
  private static final io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcSetSortableAttributeCompoundIndexedMutation>
      PARSER = new com.google.protobuf.AbstractParser<GrpcSetSortableAttributeCompoundIndexedMutation>() {
    @java.lang.Override
    public GrpcSetSortableAttributeCompoundIndexedMutation parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcSetSortableAttributeCompoundIndexedMutation(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcSetSortableAttributeCompoundIndexedMutation> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcSetSortableAttributeCompoundIndexedMutation> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcSetSortableAttributeCompoundIndexedMutation getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

