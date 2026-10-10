package com.jarvys.agent.apkfactory

/** Factory native external interactions have separate durable journals but only one active host interaction.
 * Every restored open/broken journal owns a slot independently; closing one never releases another.
 * A worker blocked in a hostile Binder call keeps its slot until it actually terminates.
 */
internal object FactoryInteractionAdmission {
    private val owners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    @Synchronized fun available() = owners.isEmpty()
    @Synchronized fun acquire(owner: Any) { check(owners.isEmpty()) { "A Factory native interaction is already in progress" }; owners.add(owner) }
    @Synchronized fun restore(owner: Any) { owners.add(owner) }
    @Synchronized fun release(owner: Any) { owners.remove(owner) }
}
