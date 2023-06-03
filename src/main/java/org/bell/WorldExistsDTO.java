package org.bell;

import java.io.Serializable;

public class WorldExistsDTO implements Serializable {
    private boolean world_exists;

    public WorldExistsDTO(boolean world_exists) {
        this.world_exists = world_exists;
    }

    public boolean getWorldExists() {
        return this.world_exists;
    }

    public void setWorldExists(boolean world_exists) {
        this.world_exists = world_exists;
    }

    @Override
    public String toString() {
        return "WorldExistsDTO{" +
                "world_exists=" + world_exists +
                '}';
    }

    public WorldExistsDTO() {
    }

}
