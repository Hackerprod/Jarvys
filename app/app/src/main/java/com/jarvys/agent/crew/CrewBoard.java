package com.jarvys.agent.crew;

import com.jarvys.agent.WorkspaceStore;

/** Restricts Crew shared files to the conversation workspace's /board/ subtree. */
public final class CrewBoard {
    private final WorkspaceStore workspace;
    public CrewBoard(WorkspaceStore workspace) { this.workspace = workspace; }

    public java.util.List<String> list() {
        java.util.List<String> result = new java.util.ArrayList<>();
        list("board", "/board", result);
        return java.util.Collections.unmodifiableList(result);
    }

    private void list(String relativeDirectory, String displayDirectory, java.util.List<String> result) {
        java.util.List<String> entries;
        try { entries = workspace.list(relativeDirectory); }
        catch (IllegalArgumentException missingOrUnsafe) { return; }
        for (String entry : entries) {
            if (entry.startsWith("DIR  ")) {
                String child = entry.substring(5);
                list(relativeDirectory + "/" + child, displayDirectory + "/" + child, result);
            } else if (entry.startsWith("FILE ")) {
                String file = entry.substring(5);
                int metadata = file.indexOf(" (");
                String name = metadata < 0 ? file : file.substring(0, metadata);
                String size = metadata < 0 ? "" : file.substring(metadata);
                result.add("FILE " + displayDirectory + "/" + name + size);
            }
        }
    }

    public void post(String path, String content) {
        workspace.write(boardPath(path), content);
    }
    public String read(String path) { return workspace.read(boardPath(path)); }

    private static String boardPath(String path) {
        if (path == null) throw new IllegalArgumentException("path must be a /board/<file> path");
        String value = path.trim();
        String relative;
        if (value.startsWith("/board/")) relative = value.substring("/board/".length());
        else if (value.startsWith("board/")) relative = value.substring("board/".length());
        else throw new IllegalArgumentException("Crew files must be inside /board/");
        if (relative.isEmpty() || relative.startsWith("/") || relative.startsWith("\\")
                || relative.matches("^[A-Za-z]:.*") || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid /board/ path");
        }
        for (String segment : relative.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
                throw new IllegalArgumentException("Invalid /board/ path segment");
        }
        return "board/" + relative;
    }
}
