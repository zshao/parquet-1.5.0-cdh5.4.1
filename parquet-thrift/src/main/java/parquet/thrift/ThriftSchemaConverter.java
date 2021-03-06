/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package parquet.thrift;

import com.twitter.elephantbird.thrift.TStructDescriptor;
import com.twitter.elephantbird.thrift.TStructDescriptor.Field;
import java.util.HashSet;
import java.util.Set;
import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import parquet.schema.MessageType;
import parquet.schema.Type;
import parquet.thrift.projection.FieldProjectionFilter;
import parquet.thrift.struct.ThriftField;
import parquet.thrift.struct.ThriftField.Requirement;
import parquet.thrift.struct.ThriftType;
import parquet.thrift.struct.ThriftType.*;
import parquet.thrift.struct.ThriftTypeID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Given a thrift class, this class converts it to parquet schema,
 * a {@link FieldProjectionFilter} can be specified for projection pushdown.
 */
public class ThriftSchemaConverter {

  private final FieldProjectionFilter fieldProjectionFilter;

  public ThriftSchemaConverter() {
    this(new FieldProjectionFilter());
  }

  public ThriftSchemaConverter(FieldProjectionFilter fieldProjectionFilter) {
    this.fieldProjectionFilter = fieldProjectionFilter;
  }

  public MessageType convert(Class<? extends TBase<?, ?>> thriftClass) {
    return convert(new ThriftStructConverter().toStructType(thriftClass));
  }

  public MessageType convert(StructType thriftClass) {
    ThriftSchemaConvertVisitor visitor = new ThriftSchemaConvertVisitor(fieldProjectionFilter);
    thriftClass.accept(visitor);
    MessageType convertedMessageType = visitor.getConvertedMessageType();
    return convertedMessageType;
  }

  public ThriftType.StructType toStructType(Class<? extends TBase<?, ?>> thriftClass) {
    return new ThriftStructConverter().toStructType(thriftClass);
  }

  /**
   * Returns whether the given type is the element type of a list or is a
   * synthetic group with one field that is the element type. This is
   * determined by checking whether the type can be a synthetic group and by
   * checking whether a potential synthetic group matches the expected
   * ThriftField.
   * <p>
   * This method never guesses because the expected ThriftField is known.
   *
   * @param repeatedType a type that may be the element type
   * @param thriftElement the expected Schema for list elements
   * @return {@code true} if the repeatedType is the element schema
   */
  static boolean isElementType(Type repeatedType, ThriftField thriftElement) {
    if (repeatedType.isPrimitive() ||
        (repeatedType.asGroupType().getFieldCount() != 1)) {
      // The repeated type must be the element type because it is an invalid
      // synthetic wrapper (must be a group with one field).
      return true;
    } else if (thriftElement != null && thriftElement.getType() instanceof StructType) {
      Set<String> fieldNames = new HashSet<String>();
      for (ThriftField field : ((StructType) thriftElement.getType()).getChildren()) {
        fieldNames.add(field.getName());
      }
      // If the repeated type is a subset of the structure of the ThriftField,
      // then it must be the element type.
      return fieldNames.contains(repeatedType.asGroupType().getFieldName(0));
    }
    return false;
  }

  private static class ThriftStructConverter {

    public ThriftType.StructType toStructType(Class<? extends TBase<?, ?>> thriftClass) {
      final TStructDescriptor struct = TStructDescriptor.getInstance(thriftClass);
      return toStructType(struct);
    }

    private StructType toStructType(TStructDescriptor struct) {
      List<Field> fields = struct.getFields();
      List<ThriftField> children = new ArrayList<ThriftField>(fields.size());
      for (int i = 0; i < fields.size(); i++) {
        Field field = fields.get(i);
        Requirement req =
                field.getFieldMetaData() == null ?
                        Requirement.OPTIONAL :
                        Requirement.fromType(field.getFieldMetaData().requirementType);
        children.add(toThriftField(field.getName(), field, req));
      }
      return new StructType(children);
    }

    private ThriftField toThriftField(String name, Field field, ThriftField.Requirement requirement) {
      ThriftType type;
      switch (ThriftTypeID.fromByte(field.getType())) {
      case STOP:
      case VOID:
      default:
        throw new UnsupportedOperationException("can't convert type of " + field);
      case BOOL:
        type = new BoolType();
        break;
      case BYTE:
        type = new ByteType();
        break;
      case DOUBLE:
        type = new DoubleType();
        break;
      case I16:
        type = new I16Type();
        break;
      case I32:
        type = new I32Type();
        break;
      case I64:
        type = new I64Type();
        break;
      case STRING:
        type = new StringType();
        break;
      case STRUCT:
        type = toStructType(field.gettStructDescriptor());
        break;
      case MAP:
        final Field mapKeyField = field.getMapKeyField();
        final Field mapValueField = field.getMapValueField();
        type = new ThriftType.MapType(
                toThriftField(mapKeyField.getName(), mapKeyField, requirement),
                toThriftField(mapValueField.getName(), mapValueField, requirement));
        break;
      case SET:
        final Field setElemField = field.getSetElemField();
        type = new ThriftType.SetType(toThriftField(name, setElemField, requirement));
        break;
      case LIST:
        final Field listElemField = field.getListElemField();
        type = new ThriftType.ListType(toThriftField(name, listElemField, requirement));
        break;
      case ENUM:
        Collection<TEnum> enumValues = field.getEnumValues();
        List<EnumValue> values = new ArrayList<ThriftType.EnumValue>();
        for (TEnum tEnum : enumValues) {
          values.add(new EnumValue(tEnum.getValue(), tEnum.toString()));
        }
        type = new EnumType(values);
        break;
      }
      return new ThriftField(name, field.getId(), requirement, type);
    }
  }
}

