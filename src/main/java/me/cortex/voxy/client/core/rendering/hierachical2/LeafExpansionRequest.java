package me.cortex.voxy.client.core.rendering.hierachical2;

//Request of the leaf node to expand
class LeafExpansionRequest {
    //Child states contain micrometadata in the top bits
    // such as isEmpty, and isEmptyButEventuallyHasNonEmptyChild
    private final long nodePos;

    private final int[] childStates = new int[]{-1,-1,-1,-1,-1,-1,-1,-1};

    private byte results;
    private byte mask;

    LeafExpansionRequest(long nodePos) {
        this.nodePos = nodePos;
    }

    public int putChildResult(int childIdx, int mesh) {
        if ((this.mask&(1<<childIdx))==0) {
            throw new IllegalStateException("Tried putting child into leaf which doesnt match mask");
        }
        //Note the mesh can be -ve meaning empty mesh, but we should still mark that node as having a result
        boolean isFirstInsert = (this.results&(1<<childIdx))==0;
        this.results |= (byte) (1<<childIdx);

        int prev = this.childStates[childIdx];
        this.childStates[childIdx] = mesh;
        if (isFirstInsert) {
            return -1;
        } else {
            return prev;
        }
    }

    public int removeAndUnRequire(int childIdx) {
        byte MSK = (byte) (1<<childIdx);
        if ((this.mask&MSK)==0) {
            throw new IllegalStateException("Tried removing and unmasking child that was never masked");
        }
        byte prev = this.results;
        this.results &= (byte) ~MSK;
        this.mask &= (byte) ~MSK;
        int mesh = this.childStates[childIdx];
        this.childStates[childIdx] = -1;
        if ((prev&MSK)==0) {
            return -1;
        } else {
            return mesh;
        }
    }

    public void addChildRequirement(int childIdx) {
        byte MSK = (byte) (1<<childIdx);
        if ((this.mask&MSK)!=0) {
            throw new IllegalStateException("Child already required!");
        }
        this.mask |= MSK;
    }

    public boolean isSatisfied() {
        return (this.results&this.mask)==this.mask;
    }
}
