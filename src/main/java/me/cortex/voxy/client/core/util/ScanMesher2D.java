package me.cortex.voxy.client.core.util;

import java.util.Random;

public abstract class ScanMesher2D {
    // is much faster if implemented inline into parent
    private final long[] rowData = new long[32];
    private final int[] rowLength = new int[32];//How long down does a row entry go
    private final int[] rowDepth = new int[32];//How many rows does it cover
    private int rowBitset = 0;

    int currentIndex = 0;
    int currentSum = 0;
    long currentData = 0;

    //Two different ways to do it, scanline then only merge on change, or try to merge with previous row at every step
    // or even can also attempt to merge previous but if the lengths are different split the current one and merge to previous
    public final void putNext(long data) {
        int idx = (this.currentIndex++)&31;//Mask to current row, but keep total so can compute actual indexing

        //If we are on the zero index, ignore it as we are going from empty state to maybe something state
        // setup data
        if (idx == 0) {
            //If the previous data is not zero, that means it was not merge-able, so emit it at the pos
            if (this.currentData!=0) {
                if ((this.rowBitset&(1<<31))!=0) {
                    emitQuad(this.rowLength[31], this.rowDepth[31], this.rowData[31]);
                }
                this.rowBitset |= 1<<31;
                this.rowLength[31] = this.currentSum;
                this.rowDepth[31] = 1;
                this.rowData[31] = this.currentData;
            }

            //Set the data to the first element
            this.currentData = data;
            this.currentSum = 0;
        }

        //If we are different from previous (this can never happen if previous is index 0)
        if (data != this.currentData) {
            //write out previous data if its a non sentinel, it is guarenteed to not have a row bit set
            if (this.currentData != 0) {
                int prev = idx-1;//We need to write in the previous entry
                if ((this.rowBitset&(1<<prev))!=0) {
                    throw new IllegalStateException();
                }
                this.rowDepth[prev] = 1;
                this.rowLength[prev] = this.currentSum;
                this.rowData[prev] = this.currentData;
                this.rowBitset |= 1<<prev;
            }

            this.currentData = data;
            this.currentSum = 0;
        }
        this.currentSum++;


        boolean isSet = (this.rowBitset&(1<<idx))!=0;
        //Greadily merge with previous row if possible
        if (this.currentData != 0 &&//Ignore sentinel empty
                isSet &&
                this.rowLength[idx] == this.currentSum &&
                this.rowData[idx] == this.currentData) {//Can merge with previous row
            this.rowDepth[idx]++;
            this.currentSum = 0;//Clear sum since we went down
            this.currentData = 0;//Zero is sentinel value for absent
        } else if (isSet) {
            this.emitQuad(this.rowLength[idx], this.rowDepth[idx], this.rowData[idx]);
            this.rowBitset &= ~(1<<idx);
        }
    }

    //Emits quads that exist at the mask pos and clear
    private void emitRanged(int msk) {
        {//Emit quads that cover the previous indices
            int rowSet = this.rowBitset&msk;
            while (rowSet!=0) {//Need to emit quads that would have skipped, note that this does not include the current index
                int index = Integer.numberOfTrailingZeros(rowSet);
                rowSet &= ~Integer.lowestOneBit(rowSet);

                //Emit the quad, dont need to clear the data since it not existing in the bitmask is implicit no data
                this.emitQuad(this.rowLength[index], this.rowDepth[index], this.rowData[index]);
            }
            this.rowBitset &= ~msk;
        }
    }

    public final void endPush() {
        putNext(0);
        this.currentIndex--;//HACK
        this.emitRanged(-1);
    }

    protected abstract void emitQuad(int length, int width, long data);

    public static void main(String[] args) {
        var r = new Random(0);
        long[] data = new long[32*32];
        float DENSITY = 0.5f;
        int RANGE = 50;
        for (int i = 0; i < data.length; i++) {
            data[i] = r.nextFloat()<DENSITY?(r.nextInt(RANGE)+1):0;
        }

        int[] qc = new int[2];
        var mesher = new ScanMesher2D(){
            @Override
            protected void emitQuad(int length, int width, long data) {
                qc[0]++;
                qc[1]+=length*width;
            }
        };

        for (int i = 0; i < 500000; i++) {
            for (long v : data) {
                mesher.putNext(v);
            }
            mesher.endPush();
        }

        var m2 = new Mesher2D();
        for (int i = 0; i < 500000; i++) {
            int j = 0;
            m2.reset();
            for (long v : data) {
                if (v!=0)
                    m2.put(j&31, j>>5, v);
                j++;
            }
            m2.process();
        }

        long t = System.nanoTime();
        for (int i = 0; i < 1000000; i++) {
            for (long v : data) {
                mesher.putNext(v);
            }
            mesher.endPush();
        }
        long delta = System.nanoTime()-t;
        System.out.println(delta*1e-6);


        t = System.nanoTime();
        for (int i = 0; i < 1000000; i++) {
            int j = 0;
            m2.reset();
            for (long v : data) {
                if (v!=0)
                    m2.put(j&31, j>>5, v);
                j++;
            }
            m2.process();
        }
        delta = System.nanoTime()-t;
        System.out.println(delta*1e-6);


    }
    public static void main3(String[] args) {
        var r = new Random(0);
        int[] qc = new int[2];
        var mesher = new ScanMesher2D(){
            @Override
            protected void emitQuad(int length, int width, long data) {
                qc[0]++;
                qc[1]+=length*width;
            }
        };

        var mesh2 = new Mesher2D();

        float DENSITY = 0.5f;
        int RANGE = 50;
        int total = 0;
        while (true) {
            DENSITY = r.nextFloat();
            RANGE = r.nextInt(500)+1;
            qc[0] = 0; qc[1] = 0;
            int c = 0;
            for (int i = 0; i < 32*32; i++) {
                long val = r.nextFloat()<DENSITY?(r.nextInt(RANGE)+1):0;
                c += val==0?0:1;
                mesher.putNext(val);
                if (val != 0) {
                    mesh2.put(i&31, i>>5, val);
                }
            }
            mesher.endPush();
            if (c != qc[1]) {
                System.out.println(c+", " + qc[1]);
            }
            int count = mesh2.process();
            int delta = count - qc[0];
            total += delta;
            System.out.println(total);
            //System.out.println(c+", new: " + qc[0] + " old: " + count);
        }
    }

    public static void main2(String[] args) {
        long[] sample = new long[32*32];

        sample[0] = 1;
        sample[1] = 1;
        sample[2] = 1;
        sample[3] = 1;
        sample[4] = 2;
        sample[5] = 2;
        sample[6] = 2;
        sample[7] = 2;
        sample[0+32*1] = 1;
        sample[1+32*1] = 1;
        sample[2+32*1] = 1;
        sample[3+32*1] = 1;
        sample[4+32*1] = 2;
        sample[5+32*1] = 2;
        sample[6+32*1] = 2;
        sample[7+32*1] = 2;
        sample[31+32*0] = 6;
        sample[31+32*1] = 6;
        sample[30+32*2] = 7;
        sample[31+32*2] = 7;
        sample[30+32*3] = 7;
        sample[31+32*3] = 7;
        sample[31+32*8] = 8;
        var mesher = new ScanMesher2D() {
            @Override
            protected void emitQuad(int length, int width, long data) {
                System.out.println(length + ", " + width + ", " + data);
            }
        };
        int j = 0;
        for (long i : sample) {
            if (j%32 == 0) {
                System.out.println("row");
            }
            mesher.putNext(i);
            j++;
        }
    }
}
