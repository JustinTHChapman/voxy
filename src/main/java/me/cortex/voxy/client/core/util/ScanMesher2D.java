package me.cortex.voxy.client.core.util;

public class ScanMesher2D {
    // is much faster if implemented inline into parent
    private long[] rowData = new long[32];
    private int[] rowLength = new int[32];//How long down does a row entry go
    private int[] rowDepth = new int[32];//How many rows does it cover
    private int rowBitset = 0;

    int currentIndex = 0;
    int currentSum = 0;
    long currentData = 0;

    //Two different ways to do it, scanline then only merge on change, or try to merge with previous row at every step
    // or even can also attempt to merge previous but if the lengths are different split the current one and merge to previous
    public void putNext(long data) {
        int thisIdx = (this.currentIndex++)&31;//Mask to current row, but keep total so can compute actual indexing

        //If we are on the zero index, ignore it as we are going from empty state to maybe something state
        // setup data
        if (thisIdx == 0) {
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

        //If we are the same as last data increment
        if (data == this.currentData) {
            this.currentSum++;
            //If we merge, then just continue
            this.mergeCurrentIfPossibleOrEmit(thisIdx);
            return;
        }

        //write out previous data
        if (this.currentData != 0) {
            int prev = thisIdx-1;//We need to write in the previous entry
            this.rowDepth[prev] = 1;
            this.rowLength[prev] = this.currentSum;
            this.rowData[prev] = this.currentData;
            this.rowBitset |= 1<<prev;
        }


        this.currentData = data;
        this.currentSum = 1;

        this.mergeCurrentIfPossibleOrEmit(thisIdx);
    }

    private boolean mergeCurrentIfPossibleOrEmit(int index) {
        boolean isSet = (this.rowBitset&(1<<index))!=0;
        //Greadily merge with previous row if possible
        if (this.currentData != 0 &&//Ignore sentinel empty
                isSet &&
                this.rowLength[index] == this.currentSum &&
                this.rowData[index] == this.currentData) {//Can merge with previous row
            this.rowDepth[index]++;
            this.currentSum = 0;//Clear sum since we went down
            this.currentData = 0;//Zero is sentinel value for absent
            return true;
        }

        if (isSet) {
            this.emitQuad(this.rowLength[index], this.rowDepth[index], this.rowData[index]);
            this.rowBitset &= ~(1<<index);
        }

        return false;
    }

    private boolean attemptMerge(int index, int length, long data) {
        if ((this.rowBitset & (1 << index)) != 0 &&//Entry in previous bitset
                this.rowLength[index] == length &&//If previous row merged length matches current
                this.rowData[index] == data) {//If the previous row entry data matches
            this.rowDepth[index]++;//Increment the depth
            return true;
        }
        return false;
    }

    //Emits quads that exist at the mask pos and clear
    private void emitRanged(int msk) {
        {//Emit quads that cover the previous indices
            int rowSet = this.rowBitset&msk;
            while (rowSet!=0) {//Need to emit quads that would have skipped, note that this does not include the current index
                int index = Integer.numberOfLeadingZeros(rowSet);
                rowSet &= ~Integer.lowestOneBit(rowSet);

                //Emit the quad, dont need to clear the data since it not existing in the bitmask is implicit no data
                this.emitQuad(this.rowLength[index], this.rowDepth[index], this.rowData[index]);
            }
            this.rowBitset &= ~msk;
        }
    }

    protected void emitQuad(int length, int width, long data) {
        System.err.println("Quad, length: " + length + " width:  " + width + " data: " + data );
    }

    public static void main(String[] args) {
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
        sample[31+32*2] = 7;
        sample[31+32*3] = 8;
        var mesher = new ScanMesher2D();
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
