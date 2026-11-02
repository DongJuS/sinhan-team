package com.sinhan.web;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  public Problem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
