package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    /**
     * Returns true if any section exists in storage for the given column
     * (fixed LOD level and section x/z) within the given Minecraft section Y range.
     * {@code minSectionY} and {@code maxSectionY} should come from
     * {@code Level.getMinSection()} / {@code Level.getMaxSection()}.
     * Default: false — subclasses should override for an efficient check.
     */
    public boolean containsColumn(int level, int sx, int sz, int minSectionY, int maxSectionY) {
        return false;
    }
}
