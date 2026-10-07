#!/usr/bin/env python3
"""Generate the bounded single-emoji allowlist from Unicode's pinned emoji-test.txt."""
import hashlib
import json
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_bytes()
expected = '24f0c534e86cf142e2496953e8f0e46a3e702392911eddcd29c6cced85139697'
assert hashlib.sha256(source).hexdigest() == expected, 'Unexpected Unicode source version/content'
values = []
for line in source.decode('utf-8').splitlines():
    data = line.split('#', 1)[0].strip()
    if not data: continue
    codepoints, status = [part.strip() for part in data.split(';')]
    if status not in ('fully-qualified', 'minimally-qualified', 'unqualified'): continue
    values.append(''.join(chr(int(point, 16)) for point in codepoints.split()))
values = sorted(set(values))
chunks = [values[i:i+200] for i in range(0, len(values), 200)]
text = '''package com.jarvys.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Generated Unicode 16.0 single-emoji forms, excluding isolated components.
 * Source: https://unicode.org/Public/emoji/16.0/emoji-test.txt
 * Copyright © 2024 Unicode, Inc. See UNICODE-LICENSE.txt.
 * Regenerate with tools/generate_reaction_emoji.py and the hash-verified source file.
 * Font/glyph availability depends on Android; this validates sequences, not rendering support.
 */
public final class MessageReactionEmoji {
    private MessageReactionEmoji() { }
    private static final String[] DATA = {
'''
text += '\n'.join('        ' + json.dumps('\n'.join(chunk), ensure_ascii=True) + ',' for chunk in chunks)
text += '''
    };
    private static final Set<String> VALUES = createValues();
    private static Set<String> createValues() {
        Set<String> values = new HashSet<>();
        for (String chunk : DATA) values.addAll(Arrays.asList(chunk.split("\\n")));
        return Collections.unmodifiableSet(values);
    }
    public static boolean isValid(String value) { return value != null && VALUES.contains(value); }
}
'''
out = Path(__file__).resolve().parents[1] / 'app/app/src/main/java/com/jarvys/agent/MessageReactionEmoji.java'
out.write_text(text)
print(f'{len(values)} exact emoji forms; longest {max(len(x.encode("utf-16-le"))//2 for x in values)} UTF-16 units; SHA256 {expected}')
