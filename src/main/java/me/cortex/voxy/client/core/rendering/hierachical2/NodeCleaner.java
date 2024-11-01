package me.cortex.voxy.client.core.rendering.hierachical2;

//Composed of 2 (3) parts
// a node cleaner
// and a geometr/section cleaner
public class NodeCleaner {


    //Clears memory via invoking node manager, clear section or delete node
    // these should take hint parameters on if when removing section data, to store it in section cache or leave

    public void setNodeMemoryUsage() {
        //Needs to bubble up information to all parents
        // also needs to update a tick/last seen time for node removal
    }
}
