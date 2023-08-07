/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
 * This DTO represents a wrapper for array of statistics for the single hierarchy level of inner entities.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcLevelInfos}
 */
public final class GrpcLevelInfos extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcLevelInfos)
    GrpcLevelInfosOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcLevelInfos.newBuilder() to construct.
  private GrpcLevelInfos(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcLevelInfos() {
    levelInfos_ = java.util.Collections.emptyList();
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcLevelInfos();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcLevelInfos(
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
              levelInfos_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo>();
              mutable_bitField0_ |= 0x00000001;
            }
            levelInfos_.add(
                input.readMessage(io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.parser(), extensionRegistry));
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
        levelInfos_ = java.util.Collections.unmodifiableList(levelInfos_);
      }
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.class, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.Builder.class);
  }

  public static final int LEVELINFOS_FIELD_NUMBER = 1;
  private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo> levelInfos_;
  /**
   * <pre>
   * Array of statistics for the single hierarchy level of inner entities.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
   */
  @java.lang.Override
  public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo> getLevelInfosList() {
    return levelInfos_;
  }
  /**
   * <pre>
   * Array of statistics for the single hierarchy level of inner entities.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
   */
  @java.lang.Override
  public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder> 
      getLevelInfosOrBuilderList() {
    return levelInfos_;
  }
  /**
   * <pre>
   * Array of statistics for the single hierarchy level of inner entities.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
   */
  @java.lang.Override
  public int getLevelInfosCount() {
    return levelInfos_.size();
  }
  /**
   * <pre>
   * Array of statistics for the single hierarchy level of inner entities.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLevelInfo getLevelInfos(int index) {
    return levelInfos_.get(index);
  }
  /**
   * <pre>
   * Array of statistics for the single hierarchy level of inner entities.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
   */
  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder getLevelInfosOrBuilder(
      int index) {
    return levelInfos_.get(index);
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
    for (int i = 0; i < levelInfos_.size(); i++) {
      output.writeMessage(1, levelInfos_.get(i));
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    for (int i = 0; i < levelInfos_.size(); i++) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, levelInfos_.get(i));
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcLevelInfos)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcLevelInfos other = (io.evitadb.externalApi.grpc.generated.GrpcLevelInfos) obj;

    if (!getLevelInfosList()
        .equals(other.getLevelInfosList())) return false;
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
    if (getLevelInfosCount() > 0) {
      hash = (37 * hash) + LEVELINFOS_FIELD_NUMBER;
      hash = (53 * hash) + getLevelInfosList().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcLevelInfos prototype) {
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
   * This DTO represents a wrapper for array of statistics for the single hierarchy level of inner entities.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcLevelInfos}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcLevelInfos)
      io.evitadb.externalApi.grpc.generated.GrpcLevelInfosOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.class, io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.newBuilder()
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
        getLevelInfosFieldBuilder();
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      if (levelInfosBuilder_ == null) {
        levelInfos_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
      } else {
        levelInfosBuilder_.clear();
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcExtraResultsOuterClass.internal_static_io_evitadb_externalApi_grpc_generated_GrpcLevelInfos_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos build() {
      io.evitadb.externalApi.grpc.generated.GrpcLevelInfos result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcLevelInfos result = new io.evitadb.externalApi.grpc.generated.GrpcLevelInfos(this);
      int from_bitField0_ = bitField0_;
      if (levelInfosBuilder_ == null) {
        if (((bitField0_ & 0x00000001) != 0)) {
          levelInfos_ = java.util.Collections.unmodifiableList(levelInfos_);
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.levelInfos_ = levelInfos_;
      } else {
        result.levelInfos_ = levelInfosBuilder_.build();
      }
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcLevelInfos) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcLevelInfos)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcLevelInfos other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcLevelInfos.getDefaultInstance()) return this;
      if (levelInfosBuilder_ == null) {
        if (!other.levelInfos_.isEmpty()) {
          if (levelInfos_.isEmpty()) {
            levelInfos_ = other.levelInfos_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureLevelInfosIsMutable();
            levelInfos_.addAll(other.levelInfos_);
          }
          onChanged();
        }
      } else {
        if (!other.levelInfos_.isEmpty()) {
          if (levelInfosBuilder_.isEmpty()) {
            levelInfosBuilder_.dispose();
            levelInfosBuilder_ = null;
            levelInfos_ = other.levelInfos_;
            bitField0_ = (bitField0_ & ~0x00000001);
            levelInfosBuilder_ = 
              com.google.protobuf.GeneratedMessageV3.alwaysUseFieldBuilders ?
                 getLevelInfosFieldBuilder() : null;
          } else {
            levelInfosBuilder_.addAllMessages(other.levelInfos_);
          }
        }
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
      io.evitadb.externalApi.grpc.generated.GrpcLevelInfos parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcLevelInfos) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    private int bitField0_;

    private java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo> levelInfos_ =
      java.util.Collections.emptyList();
    private void ensureLevelInfosIsMutable() {
      if (!((bitField0_ & 0x00000001) != 0)) {
        levelInfos_ = new java.util.ArrayList<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo>(levelInfos_);
        bitField0_ |= 0x00000001;
       }
    }

    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcLevelInfo, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder, io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder> levelInfosBuilder_;

    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo> getLevelInfosList() {
      if (levelInfosBuilder_ == null) {
        return java.util.Collections.unmodifiableList(levelInfos_);
      } else {
        return levelInfosBuilder_.getMessageList();
      }
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public int getLevelInfosCount() {
      if (levelInfosBuilder_ == null) {
        return levelInfos_.size();
      } else {
        return levelInfosBuilder_.getCount();
      }
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfo getLevelInfos(int index) {
      if (levelInfosBuilder_ == null) {
        return levelInfos_.get(index);
      } else {
        return levelInfosBuilder_.getMessage(index);
      }
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder setLevelInfos(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo value) {
      if (levelInfosBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureLevelInfosIsMutable();
        levelInfos_.set(index, value);
        onChanged();
      } else {
        levelInfosBuilder_.setMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder setLevelInfos(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder builderForValue) {
      if (levelInfosBuilder_ == null) {
        ensureLevelInfosIsMutable();
        levelInfos_.set(index, builderForValue.build());
        onChanged();
      } else {
        levelInfosBuilder_.setMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder addLevelInfos(io.evitadb.externalApi.grpc.generated.GrpcLevelInfo value) {
      if (levelInfosBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureLevelInfosIsMutable();
        levelInfos_.add(value);
        onChanged();
      } else {
        levelInfosBuilder_.addMessage(value);
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder addLevelInfos(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo value) {
      if (levelInfosBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureLevelInfosIsMutable();
        levelInfos_.add(index, value);
        onChanged();
      } else {
        levelInfosBuilder_.addMessage(index, value);
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder addLevelInfos(
        io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder builderForValue) {
      if (levelInfosBuilder_ == null) {
        ensureLevelInfosIsMutable();
        levelInfos_.add(builderForValue.build());
        onChanged();
      } else {
        levelInfosBuilder_.addMessage(builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder addLevelInfos(
        int index, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder builderForValue) {
      if (levelInfosBuilder_ == null) {
        ensureLevelInfosIsMutable();
        levelInfos_.add(index, builderForValue.build());
        onChanged();
      } else {
        levelInfosBuilder_.addMessage(index, builderForValue.build());
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder addAllLevelInfos(
        java.lang.Iterable<? extends io.evitadb.externalApi.grpc.generated.GrpcLevelInfo> values) {
      if (levelInfosBuilder_ == null) {
        ensureLevelInfosIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, levelInfos_);
        onChanged();
      } else {
        levelInfosBuilder_.addAllMessages(values);
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder clearLevelInfos() {
      if (levelInfosBuilder_ == null) {
        levelInfos_ = java.util.Collections.emptyList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
      } else {
        levelInfosBuilder_.clear();
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public Builder removeLevelInfos(int index) {
      if (levelInfosBuilder_ == null) {
        ensureLevelInfosIsMutable();
        levelInfos_.remove(index);
        onChanged();
      } else {
        levelInfosBuilder_.remove(index);
      }
      return this;
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder getLevelInfosBuilder(
        int index) {
      return getLevelInfosFieldBuilder().getBuilder(index);
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder getLevelInfosOrBuilder(
        int index) {
      if (levelInfosBuilder_ == null) {
        return levelInfos_.get(index);  } else {
        return levelInfosBuilder_.getMessageOrBuilder(index);
      }
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder> 
         getLevelInfosOrBuilderList() {
      if (levelInfosBuilder_ != null) {
        return levelInfosBuilder_.getMessageOrBuilderList();
      } else {
        return java.util.Collections.unmodifiableList(levelInfos_);
      }
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder addLevelInfosBuilder() {
      return getLevelInfosFieldBuilder().addBuilder(
          io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.getDefaultInstance());
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder addLevelInfosBuilder(
        int index) {
      return getLevelInfosFieldBuilder().addBuilder(
          index, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.getDefaultInstance());
    }
    /**
     * <pre>
     * Array of statistics for the single hierarchy level of inner entities.
     * </pre>
     *
     * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcLevelInfo levelInfos = 1;</code>
     */
    public java.util.List<io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder> 
         getLevelInfosBuilderList() {
      return getLevelInfosFieldBuilder().getBuilderList();
    }
    private com.google.protobuf.RepeatedFieldBuilderV3<
        io.evitadb.externalApi.grpc.generated.GrpcLevelInfo, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder, io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder> 
        getLevelInfosFieldBuilder() {
      if (levelInfosBuilder_ == null) {
        levelInfosBuilder_ = new com.google.protobuf.RepeatedFieldBuilderV3<
            io.evitadb.externalApi.grpc.generated.GrpcLevelInfo, io.evitadb.externalApi.grpc.generated.GrpcLevelInfo.Builder, io.evitadb.externalApi.grpc.generated.GrpcLevelInfoOrBuilder>(
                levelInfos_,
                ((bitField0_ & 0x00000001) != 0),
                getParentForChildren(),
                isClean());
        levelInfos_ = null;
      }
      return levelInfosBuilder_;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcLevelInfos)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcLevelInfos)
  private static final io.evitadb.externalApi.grpc.generated.GrpcLevelInfos DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcLevelInfos();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcLevelInfos>
      PARSER = new com.google.protobuf.AbstractParser<GrpcLevelInfos>() {
    @java.lang.Override
    public GrpcLevelInfos parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcLevelInfos(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcLevelInfos> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcLevelInfos> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcLevelInfos getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

