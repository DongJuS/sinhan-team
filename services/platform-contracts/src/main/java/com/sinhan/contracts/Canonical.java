package com.sinhan.contracts;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Canonical {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private Canonical() {}

  public static String json(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid contract", e);
    }
  }

  public static <T> T read(String v, Class<T> type) {
    try {
      return MAPPER.readValue(v, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored contract", e);
    }
  }

  public static String hash(Object v) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(v)));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
