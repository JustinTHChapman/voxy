package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory45;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL30C.*;

//Core class that ingests new data from sources and updates the required systems

//3 primary services:
// ingest service: this takes in unloaded chunk events from the client, processes the chunk and critically also updates the lod view of the world
// render data builder service: this service builds the render data from build requests it also handles the collecting of build data for the selected region (only axis aligned single lod tasks)
// serialization service: serializes changed world data and ensures that the database and any loaded data are in sync such that the database can never be more updated than loaded data, also performs compression on serialization

//there are multiple subsystems
//player tracker system (determines what lods are loaded and used by the player)
//updating system (triggers render data rebuilds when something from the ingest service causes an LOD change)
//the render system simply renders what data it has, its responsable for gpu memory layouts in arenas and rendering in an optimal way, it makes no requests back to any of the other systems or services, it just applies render data updates

//There is strict forward only dataflow
//Ingest -> world engine -> raw render data -> render data



//REDESIGN THIS PIECE OF SHIT SPAGETTY SHIT FUCK
// like Get rid of interactor and renderer being seperate just fucking put them together
// fix the callback bullshit spagetti
//REMOVE setRenderGen like holy hell
public class VoxelCore {
    private final WorldEngine world;
    public final ServiceThreadPool serviceThreadPool;
    public final WorldImportWrapper importer;

    public VoxelCore(ContextSelectionSystem.Selection worldSelection) {
        var cfg = worldSelection.getConfig();
        this.serviceThreadPool = new ServiceThreadPool(VoxyConfig.CONFIG.serviceThreads);

        this.world = null;//worldSelection.createEngine(this.serviceThreadPool);
        Logger.info("Initializing voxy core");

        this.importer = new WorldImportWrapper(this.serviceThreadPool, this.world);

        Logger.info("Voxy core initialized");

        //this.verifyTopNodeChildren(0,0,0);

        //this.testMeshingPerformance();

        //this.testDbPerformance();
        //this.testFullMesh();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("");
        debug.add("");
        /*
        debug.add("Ingest service tasks: " + this.world.ingestService.getTaskCount());
        debug.add("Saving service tasks: " + this.world.savingService.getTaskCount());
        debug.add("Render service tasks: " + this.renderGen.getTaskCount());
         */
        this.world.addDebugData(debug);

    }

    //Note: when doing translucent rendering, only need to sort when generating the geometry, or when crossing into the center zone
    // cause in 99.99% of cases the sections dont need to be sorted
    // since they are AABBS crossing the normal is impossible without one of the axis being equal

    public void shutdown() {

        //if (Thread.currentThread() != this.shutdownThread) {
        //    Runtime.getRuntime().removeShutdownHook(this.shutdownThread);
        //}

        //this.world.getMapper().forceResaveStates();
        this.importer.shutdown();
        Logger.info("Shutting down world engine");
        try {this.world.free();} catch (Exception e) {Logger.error("Error shutting down world engine", e);}
        Logger.info("Shutting down service thread pool");
        this.serviceThreadPool.shutdown();
        Logger.info("Voxel core shut down");
    }

    public WorldEngine getWorldEngine() {
        return this.world;
    }





    private void verifyTopNodeChildren(int X, int Y, int Z) {
        for (int lvl = 0; lvl < 5; lvl++) {
            for (int y = (Y<<5)>>lvl; y < ((Y+1)<<5)>>lvl; y++) {
                for (int x = (X<<5)>>lvl; x < ((X+1)<<5)>>lvl; x++) {
                    for (int z = (Z<<5)>>lvl; z < ((Z+1)<<5)>>lvl; z++) {
                        if (lvl == 0) {
                            var own = this.world.acquire(lvl, x, y, z);
                            if ((own.getNonEmptyChildren() != 0) ^ (own.getNonEmptyBlockCount() != 0)) {
                                Logger.error("Lvl 0 node not marked correctly " + WorldEngine.pprintPos(own.key));
                            }
                            own.release();
                        } else {
                            byte msk = 0;
                            for (int child = 0; child < 8; child++) {
                                var section = this.world.acquire(lvl-1, (child&1)+(x<<1), ((child>>2)&1)+(y<<1), ((child>>1)&1)+(z<<1));
                                msk |= (byte) (section.getNonEmptyBlockCount()!=0?(1<<child):0);
                                section.release();
                            }
                            var own = this.world.acquire(lvl, x, y, z);
                            if (own.getNonEmptyChildren() != msk) {
                                Logger.error("Section empty child mask not correct " + WorldEngine.pprintPos(own.key) + " got: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(own.getNonEmptyChildren()))).replace(' ', '0') + " expected: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(msk))).replace(' ', '0'));
                            }
                            own.release();
                        }
                    }
                }
            }
        }
    }







}
