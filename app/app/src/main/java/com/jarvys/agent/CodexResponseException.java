package com.jarvys.agent;

/** A response did not safely finish. Never includes provider payloads or partial tool arguments. */
public final class CodexResponseException extends IllegalStateException {
  public enum Kind { FAILED, INCOMPLETE, ERROR, MALFORMED }
  public final Kind kind;

  public CodexResponseException(Kind kind) {
    super(message(kind));
    this.kind = kind;
  }

  private static String message(Kind kind) {
    switch (kind) {
      case FAILED: return "El proveedor indicó que la respuesta falló; no se ejecutaron llamadas parciales.";
      case INCOMPLETE: return "La respuesta del proveedor quedó incompleta; no se ejecutaron llamadas parciales.";
      case ERROR: return "El proveedor devolvió un error; no se ejecutaron llamadas parciales.";
      default: return "La respuesta del proveedor no tiene un cierre válido; no se ejecutaron llamadas parciales.";
    }
  }
}
