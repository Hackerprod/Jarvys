package com.jarvys.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Multilingual post-turn guard configured by the versioned memory claim section in agent_docs. */
final class MemoryClaimValidator {
  private static final Pattern CONFIG_BLOCK = Pattern.compile(
      "(?s)<memory_claim_patterns(?:\\s+[^>]*)?>(.*?)</memory_claim_patterns>");
  private static final Pattern CORRECTION_BLOCK = Pattern.compile(
      "(?s)<memory_claim_correction(?:\\s+[^>]*)?>(.*?)</memory_claim_correction>");

  private MemoryClaimValidator() {}

  static boolean claimsPersistence(String answer, String instructions) {
    if (answer == null || answer.isEmpty() || instructions == null) return false;
    Matcher block = CONFIG_BLOCK.matcher(instructions);
    if (!block.find()) return false;
    for (String line : block.group(1).split("\\R")) {
      int separator = line.indexOf('=');
      if (separator <= 0 || "version".equals(line.substring(0, separator).trim())) continue;
      String expression = line.substring(separator + 1).trim();
      if (expression.isEmpty()) continue;
      try {
        if (Pattern.compile(expression).matcher(answer).find()) return true;
      } catch (RuntimeException ignored) {
        // A malformed configuration entry is skipped; valid language entries still apply.
      }
    }
    return false;
  }

  static String correctionInstruction(String instructions) {
    if (instructions != null) {
      Matcher block = CORRECTION_BLOCK.matcher(instructions);
      if (block.find() && !block.group(1).trim().isEmpty()) return block.group(1).trim();
    }
    return "The draft claimed that user information was saved or will be remembered without a successful memory-write result containing durable id and revision evidence. Either call an available memory write tool now and verify its success result, or state that it was not saved and name the blocker. Do not imply persistence without evidence.";
  }
}
