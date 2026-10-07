package com.jarvys.agent

/** File-backed MemoryStore tests supply deterministic English/Spanish seed variants. */
internal fun testMemorySeedProvider(selected: AppLanguageChoice = AppLanguageChoice.SPANISH) =
    object : MemorySeedTextProvider {
        private val english = MemorySeedTextProvider.SeedTexts(
            "# Memory\n\nIndex of durable memories about the user. Keep links to detailed notes here; consult each index before reading details.\n\n- [User](human.md)\n- [Interaction preferences](persona.md)\n",
            "---\nname: User\ndescription: Information, interests, preferences, and personal context the user has explicitly shared.\n---\n",
            "---\nname: Interaction preferences\ndescription: Explicit preferences about language, tone, style, and how to help the user.\n---\n",
        )
        private val spanish = MemorySeedTextProvider.SeedTexts(
            "# Memoria\n\nÍndice de recuerdos duraderos sobre la persona usuaria. Mantén aquí enlaces a notas ampliadas; consulta cada índice antes de leer sus detalles.\n\n- [Persona usuaria](human.md)\n- [Preferencias de interacción](persona.md)\n",
            "---\nname: Persona usuaria\ndescription: Datos, gustos, preferencias y contexto personal que la persona haya compartido explícitamente.\n---\n",
            "---\nname: Preferencias de interacción\ndescription: Preferencias explícitas sobre idioma, tono, estilo y forma de ayudar a esta persona.\n---\n",
        )
        override fun current() = if (selected == AppLanguageChoice.SPANISH) spanish else english
        override fun english() = english
        override fun spanish() = spanish
    }
