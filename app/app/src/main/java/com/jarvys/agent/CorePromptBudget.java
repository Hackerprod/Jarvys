package com.jarvys.agent;

/** Per-run character budgets; kept configurable so a host can tune prompt cost without editing the loop. */
public final class CorePromptBudget {
    public final int transcriptChars;
    public final int skillCatalogChars;
    public final int skillsInCatalog;
    public final int loadedSkillChars;
    public final int toolResultsPerTurnChars;
    public final int memoryChars;

    public CorePromptBudget(int transcriptChars, int skillCatalogChars, int skillsInCatalog,
                            int loadedSkillChars, int toolResultsPerTurnChars) {
        this(transcriptChars, skillCatalogChars, skillsInCatalog, loadedSkillChars,
                toolResultsPerTurnChars, MemoryConstants.MAX_PROMPT_CHARACTERS);
    }

    public CorePromptBudget(int transcriptChars, int skillCatalogChars, int skillsInCatalog,
                            int loadedSkillChars, int toolResultsPerTurnChars, int memoryChars) {
        if (transcriptChars < 4096 || skillCatalogChars < 256 || skillsInCatalog < 1
                || loadedSkillChars < 256 || toolResultsPerTurnChars < loadedSkillChars
                || memoryChars < MemoryConstants.MAX_CORE_MEMORY_CHARACTERS) {
            throw new IllegalArgumentException("Core prompt budgets are too small or inconsistent");
        }
        this.transcriptChars = transcriptChars;
        this.skillCatalogChars = skillCatalogChars;
        this.skillsInCatalog = skillsInCatalog;
        this.loadedSkillChars = loadedSkillChars;
        this.toolResultsPerTurnChars = toolResultsPerTurnChars;
        this.memoryChars = memoryChars;
    }

    public static CorePromptBudget standard() {
        return new CorePromptBudget(64 * 1024, 10 * 1024, 40, 16 * 1024, 24 * 1024,
                MemoryConstants.MAX_PROMPT_CHARACTERS);
    }
}
