package com.jarvys.agent.apkfactory

/** Factory native external interactions have separate durable journals but only one active host interaction.
 * Each family owns its own slot; historical uncertainty never blocks unrelated families.
 * A worker blocked in a hostile Binder call keeps its slot until it actually terminates.
 */
internal object FactoryInteractionAdmission {
    private val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    @Synchronized fun available() = owners.isEmpty()
    @Synchronized fun available(owner: Any) = !owners.contains(owner)
    @Synchronized fun acquire(owner: Any) { check(!owners.contains(owner)) { "This Factory interaction already owns its resource slot" }; owners.add(owner) }
    @Synchronized fun restore(owner: Any) { owners.add(owner) }
    @Synchronized fun release(owner: Any) { owners.remove(owner) }
}
