# WebView evidence checkpoints

The original `checkpoint.json` and its 320 files retain their b417d94 identities. Its unsupported-operation preflight assertion was not sufficient to prove synchronous gate enforcement. The two affected original cohorts remain historical.

Use [the repaired matrix](audit-repair/matrix.json) and [repair checkpoint](audit-repair/checkpoint.json) for current credit. The repair substitutes two newly completed whole cohorts, preserves unaffected outcomes at their original binaries, and separately records a deliberately regressed guard. The old test passes that mutant; the strengthened test fails its intended supported-operation assertion. Neither mutation result earns candidate-runtime credit.
