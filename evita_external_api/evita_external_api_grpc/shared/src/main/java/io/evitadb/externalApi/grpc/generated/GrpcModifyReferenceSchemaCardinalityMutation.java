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
// source: GrpcReferenceSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

/**
 * <pre>
 * Mutation is responsible for setting value to a `ReferenceSchema.cardinality` in `EntitySchema`.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation}
 */
public final class GrpcModifyReferenceSchemaCardinalityMutation extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation)
    GrpcModifyReferenceSchemaCardinalityMutationOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcModifyReferenceSchemaCardinalityMutation.newBuilder() to construct.
  private GrpcModifyReferenceSchemaCardinalityMutation(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcModifyReferenceSchemaCardinalityMutation() {
    name_ = "";
    cardinality_ = 0;
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcModifyReferenceSchemaCardinalityMutation();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcModifyReferenceSchemaCardinalityMutation(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
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

            cardinality_ = rawValue;
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
    return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaCardinalityMutation_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaCardinalityMutation_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.class, io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.Builder.class);
  }

  public static final int NAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object name_;
  /**
   * <pre>
   * Name of the reference the mutation is targeting.
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
   * Name of the reference the mutation is targeting.
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

  public static final int CARDINALITY_FIELD_NUMBER = 2;
  private int cardinality_;
  /**
   * <pre>
   * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
   * relationship from the perspective of the entity. We stick to the ERD modelling
   * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
   * of the client API (returning only single reference or collections) and also help us to protect the consistency
   * of the data so that conforms to the creator mental model.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
   * @return The enum numeric value on the wire for cardinality.
   */
  @java.lang.Override public int getCardinalityValue() {
    return cardinality_;
  }
  /**
   * <pre>
   * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
   * relationship from the perspective of the entity. We stick to the ERD modelling
   * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
   * of the client API (returning only single reference or collections) and also help us to protect the consistency
   * of the data so that conforms to the creator mental model.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
   * @return The cardinality.
   */
  @java.lang.Override public io.evitadb.externalApi.grpc.generated.GrpcCardinality getCardinality() {
    @SuppressWarnings("deprecation")
    io.evitadb.externalApi.grpc.generated.GrpcCardinality result = io.evitadb.externalApi.grpc.generated.GrpcCardinality.valueOf(cardinality_);
    return result == null ? io.evitadb.externalApi.grpc.generated.GrpcCardinality.UNRECOGNIZED : result;
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
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(name_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, name_);
    }
    if (cardinality_ != io.evitadb.externalApi.grpc.generated.GrpcCardinality.NOT_SPECIFIED.getNumber()) {
      output.writeEnum(2, cardinality_);
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
    if (cardinality_ != io.evitadb.externalApi.grpc.generated.GrpcCardinality.NOT_SPECIFIED.getNumber()) {
      size += com.google.protobuf.CodedOutputStream
        .computeEnumSize(2, cardinality_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation other = (io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation) obj;

    if (!getName()
        .equals(other.getName())) return false;
    if (cardinality_ != other.cardinality_) return false;
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
    hash = (37 * hash) + CARDINALITY_FIELD_NUMBER;
    hash = (53 * hash) + cardinality_;
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation prototype) {
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
   * Mutation is responsible for setting value to a `ReferenceSchema.cardinality` in `EntitySchema`.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation)
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutationOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaCardinalityMutation_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaCardinalityMutation_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.class, io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.newBuilder()
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

      cardinality_ = 0;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcReferenceSchemaMutations.internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifyReferenceSchemaCardinalityMutation_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation build() {
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation result = new io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation(this);
      result.name_ = name_;
      result.cardinality_ = cardinality_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation.getDefaultInstance()) return this;
      if (!other.getName().isEmpty()) {
        name_ = other.name_;
        onChanged();
      }
      if (other.cardinality_ != 0) {
        setCardinalityValue(other.getCardinalityValue());
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
      io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object name_ = "";
    /**
     * <pre>
     * Name of the reference the mutation is targeting.
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
     * Name of the reference the mutation is targeting.
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
     * Name of the reference the mutation is targeting.
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
     * Name of the reference the mutation is targeting.
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
     * Name of the reference the mutation is targeting.
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

    private int cardinality_ = 0;
    /**
     * <pre>
     * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
     * relationship from the perspective of the entity. We stick to the ERD modelling
     * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
     * of the client API (returning only single reference or collections) and also help us to protect the consistency
     * of the data so that conforms to the creator mental model.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
     * @return The enum numeric value on the wire for cardinality.
     */
    @java.lang.Override public int getCardinalityValue() {
      return cardinality_;
    }
    /**
     * <pre>
     * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
     * relationship from the perspective of the entity. We stick to the ERD modelling
     * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
     * of the client API (returning only single reference or collections) and also help us to protect the consistency
     * of the data so that conforms to the creator mental model.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
     * @param value The enum numeric value on the wire for cardinality to set.
     * @return This builder for chaining.
     */
    public Builder setCardinalityValue(int value) {
      
      cardinality_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
     * relationship from the perspective of the entity. We stick to the ERD modelling
     * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
     * of the client API (returning only single reference or collections) and also help us to protect the consistency
     * of the data so that conforms to the creator mental model.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
     * @return The cardinality.
     */
    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcCardinality getCardinality() {
      @SuppressWarnings("deprecation")
      io.evitadb.externalApi.grpc.generated.GrpcCardinality result = io.evitadb.externalApi.grpc.generated.GrpcCardinality.valueOf(cardinality_);
      return result == null ? io.evitadb.externalApi.grpc.generated.GrpcCardinality.UNRECOGNIZED : result;
    }
    /**
     * <pre>
     * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
     * relationship from the perspective of the entity. We stick to the ERD modelling
     * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
     * of the client API (returning only single reference or collections) and also help us to protect the consistency
     * of the data so that conforms to the creator mental model.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
     * @param value The cardinality to set.
     * @return This builder for chaining.
     */
    public Builder setCardinality(io.evitadb.externalApi.grpc.generated.GrpcCardinality value) {
      if (value == null) {
        throw new NullPointerException();
      }
      
      cardinality_ = value.getNumber();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
     * relationship from the perspective of the entity. We stick to the ERD modelling
     * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
     * of the client API (returning only single reference or collections) and also help us to protect the consistency
     * of the data so that conforms to the creator mental model.
     * </pre>
     *
     * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearCardinality() {
      
      cardinality_ = 0;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation)
  private static final io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcModifyReferenceSchemaCardinalityMutation>
      PARSER = new com.google.protobuf.AbstractParser<GrpcModifyReferenceSchemaCardinalityMutation>() {
    @java.lang.Override
    public GrpcModifyReferenceSchemaCardinalityMutation parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcModifyReferenceSchemaCardinalityMutation(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcModifyReferenceSchemaCardinalityMutation> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcModifyReferenceSchemaCardinalityMutation> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSchemaCardinalityMutation getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

