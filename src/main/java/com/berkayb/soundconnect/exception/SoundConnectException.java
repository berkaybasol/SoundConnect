package com.berkayb.soundconnect.exception;

import com.berkayb.soundconnect.exception.ErrorType;
import lombok.Getter;

import java.util.List;

@Getter
public class SoundConnectException extends RuntimeException {
  private final ErrorType errorType;
  private final List<String> details;
  
  public SoundConnectException(ErrorType errorType) {
    super(errorType.getMessage());
    this.errorType = errorType;
    this.details = null;
  }
  
  public SoundConnectException(ErrorType errorType, List<String> details) {
    super(errorType.getMessage());
    this.errorType = errorType;
    this.details = details;
  }
}