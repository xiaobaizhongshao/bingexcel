package com.bing.excel.core.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bing.excel.converter.FieldValueConverter;
import com.bing.excel.converter.HeaderReflectConverter;
import com.bing.excel.converter.ModelAdapter;
import com.bing.excel.core.handler.ConverterHandler;
import com.bing.excel.exception.ConversionException;
import com.bing.excel.exception.IllegalEntityException;
import com.bing.excel.exception.illegalValueException;
import com.bing.excel.mapper.ExcelConverterMapperHandler;
import com.bing.excel.vo.CellKV;
import com.bing.excel.mapper.ConversionMapper.FieldConverterMapper;
import com.bing.excel.vo.ListLine;
import com.bing.excel.vo.ListRow;
import com.bing.excel.vo.OutValue;
import com.bing.excel.vo.OutValue.OutType;
import com.google.common.primitives.Primitives;
import java.util.Map.Entry;

/**
 * @author shizhongtao
 */
public class TypeAdapterConverter<T> implements ModelAdapter, HeaderReflectConverter {

  private final Constructor<T> constructor;
  /**
   * 名称和
   */
  private final Map<String, BoundField> boundFields;
  private final Class<T> clazz;
  private final ConverterHandler defaultLocalConverterHandler;

  public Field getFieldByName(String fieldName) {
    BoundField boundField = boundFields.get(fieldName);
    if (boundField == null) {
      throw new NullPointerException("connot find the propertiy ：" + fieldName);

    }

    return boundField.field;
  }

  public TypeAdapterConverter(Constructor<T> constructor,
      Field[] fields, ConverterHandler converterHandler) {
    Map<String, BoundField> boundFields = new HashMap<>();
    for (int i = 0; i < fields.length; i++) {
      final Field field = fields[i];

      if (field.isEnumConstant()
          || (field.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) > 0) {
        continue;
      }

      // 应该不会出现
      if (field.isSynthetic()) {
        continue;
      }
      // Fixme 为了适配用户自定义mapper 注释一下
      /* ------  start  -----*/
      /*CellConfig cellConfig = field.getAnnotation(CellConfig.class);
      if (cellConfig == null) {
        continue;
      }*/
      /*-----------end-----------*/
      field.setAccessible(true);
      String name = field.getName();
      boundFields.put(name, new BoundField(field, name));
    }

    this.constructor = constructor;
    this.boundFields = boundFields;
    defaultLocalConverterHandler = converterHandler;
    clazz = constructor.getDeclaringClass();
  }

  @Override
  public List<CellKV<String>> getHeader(ExcelConverterMapperHandler... handlers) {
    List<CellKV<String>> list = new ArrayList<>();
    for (Map.Entry<String, BoundField> kv : boundFields.entrySet()) {
      FieldConverterMapper fieldConverterMapper = getFieldConverterMapper(kv.getKey(),
          handlers);
      if (fieldConverterMapper == null) {
        continue;
      }
      list.add(new CellKV<String>(fieldConverterMapper.getIndex(),
          fieldConverterMapper.getAlias()));
    }
    return list;
  }

  public ListLine getHeadertoListLine(ExcelConverterMapperHandler... handlers) {
    ListLine line = new ListLine();
    for (Map.Entry<String, BoundField> kv : boundFields.entrySet()) {
      FieldConverterMapper fieldConverterMapper = getFieldConverterMapper(kv.getKey(),
          handlers);
      if (fieldConverterMapper == null) {
        continue;
      }
      line.addValue(fieldConverterMapper.getIndex(),
          fieldConverterMapper.getAlias());
    }
    return line;
  }

  @Override
  public ListLine marshal(Object source, ExcelConverterMapperHandler... fieldHandler) {
    ListLine line = new ListLine();
    for (Map.Entry<String, BoundField> kv : boundFields.entrySet()) {
      FieldConverterMapper fieldConverterMapper = getFieldConverterMapper(kv.getKey(),
          fieldHandler);
      if (fieldConverterMapper == null) {
        continue;
      }

      BoundField boundField = kv.getValue();
      if (fieldConverterMapper.getFieldConverter() == null) {

        setLocalConverter(fieldConverterMapper);
      }

      boundField.serializeValue(source, fieldConverterMapper, line);
    }
    return line;
  }

  @Override
  public T unmarshal(ListRow source, ExcelConverterMapperHandler... fieldHandler) {
    final Object obj;
    try {
      obj = constructor.newInstance();
    } catch (InstantiationException | IllegalAccessException
        | IllegalArgumentException | InvocationTargetException e) {
      throw new IllegalEntityException(constructor.getName() + "构造实例失败",
          e);
    }
    String[] fullArray = source.toFullArray();
    int length = fullArray.length;

    if (length > 0) {
      for (Map.Entry<String, BoundField> kv : boundFields.entrySet()) {
        FieldConverterMapper converterMapper = getFieldConverterMapper(kv.getKey(), fieldHandler);
        if (converterMapper == null) {
          continue;
        }
        BoundField boundField = kv.getValue();
        if (converterMapper.getFieldConverter() == null) {

          setLocalConverter(converterMapper);
        }

        int index = converterMapper.getIndex();
        String fieldValue = length > index ? fullArray[index] : null;
        boundField.initializeValue(obj, fieldValue, converterMapper);
      }
    }
    return (T) obj;
  }

  private FieldConverterMapper getFieldConverterMapper(String fieldName,
      ExcelConverterMapperHandler[] fieldHandler) {
    FieldConverterMapper converterMapper = null;
    for (int i = 0; i < fieldHandler.length; i++) {
      ExcelConverterMapperHandler excelConverterMapperHandler = fieldHandler[i];
      if (excelConverterMapperHandler != null) {
        converterMapper = excelConverterMapperHandler
            .getLocalFieldConverterMapper(clazz, fieldName);
        if (converterMapper != null) {
          break;
        }
      }

    }
    return converterMapper;
  }

  private void setLocalConverter(FieldConverterMapper converterMapper) {
    // it is not good for wrap clazz in this place
    Class<?> keyFieldType = converterMapper.isPrimitive() ? Primitives
        .wrap(converterMapper.getFieldClass()) : converterMapper
        .getFieldClass();
    FieldValueConverter fieldValueConverter = defaultLocalConverterHandler
        .getLocalConverter(keyFieldType);

    if (fieldValueConverter == null) {
      throw new IllegalEntityException(clazz,
          "can find the converter for fieldType ["
              + converterMapper.getFieldClass() + "]");
    }
    converterMapper.setFieldConverter(fieldValueConverter);

  }

  private class BoundField {

    private final String name;
    private final Field field;

    public BoundField(Field field, String name) {
      this.field = field;
      this.name = name;
    }

    /**
     * Get listline from object
     */
    protected ListLine serializeValue(Object entity,
        FieldConverterMapper converterMapper, ListLine line) {
      if (entity == null) {
        return line;
      } else {
        if (converterMapper == null) {
          throw new NullPointerException("the converterMapper for ["
              + name + "] is null");
        } else {
          FieldValueConverter converter = converterMapper
              .getFieldConverter();
          if (converter == null) {
            throw new NullPointerException("the converter for ["
                + name + "] is null");
          }
          boolean canConvert = converter.canConvert(converterMapper
              .getFieldClass());
          if (!canConvert) {
            throw new ConversionException(
                "the selected converter ["
                    + converter.getClass()
                    + "] cannot handle type ["
                    + converterMapper.getFieldClass() + "]");
          }
          Object obj;
          try {
            obj = field.get(entity);
          } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                "It happened an error when get the value of the Entity !",
                e);
          }
          OutValue outValue = converter.toObject(obj,
              defaultLocalConverterHandler);
          if (outValue != null) {
            if (outValue.getOutType().equals(OutType.DATE)) {
              line.addValue(converterMapper.getIndex(),
                  (Date) outValue.getValue());
            } else if (outValue.getOutType().equals(OutType.DOUBLE)) {
              line.addValue(converterMapper.getIndex(),
                  (double) outValue.getValue());
            } else if (outValue.getOutType()
                .equals(OutType.INTEGER)) {
              line.addValue(converterMapper.getIndex(),
                  (int) outValue.getValue());
            } else if (outValue.getOutType().equals(OutType.LONG)) {
              line.addValue(converterMapper.getIndex(),
                  (long) outValue.getValue());
            } else if (outValue.getOutType().equals(OutType.STRING)) {
              line.addValue(converterMapper.getIndex(), outValue
                  .getValue().toString());
            } else if (outValue.getOutType().equals(
                OutType.UNDEFINED)) {
              line.addValue(converterMapper.getIndex(), outValue
                  .getValue().toString());
            }
          }
        }
        return line;
      }
    }

    protected Object initializeValue(Object obj, String value,
        FieldConverterMapper converterMapper) {
      // field.set(obj, value);
      if (value != null) {
        if (converterMapper != null) {
          FieldValueConverter converter = converterMapper
              .getFieldConverter();
          if (converter == null) {
            throw new NullPointerException("the converter for ["
                + name + "] is null");
          }
          boolean canConvert = converter.canConvert(converterMapper
              .getFieldClass());
          if (!canConvert) {
            throw new ConversionException(
                "the selected converter ["
                    + converter.getClass()
                    + "] cannot handle type ["
                    + converterMapper.getFieldClass() + "]");
          }

          Object fieldValue = converter.fromString(value,
              defaultLocalConverterHandler,
              converterMapper.getFieldClass());
          if (fieldValue != null) {
            try {
              field.set(obj, fieldValue);
            } catch (IllegalArgumentException
                | IllegalAccessException e) {
              throw new IllegalArgumentException(
                  "It happened an error when set the value of the Entity !",
                  e);
            }
          }

        } else {
          throw new NullPointerException("the converterMapper for ["
              + name + "] is null");
        }
      } else {
        if (converterMapper.isReadRequired()) {
          throw new illegalValueException(
              "  field in [" + converterMapper.getContainer()
                  + "] indexed " + converterMapper.getIndex() + " is required");

        }
      }
      return obj;
    }

  }
}
