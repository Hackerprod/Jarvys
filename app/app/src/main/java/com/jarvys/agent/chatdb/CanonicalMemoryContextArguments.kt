package com.jarvys.agent.chatdb

import java.math.BigDecimal

/** MCP context type assertions/defaults, intentionally distinct from strict prompt-search arguments. */
object CanonicalMemoryContextArguments {
    @JvmStatic fun maxBytes(raw:Any?):Int {
        if(raw !is Number) return 16384
        if(raw is Double || raw is Float) {
            val value=raw.toDouble()
            if(value==Double.POSITIVE_INFINITY) return 65536
            if(!value.isFinite()) return 16384
        }
        val value=try { BigDecimal(raw.toString()) } catch(_:NumberFormatException) { return 16384 }
        if(value.signum()<=0 || value.stripTrailingZeros().scale()>0) return 16384
        if(value>BigDecimal.valueOf(65536)) return 65536
        return value.toInt()
    }

    @JvmStatic fun prepare(args:Map<String,Any?>):Map<String,Any?> = LinkedHashMap(args).apply {
        put("project",args["project"] as? String ?: "")
        put("scope",args["scope"] as? String ?: "")
        put("compact",args["compact"] as? Boolean ?: false)
        put("older_history",args["older_history"] as? Boolean ?: false)
        put("max_bytes",maxBytes(args["max_bytes"]))
    }
}
