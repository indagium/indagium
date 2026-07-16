package com.openlog.utils

import java.util.concurrent.atomic.AtomicLong

// Process-wide, not per-call-site: two id factories seeded independently could still tie on the
// same millisecond, which is exactly the collision this counter exists to rule out.
private val idCounter = AtomicLong()

// (QUAL-2) Single id factory for every "$prefix${System.currentTimeMillis()}..." id previously
// hand-rolled at each call site (message rules, highlighters, sequences, manual-collapse blocks,
// note blocks). The timestamp keeps ids distinct across sessions and roughly time-ordered, which
// matches the shape already persisted in existing autosave/filter data; the counter suffix is what
// actually guarantees uniqueness — two highlighters (or sequences, or MCP-driven rule creations)
// added within the same millisecond, trivially reachable via rapid clicks or a scripted MCP client,
// would otherwise collide and silently overwrite one another in any Map keyed by id.
internal fun newId(prefix: String): String = "$prefix${System.currentTimeMillis()}_${idCounter.incrementAndGet()}"
