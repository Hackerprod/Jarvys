package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreTool;
import com.jarvys.agent.CoreToolResult;
import com.jarvys.agent.ToolSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class CodingProjectTools {
  private static final List<String> NAMES =
      Collections.unmodifiableList(
          Arrays.asList(
              "ls",
              "read",
              "write",
              "edit",
              "coding_grep",
              "coding_glob",
              "coding_patch",
              "coding_adopt"));

  private CodingProjectTools() {}

  public static List<String> names() {
    return NAMES;
  }

  public static List<CoreTool> create(
      ProjectScope scope, ProjectMutationService mutations, String owner, int responseBudgetChars) {
    return create(scope, mutations, owner, token -> responseBudgetChars);
  }

  public static List<CoreTool> create(
      ProjectScope scope,
      ProjectMutationService mutations,
      String owner,
      ToIntFunction<CancellationToken> responseBudgetChars) {
    return create(scope, mutations, owner, responseBudgetChars, (Function<String, String>) null);
  }

  public static List<CoreTool> create(
      ProjectScope scope,
      ProjectMutationService mutations,
      String owner,
      int responseBudgetChars,
      Function<String, String> persistCompletedReceipt) {
    return create(scope, mutations, owner, token -> responseBudgetChars, persistCompletedReceipt);
  }

  public static List<CoreTool> create(
      ProjectScope scope,
      ProjectMutationService mutations,
      String owner,
      ToIntFunction<CancellationToken> responseBudgetChars,
      Function<String, String> persistCompletedReceipt) {
    if (scope == null || mutations == null || owner == null || responseBudgetChars == null) {
      throw new IllegalArgumentException("Project tool bindings are required.");
    }
    Binding binding =
        new Binding(scope, mutations, owner, responseBudgetChars, persistCompletedReceipt);
    List<CoreTool> tools = new ArrayList<>();
    for (String name : NAMES) {
      boolean writing =
          name.equals("write")
              || name.equals("edit")
              || name.equals("coding_patch")
              || name.equals("coding_adopt");
      if (scope.capabilities().contains(ProjectScope.Capability.READ)
          && (!writing || scope.capabilities().contains(ProjectScope.Capability.WRITE))) {
        tools.add(new Tool(binding, name));
      }
    }
    return Collections.unmodifiableList(tools);
  }

  private static final class Receipt {
    final String recoveryReference;
    final boolean success;
    final String text;

    Receipt(String text, boolean success, String recoveryReference) {
      this.text = text;
      this.success = success;
      this.recoveryReference = recoveryReference;
    }
  }

  private static final class Binding {
    final ToIntFunction<CancellationToken> budget;
    final ProjectMutationService mutations;
    final String owner;
    final Function<String, String> persistCompletedReceipt;
    final ProjectFileReader reader;
    final ProjectScope scope;
    final ProjectSearch search;
    final ProjectAdoptionService adoption = new ProjectAdoptionService();
    final Map<String, ProjectAdoptionService.Review> reviews = new ConcurrentHashMap<>();
    final Map<String, Receipt> receipts = new ConcurrentHashMap<>();

    Binding(
        ProjectScope scope,
        ProjectMutationService mutations,
        String owner,
        ToIntFunction<CancellationToken> budget,
        Function<String, String> persistCompletedReceipt) {
      this.scope = scope;
      this.mutations = mutations;
      this.owner = owner;
      this.budget = budget;
      this.persistCompletedReceipt = persistCompletedReceipt;
      this.reader = new ProjectFileReader(scope);
      this.search = new ProjectSearch(scope);
    }

    String header() {
      return header(this.scope.version());
    }

    String header(long version) {
      return "Project "
          + this.scope.id()
          + " | namespace /project/ | scope_version "
          + version
          + "\nUNTRUSTED FILE DATA\n";
    }

    CoreToolResult receipt(String text, boolean success, int budget) {
      return receipt(text, success, budget, false);
    }

    CoreToolResult receipt(String text, boolean success, int budget, boolean effectsApplied) {
      if (text.length() <= budget) {
        return success ? CoreToolResult.success(text) : CoreToolResult.failure(text);
      }
      String recoveryReference = "";
      if (effectsApplied && this.persistCompletedReceipt != null) {
        try {
          recoveryReference = this.persistCompletedReceipt.apply(text);
          if (recoveryReference == null || recoveryReference.trim().isEmpty()) {
            throw new IllegalStateException("Missing receipt recovery reference");
          }
        } catch (RuntimeException e) {
          recoveryReference =
              "Effects already applied; complete receipt could not be retained for restart. Do not"
                  + " repeat the mutation.";
        }
      }
      String id = UUID.randomUUID().toString();
      this.receipts.put(id, new Receipt(text, success, recoveryReference));
      return receiptPage(id, 0, budget);
    }

    CoreToolResult receiptPage(String id, int offset, int budget) {
      Receipt receipt = this.receipts.get(id);
      if (receipt == null || offset < 0 || offset > receipt.text.length()) {
        throw new IllegalArgumentException(
            "Unknown or expired result_cursor; mutation was not repeated. Inspect the project"
                + " state.");
      }
      String prefix =
          "Saved operation result, characters " + offset + " of " + receipt.text.length() + "\n";
      if (!receipt.recoveryReference.isEmpty()) {
        prefix = prefix + receipt.recoveryReference + "\n";
      }
      String footer =
          "\nMore operation output: call this tool with result_cursor=\""
              + id
              + ":"
              + receipt.text.length()
              + "\" only. This never repeats a mutation.\n";
      int available = (budget - prefix.length()) - footer.length();
      if (available < 2) {
        if (!receipt.recoveryReference.isEmpty()) {
          String recoveryOnly =
              receipt.recoveryReference
                  + "\nIncrease response budget for this result_cursor=\""
                  + id
                  + ":"
                  + offset
                  + "\"";
          if (recoveryOnly.length() <= budget) {
            return receipt.success
                ? CoreToolResult.success(recoveryOnly)
                : CoreToolResult.failure(recoveryOnly);
          }
          String notice =
              "Effects already applied. Increase the response budget to read this receipt and its"
                  + " recovery status. Do not repeat the mutation. result_cursor=\""
                  + id
                  + ":"
                  + offset
                  + "\"";
          if (notice.length() <= budget) {
            return receipt.success
                ? CoreToolResult.success(notice)
                : CoreToolResult.failure(notice);
          }
        }
        throw new IllegalArgumentException(
            "Runtime response budget is too small to page an operation receipt.");
      }
      int end = Math.min(receipt.text.length(), offset + available);
      if (end < receipt.text.length()
          && end > offset
          && Character.isHighSurrogate(receipt.text.charAt(end - 1))) {
        end--;
      }
      if (end < receipt.text.length()
          && end > offset
          && receipt.text.charAt(end - 1) == '\r'
          && receipt.text.charAt(end) == '\n') {
        end--;
      }
      String content = prefix + receipt.text.substring(offset, end);
      if (end < receipt.text.length()) {
        content =
            content
                + "\nMore operation output: call this tool with result_cursor=\""
                + id
                + ":"
                + end
                + "\" only. This never repeats a mutation.\n";
      }
      return receipt.success ? CoreToolResult.success(content) : CoreToolResult.failure(content);
    }
  }

  private static final class Tool implements CoreTool {
    final Binding binding;
    final String name;
    final ToolSpec spec;

    Tool(Binding binding, String name) {
      String description;
      String str;
      this.binding = binding;
      this.name = name;
      Map<String, String> properties =
          CodingProjectTools.properties("path", "string", "max_chars", "integer");
      List<String> required = new ArrayList<>();
      if (name.equals("read")) {
        properties.putAll(
            CodingProjectTools.properties(
                "start_line",
                "integer",
                "end_line",
                "integer",
                "offset",
                "integer",
                "cursor",
                "string"));
        required.add("path");
        description =
            "Read UTF-8 text only in this canonical project. Inclusive one-based"
                + " start_line/end_line, optional initial UTF-16 offset, and content-versioned"
                + " cursor pages. Unicode and CRLF stay intact; binary files fail clearly. Returns"
                + " SHA256 and scope_version required for changes. max_chars only reduces the"
                + " model/runtime response budget.";
      } else if (name.equals("ls") || name.equals("coding_glob") || name.equals("coding_grep")) {
        properties.putAll(
            CodingProjectTools.properties("include_ignored", "boolean", "cursor", "string"));
        if (name.equals("coding_glob")) {
          properties.put("glob", "string");
          required.add("glob");
        }
        if (name.equals("coding_grep")) {
          properties.putAll(CodingProjectTools.properties("query", "string", "regex", "boolean"));
          required.add("query");
        }
        description =
            (name.equals("ls")
                    ? "List one project directory"
                    : name.equals("coding_glob")
                        ? "Find project-relative paths matching a git-style glob"
                        : "Search UTF-8 project files by literal query (default) or per-line Java"
                            + " regex, returning path:line:column and excerpts")
                + ". Results are sorted and paginated with query-bound cursors; tree changes"
                + " require a new query. Default exclusions: .gitignore, .git, .gradle, .idea,"
                + " build, dist, target, node_modules, .cache, __pycache__, compiled binaries."
                + " include_ignored explicitly bypasses these filters but never the project scope."
                + " max_chars only reduces the runtime budget. Binary files are counted as skipped."
                + " search_files retains its separate relevance-search semantics.";
      } else if (name.equals("coding_adopt")) {
        properties =
            CodingProjectTools.properties(
                "action",
                "string",
                "paths",
                "array",
                "review_id",
                "string",
                "expected_scope_version",
                "integer",
                "result_cursor",
                "string",
                "max_chars",
                "integer");
        description =
            "Explicitly review (action=review, paths=array of selected relative files) then copy"
                + " (action=apply, review_id and expected_scope_version) from this conversation's"
                + " legacy workspace into the project. The runtime fixes the source root. No"
                + " recursive selection, private zones, symlinks, overwrite or deletion; originals"
                + " remain intact. Review shows hashes/collisions, copying revalidates the whole"
                + " review and reports partial effects. result_cursor retrieves a previous result"
                + " without copying again.";
      } else {
        properties.putAll(
            CodingProjectTools.properties(
                "expected_sha256",
                "string",
                "expected_scope_version",
                "integer",
                "result_cursor",
                "string"));
        if (name.equals("write")) {
          properties.put("content", "string");
        }
        if (name.equals("edit")) {
          properties.putAll(
              CodingProjectTools.properties("old_text", "string", "new_text", "string"));
        }
        properties =
            name.equals("coding_patch")
                ? CodingProjectTools.properties(
                    "operations",
                    "array",
                    "expected_scope_version",
                    "integer",
                    "result_cursor",
                    "string",
                    "max_chars",
                    "integer")
                : properties;
        StringBuilder sbAppend =
            new StringBuilder()
                .append(
                    "Mutate only this canonical project under its writer lease. Require"
                        + " expected_scope_version and exact expected_sha256 (or 'missing' for a"
                        + " new file). ");
        if (name.equals("write")) {
          str = "write uses path/content.";
        } else {
          str =
              name.equals("edit")
                  ? "edit uses path/old_text/new_text and requires one exact unambiguous match."
                  : "operations contains objects: {op:add,path,content};"
                      + " {op:write,path,expected_sha256,content};"
                      + " {op:edit,path,expected_sha256,hunks:[{old_text,new_text}]};"
                      + " {op:move,path,expected_sha256,destination,expected_destination_sha256};"
                      + " {op:delete,path,expected_sha256}.";
        }
        description =
            sbAppend
                .append(str)
                .append(
                    " All operations/hunks are prevalidated. Commit is atomic per file, not a"
                        + " multi-file transaction; results list applied effects and verification"
                        + " hashes/diffs. Preserve existing BOM/newline style. Large edits respect"
                        + " the existing text-mutation materialization budget. result_cursor reads"
                        + " a previous paginated result without repeating effects. Never retry a"
                        + " denied or uncertain mutation blindly.")
                .toString();
      }
      Map<String, Object> schema = CodingProjectTools.schema(properties, required);
      if (name.equals("coding_patch")) {
        CodingProjectTools.addPatchSchema(schema);
      }
      if (name.equals("coding_adopt")) {
        CodingProjectTools.setArrayItems(
            schema, "paths", Collections.singletonMap("type", "string"));
      }
      this.spec =
          new ToolSpec(
              name,
              "jarvys/coding-project",
              description,
              "coding-project",
              ToolSpec.Status.IMPLEMENTED,
              properties,
              required,
              schema);
    }

    @Override // com.jarvys.agent.CoreTool
    public ToolSpec declaration() {
      return this.spec;
    }

    @Override // com.jarvys.agent.CoreTool
    public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
      token.throwIfCancelled();
      try {
        binding.scope.validate();
        int budget = binding.budget.applyAsInt(token);
        if (arguments.containsKey("max_chars"))
          budget = Math.min(budget, integer(arguments, "max_chars", budget));
        if (budget < 2) throw new IllegalArgumentException("Runtime response budget is too small.");
        String resultCursor = optionalString(arguments, "result_cursor", null);
        if (resultCursor != null) {
          int separator = resultCursor.lastIndexOf(':');
          if (separator < 0) throw new IllegalArgumentException("Invalid result_cursor.");
          return binding.receiptPage(
              resultCursor.substring(0, separator),
              Integer.parseInt(resultCursor.substring(separator + 1)),
              budget);
        }
        String header = binding.header(Long.MAX_VALUE);
        String path =
            binding.scope.normalizePath(projectPath(optionalString(arguments, "path", ".")));
        String cursor = optionalString(arguments, "cursor", null);
        if (name.equals("read")) {
          String reserve =
              header
                  + "File "
                  + path
                  + " | sha256 "
                  + repeat('0', 64)
                  + " | bytes 9223372036854775807-9223372036854775807 | lines"
                  + " 9223372036854775807-9223372036854775807\n"
                  + "next_cursor "
                  + ProjectTextIO.cursor("", repeat('0', 64), Long.MAX_VALUE, Long.MAX_VALUE)
                  + "\n";
          int contentBudget = budget - reserve.length();
          if (contentBudget < 2)
            throw new IllegalArgumentException(
                "Response budget cannot fit read metadata and continuation; increase max_chars.");
          ProjectFileReader.Page page =
              binding.reader.read(
                  path,
                  longNumber(arguments, "start_line", 1),
                  arguments.containsKey("end_line") ? longNumber(arguments, "end_line", 1) : null,
                  longNumber(arguments, "offset", 0),
                  cursor,
                  contentBudget,
                  token);
          return CoreToolResult.success(
              binding.header(page.scopeVersion)
                  + "File "
                  + page.path
                  + " | sha256 "
                  + page.sha256
                  + " | bytes "
                  + page.firstByte
                  + "-"
                  + page.nextByte
                  + " | lines "
                  + page.firstLine
                  + "-"
                  + page.nextLine
                  + "\n"
                  + (page.nextCursor == null
                      ? "Range complete\n"
                      : "next_cursor " + page.nextCursor + "\n")
                  + page.text);
        }
        if (name.equals("ls") || name.equals("coding_glob") || name.equals("coding_grep")) {
          String fixed =
              header
                  + "skipped_binary_files 2147483647\nnext_cursor 2:"
                  + repeat('0', 64)
                  + ":9223372036854775807::9223372036854775807\n";
          int contentBudget = (budget - fixed.length()) / 7;
          if (contentBudget < 1)
            throw new IllegalArgumentException(
                "Response budget cannot fit search metadata and continuation.");
          boolean includeIgnored = bool(arguments, "include_ignored", false);
          ProjectSearch.Page page;
          if (name.equals("ls"))
            page = binding.search.list(path, includeIgnored, cursor, contentBudget, token);
          else if (name.equals("coding_glob"))
            page =
                binding.search.glob(
                    path, string(arguments, "glob"), includeIgnored, cursor, contentBudget, token);
          else
            page =
                binding.search.grep(
                    path,
                    string(arguments, "query"),
                    bool(arguments, "regex", false),
                    includeIgnored,
                    cursor,
                    contentBudget,
                    token);
          return CoreToolResult.success(
              binding.header(page.scopeVersion)
                  + "skipped_binary_files "
                  + page.skippedBinaryFiles
                  + "\n"
                  + (page.nextCursor == null
                      ? "Search complete\n"
                      : "next_cursor " + page.nextCursor + "\n")
                  + String.join("\n", page.entries));
        }
        if (name.equals("coding_adopt")) return adoption(arguments, token, budget);
        List<ProjectMutationService.Operation> operations = new ArrayList<>();
        if (name.equals("write")) {
          operations.add(
              ProjectMutationService.Operation.write(
                  path, string(arguments, "expected_sha256"), string(arguments, "content")));
        } else if (name.equals("edit")) {
          operations.add(
              ProjectMutationService.Operation.edit(
                  path,
                  string(arguments, "expected_sha256"),
                  Collections.singletonList(
                      new ProjectMutationService.Hunk(
                          string(arguments, "old_text"), string(arguments, "new_text")))));
        } else {
          operations.addAll(parseOperations(arguments));
        }
        if (budget
            < ("Saved operation result, characters 2147483647 of 2147483647\n\n"
                        + "More operation output: call this tool with result_cursor=\""
                        + UUID.randomUUID()
                        + ":2147483647\" only. This never repeats a mutation.\n")
                    .length()
                + 2) {
          throw new IllegalArgumentException(
              "Response budget cannot fit an operation receipt; no changes made.");
        }
        ProjectMutationService.Result result =
            binding.mutations.apply(
                binding.scope,
                binding.owner,
                requiredLong(arguments, "expected_scope_version"),
                operations,
                token);
        StringBuilder report =
            new StringBuilder(binding.header(result.scopeVersion))
                .append("status ")
                .append(result.status)
                .append("\n")
                .append(result.message)
                .append('\n');
        for (ProjectMutationService.Applied applied : result.applied) {
          report.append("APPLIED ").append(applied.kind).append(' ').append(applied.path);
          if (applied.destination != null) report.append(" -> ").append(applied.destination);
          report
              .append("\nbefore_sha256 ")
              .append(applied.beforeSha)
              .append("\nafter_sha256 ")
              .append(applied.afterSha)
              .append("\n")
              .append(applied.diff)
              .append('\n');
        }
        return binding.receipt(
            report.toString(), result.isSuccess(), budget, !result.applied.isEmpty());
      } catch (IOException | IllegalArgumentException failure) {
        token.throwIfCancelled();
        return CoreToolResult.failure("Project operation failed: " + failure.getMessage());
      }
    }

    private CoreToolResult adoption(
        Map<String, Object> arguments, CancellationToken token, int budget) throws IOException {
      String action = CodingProjectTools.string(arguments, "action");
      if (action.equals("review")) {
        List<String> paths = new ArrayList<>();
        for (Object value : CodingProjectTools.list(arguments, "paths")) {
          if (!(value instanceof String)) {
            throw new IllegalArgumentException("paths must contain strings.");
          }
          paths.add(CodingProjectTools.projectPath((String) value));
        }
        ProjectAdoptionService.Review review =
            this.binding.adoption.review(this.binding.scope, paths, token);
        String id = UUID.randomUUID().toString();
        this.binding.reviews.put(id, review);
        StringBuilder text =
            new StringBuilder(this.binding.header())
                .append("review_id ")
                .append(id)
                .append(
                    "\n"
                        + "No files copied. Apply this reviewed allowlist explicitly with"
                        + " expected_scope_version.\n");
        for (ProjectAdoptionService.Entry entry : review.entries) {
          text.append(entry.status)
              .append(' ')
              .append(entry.path)
              .append(" | bytes ")
              .append(entry.size)
              .append(" | source_sha256 ")
              .append(entry.sha)
              .append(" | destination_sha256 ")
              .append(entry.destinationSha)
              .append('\n');
        }
        return this.binding.receipt(text.toString(), true, budget);
      }
      if (!action.equals("apply")) {
        throw new IllegalArgumentException("action must be review or apply.");
      }
      if (budget
          < ("Saved operation result, characters 2147483647 of 2147483647\n\n"
                      + "More operation output: call this tool with result_cursor=\""
                      + UUID.randomUUID()
                      + ":2147483647\" only. This never repeats a mutation.\n")
                  .length()
              + 2) {
        throw new IllegalArgumentException(
            "Response budget cannot fit an operation receipt; no copies made.");
      }
      ProjectAdoptionService.Review review2 =
          this.binding.reviews.get(CodingProjectTools.string(arguments, "review_id"));
      if (review2 == null) {
        throw new IllegalArgumentException(
            "Unknown review_id; review the explicit file list first.");
      }
      ProjectAdoptionService.Result result =
          this.binding.adoption.adopt(
              this.binding.scope,
              this.binding.owner,
              CodingProjectTools.requiredLong(arguments, "expected_scope_version"),
              review2,
              token);
      return this.binding.receipt(
          this.binding.header(result.scopeVersion)
              + "status "
              + result.status
              + "\n"
              + result.message
              + "\nCopied: "
              + String.join(", ", result.copied)
              + "\nAlready present: "
              + String.join(", ", result.alreadyPresent)
              + "\n",
          result.status == ProjectAdoptionService.Status.COMPLETE,
          budget,
          true ^ result.copied.isEmpty());
    }
  }

  public static List<ProjectMutationService.Operation> parseOperations(
      Map<String, Object> arguments) {
    List<ProjectMutationService.Operation> result = new ArrayList<>();
    for (Object raw : list(arguments, "operations")) {
      Map<String, Object> op = object(raw, "operation");
      String path = projectPath(string(op, "path"));
      switch (string(op, "op")) {
        case "add":
          result.add(ProjectMutationService.Operation.add(path, string(op, "content")));
          break;
        case "write":
          result.add(
              ProjectMutationService.Operation.write(
                  path, string(op, "expected_sha256"), string(op, "content")));
          break;
        case "edit":
          List<ProjectMutationService.Hunk> hunks = new ArrayList<>();
          for (Object h : list(op, "hunks")) {
            Map<String, Object> hunk = object(h, "hunk");
            hunks.add(
                new ProjectMutationService.Hunk(
                    string(hunk, "old_text"), string(hunk, "new_text")));
          }
          result.add(
              ProjectMutationService.Operation.edit(path, string(op, "expected_sha256"), hunks));
          break;
        case "move":
          result.add(
              ProjectMutationService.Operation.move(
                  path,
                  string(op, "expected_sha256"),
                  projectPath(string(op, "destination")),
                  string(op, "expected_destination_sha256")));
          break;
        case "delete":
          result.add(ProjectMutationService.Operation.delete(path, string(op, "expected_sha256")));
          break;
        default:
          throw new IllegalArgumentException("Unknown patch operation.");
      }
    }
    return result;
  }

  public static String projectPath(String path) {
    if (path.equals("/project") || path.equals("/project/")) {
      return ".";
    }
    return path.startsWith("/project/") ? path.substring("/project/".length()) : path;
  }

  public static String string(Map<String, Object> arguments, String key) {
    Object value = arguments.get(key);
    if (value instanceof String) {
      return (String) value;
    }
    throw new IllegalArgumentException(key + " must be a string.");
  }

  public static String optionalString(
      Map<String, Object> arguments, String key, String defaultValue) {
    return arguments.containsKey(key) ? string(arguments, key) : defaultValue;
  }

  public static boolean bool(Map<String, Object> arguments, String key, boolean fallback) {
    if (!arguments.containsKey(key)) {
      return fallback;
    }
    Object value = arguments.get(key);
    if (value instanceof Boolean) {
      return ((Boolean) value).booleanValue();
    }
    throw new IllegalArgumentException(key + " must be a boolean.");
  }

  public static int integer(Map<String, Object> args, String key, int fallback) {
    long value = longNumber(args, key, fallback);
    if (value < 1 || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(key + " must be a positive platform integer.");
    }
    return (int) value;
  }

  public static long requiredLong(Map<String, Object> args, String key) {
    if (args.containsKey(key)) {
      return longNumber(args, key, 0L);
    }
    throw new IllegalArgumentException(key + " is required.");
  }

  public static long longNumber(Map<String, Object> args, String key, long fallback) {
    Object value = args.get(key);
    if (value == null) {
      return fallback;
    }
    if ((value instanceof Number)
        && Double.isFinite(((Number) value).doubleValue())
        && ((Number) value).doubleValue() == ((Number) value).longValue()) {
      return ((Number) value).longValue();
    }
    throw new IllegalArgumentException(key + " must be an integer.");
  }

  public static List<?> list(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value instanceof List) {
      return (List) value;
    }
    throw new IllegalArgumentException(key + " must be an array.");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object raw, String name) {
    if (raw instanceof Map) {
      return (Map<String, Object>) raw;
    }
    throw new IllegalArgumentException(name + " must be an object.");
  }

  public static Map<String, String> properties(String... values) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      map.put(values[i], values[i + 1]);
    }
    return map;
  }

  public static String repeat(char value, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, value);
    return new String(chars);
  }

  public static Map<String, Object> schema(Map<String, String> properties, List<String> required) {
    Map<String, Object> linkedHashMap = new LinkedHashMap<>();
    for (Map.Entry<String, String> field : properties.entrySet()) {
      linkedHashMap.put(
          field.getKey(), new LinkedHashMap<>(Collections.singletonMap("type", field.getValue())));
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", linkedHashMap);
    schema.put("required", required);
    schema.put("additionalProperties", false);
    return schema;
  }

  @SuppressWarnings("unchecked")
  public static void setArrayItems(
      Map<String, Object> schema, String key, Map<String, Object> item) {
    Map<String, Object> fields = (Map<String, Object>) schema.get("properties");
    ((Map<String, Object>) fields.get(key)).put("items", item);
  }

  public static void addPatchSchema(Map<String, Object> schema) {
    Map<String, String> fields =
        properties(
            "op",
            "string",
            "path",
            "string",
            "expected_sha256",
            "string",
            "content",
            "string",
            "destination",
            "string",
            "expected_destination_sha256",
            "string",
            "hunks",
            "array");
    Map<String, Object> operation = schema(fields, Arrays.asList("op", "path"));
    setArrayItems(
        operation,
        "hunks",
        schema(
            properties("old_text", "string", "new_text", "string"),
            Arrays.asList("old_text", "new_text")));
    setArrayItems(schema, "operations", operation);
  }
}
